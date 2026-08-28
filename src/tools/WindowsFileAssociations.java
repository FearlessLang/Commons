package tools;

import java.lang.invoke.MethodHandle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import utils.Bug;
import utils.Push;

public final class WindowsFileAssociations implements FileAssociations{
  static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  private final String name;
  private final String command;
  private final Path ico;
  private final Function<String,RuntimeException> stepFailed;
  private final Function<String,RuntimeException> notTaken;
  private final Function<String,RuntimeException> halfDone;
  private final LinkedHashMap<String,Path> icons= new LinkedHashMap<>();
  WindowsFileAssociations(String name, String command, Path ico,
      Function<String,RuntimeException> notOurs, Function<String,RuntimeException> stepFailed,
      Function<String,RuntimeException> notTaken, Function<String,RuntimeException> halfDone,
      Function<String,RuntimeException> halfThere){
    this.name= name; this.command= command; this.ico= ico;
    this.stepFailed= stepFailed; this.notTaken= notTaken; this.halfDone= halfDone;
    var listed= regValue(hkcu(registeredApplications), name).isPresent();
    if (listed != regValue(hkcu(capabilities(name)), "ApplicationName").isPresent()){
      throw halfThere.apply(registeredApplications+"\n"+capabilities(name));
    }
    if (!listed){ write(); return; }
    icons.putAll(declared());
    var lost= icons.keySet().stream().filter(e->!progId(name, e).equals(effective(e).orElse(""))).toList();
    if (!lost.isEmpty()){ acquire(lost); }
  }
  public List<String> owned(){ return List.copyOf(icons.keySet()); }
  public void acquire(List<String> exts){
    var was= new LinkedHashMap<>(icons);
    var undo= new WinUndo(name, Push.<String>of(List.copyOf(icons.keySet()), exts));
    exts.stream().filter(e->!icons.containsKey(e)).forEach(e->icons.put(e, ico));
    try { write(); }
    catch(RuntimeException e){ back(was, undo); throw e; }
    for (var ext: exts){ settle(ext, was, undo); }
  }
  public void release(List<String> exts){ edit(()->drop(exts)); }
  public void setIcon(List<Icon> wanted){ edit(()->wanted.forEach(this::put)); }
  ///Only our own keys change here, so what was written is all there is to put back.
  private void edit(Runnable body){
    var was= new LinkedHashMap<>(icons);
    var undo= new WinUndo(name, List.copyOf(icons.keySet()));
    try { body.run(); write(); }
    catch(RuntimeException e){ back(was, undo); throw e; }
  }
  private void drop(List<String> exts){
    exts.forEach(icons::remove);
    exts.forEach(this::forget);
  }
  //A program's own picture is compiled into the program, so the most that can be said here is
  //the one Settings shows on the page where this app is picked; jpackage put in the rest.
  public void setProgramIcon(Path ico, Path png){
    Shell.req(importReg("Windows Registry Editor Version 5.00\r\n"
      +regEntry(capabilities(name), "ApplicationIcon", ico+",0")), stepFailed);
  }
  private void put(Icon i){
    assert icons.containsKey(i.extension());
    icons.put(i.extension(), i.ico());
  }
  private void back(Map<String,Path> was, WinUndo undo){
    icons.clear();
    icons.putAll(was);
    undo.restore(halfDone);
  }
  private void forget(String ext){
    kill(hkcu(classes)+progId(name, ext));
    kill(hkcu(classes)+ext);
    Shell.exec(List.of("reg","delete",hkcu(fileAssociations(name)),"/v",ext,"/f"));
  }
  private static void kill(String key){ Shell.exec(List.of("reg","delete",key,"/f")); }
  private void write(){ Shell.req(importReg(regFile(name, command, icons)), stepFailed); }
  //The answer the desktop remembers is the user's own, given by hand, and only
  //another answer given by hand takes its place: no program writes it, and no
  //program removes it. The most we can do is open the window where it is
  //given, and wait there with them until they have answered.
  private void settle(String ext, Map<String,Path> was, WinUndo undo){
    if (progId(name, ext).equals(effective(ext).orElse(""))){ return; }
    var before= givenByHand(ext);
    if (before.isPresent()){
      Shell.exec(whereToChoose(name));
      //Explorer writes other things under this key while the person browses, so the
      //answer they gave is the value changing, not the key changing.
      while (givenByHand(ext).equals(before)){ awaitChange(fileExts+ext); }
      if (progId(name, ext).equals(effective(ext).orElse(""))){ return; }
    }
    var current= effective(ext).orElse("nothing at all");
    back(was, undo);
    throw notTaken.apply(current);
  }
  private Map<String,Path> declared(){
    var res= new LinkedHashMap<String,Path>();
    regValues(hkcu(fileAssociations(name))).keySet()
      .forEach(ext->res.put(ext, iconOf(progId(name, ext))));
    return res;
  }
  private Path iconOf(String progId){
    var was= regValue(hkcu(classes)+progId+"\\DefaultIcon", "");
    return Path.of(was.map(s->s.substring(0, s.lastIndexOf(','))).orElseGet(ico::toString));
  }
  public static String progId(String name, String ext){ return name+"."+ext.substring(1); }
  public static String capabilities(String name){ return "HKEY_CURRENT_USER\\Software\\"+name+"\\Capabilities"; }
  public static String fileAssociations(String name){ return capabilities(name)+"\\FileAssociations"; }
  static final String fileExts= "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";
  public static String userChoiceKey(String ext){ return "HKCU\\"+fileExts+ext+"\\UserChoice"; }
  static Optional<String> givenByHand(String ext){ return regValue(userChoiceKey(ext),"ProgId"); }
  static Optional<String> effective(String ext){
    return givenByHand(ext).or(()->regValue(hkcu(classes)+ext,"")).filter(s->!s.isEmpty());
  }
  //Two registrations, and they answer two different questions. The file type says
  //what opens the file; the capabilities say that this is an app, what it is
  //called, and which types it handles. Only the second puts us on our own page
  //under Settings, which is the one place a default can be chosen.
  public static String regFile(String name, String command, Map<String,Path> icons){
    var res= new StringBuilder("Windows Registry Editor Version 5.00\r\n");
    res.append(regEntry(capabilities(name), "ApplicationName", name));
    icons.forEach((ext,ico)->res.append(oneKind(name, command, ext, ico)));
    res.append(regEntry(registeredApplications, name, "Software\\"+name+"\\Capabilities"));
    return res.toString();
  }
  ///One type of its own for every extension: the picture hangs off the type, so extensions
  ///sharing this program still show a picture each.
  private static String oneKind(String name, String command, String ext, Path ico){
    var progId= progId(name, ext);
    return regEntry(classes+progId, name)
      +regEntry(classes+progId+"\\DefaultIcon", ico+",0")
      +regEntry(classes+progId+"\\shell\\open\\command", "\""+command+"\" \"%1\"")
      +regEntry(classes+ext, progId)
      +regEntry(classes+ext+"\\OpenWithProgids", progId, "")
      +regEntry(fileAssociations(name), ext, progId);
  }
  //Settings is the only place the answer can be changed: a program cannot write it
  //and cannot remove it. This opens the page Settings keeps for this app, where
  //the types it handles are listed.
  public static List<String> whereToChoose(String name){
    var escaped= URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+","%20");
    return List.of("explorer.exe","ms-settings:defaultapps?registeredAppUser="+escaped);
  }
  private static List<String> importReg(String content){
    var file= Fs.of(()->Files.createTempFile("association",".reg"));
    Fs.ofV(()->Files.write(file, ("\uFEFF"+content).getBytes(StandardCharsets.UTF_16LE)));
    file.toFile().deleteOnExit();
    return List.of("reg","import",file.toString());
  }
  private static String regEntry(String key, String data){ return regEntry(key,"",data); }
  private static String regEntry(String key, String name, String data){
    var shown= name.isEmpty() ? "@" : "\""+regData(name)+"\"";
    return "\r\n["+key+"]\r\n"+shown+"=\""+regData(data)+"\"\r\n";
  }
  private static String regData(String data){ return data.replace("\\","\\\\").replace("\"","\\\""); }
  static Optional<String> regValue(String key, String name){
    var cmd= name.isEmpty()
      ? List.of("reg","query",key,"/ve")
      : List.of("reg","query",key,"/v",name);
    return Shell.exec(cmd).filter(ran->ran.code() == 0)
      .flatMap(ran->ran.out().lines().map(String::strip).filter(l->l.contains("REG_")).findFirst())
      .map(WindowsFileAssociations::regQueried);
  }
  ///Every value under one key at once: the extensions we hold are read from a single query.
  public static Map<String,String> regValues(String key){
    var res= new LinkedHashMap<String,String>();
    Shell.exec(List.of("reg","query",key)).filter(ran->ran.code() == 0)
      .ifPresent(ran->ran.out().lines().map(String::strip)
        .filter(l->l.contains("REG_")).forEach(l->res.put(regName(l), regQueried(l))));
    return res;
  }
  public static String regName(String line){ return line.substring(0, line.indexOf("REG_")).strip(); }
  private static String regQueried(String line){
    var end= line.indexOf(' ', line.indexOf("REG_"));
    return end < 0 ? "" : line.substring(end).strip();
  }
  //reg.exe wants the short root name; a .reg file wants the long one.
  static String hkcu(String key){ return "HKCU"+key.substring("HKEY_CURRENT_USER".length()); }
  //Blocks in place until anything under the key changes; the person may take as long as they need.
  private static void awaitChange(String subKey){
    try(var arena= Arena.ofConfined()){
      var linker= Linker.nativeLinker();
      var lib= SymbolLookup.libraryLookup("advapi32.dll", arena);
      var open= handle(linker, lib, "RegOpenKeyExW", FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      var notify= handle(linker, lib, "RegNotifyChangeKeyValue", FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
      var close= handle(linker, lib, "RegCloseKey", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      var out= arena.allocate(ValueLayout.ADDRESS);
      var path= arena.allocateFrom(subKey, StandardCharsets.UTF_16LE);
      var hkcuRoot= MemorySegment.ofAddress(0x80000001L);
      if ((int)call(open, hkcuRoot, path, 0, keyNotify, out) != 0){ throw Bug.of("Cannot watch "+subKey); }
      var key= out.get(ValueLayout.ADDRESS, 0);
      try { call(notify, key, 1, notifyNameOrValue, MemorySegment.NULL, 0); }
      finally { call(close, key); }
    }
  }
  private static final int keyNotify= 0x0010;
  private static final int notifyNameOrValue= 0x0001 | 0x0004;
  private static MethodHandle handle(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd){
    return linker.downcallHandle(lib.find(name).orElseThrow(), fd);
  }
  private static Object call(MethodHandle h, Object... args){
    try { return h.invokeWithArguments(args); }
    catch(Throwable t){ throw Bug.of(t); }
  }
}
///What the registry held before we wrote, so a failure leaves the machine as it was.
final class WinUndo{
  private final String name;
  private final LinkedHashMap<String,Path> saved= new LinkedHashMap<>();
  private final Optional<String> listed;
  WinUndo(String name, List<String> exts){
    this.name= name;
    var classes= WindowsFileAssociations.hkcu(WindowsFileAssociations.classes);
    listed= WindowsFileAssociations.regValue(WindowsFileAssociations.hkcu(WindowsFileAssociations.registeredApplications), name);
    save("HKCU\\Software\\"+name);
    exts.forEach(ext->save(classes+WindowsFileAssociations.progId(name, ext)));
    exts.forEach(ext->save(classes+ext));
  }
  private void save(String key){
    var file= Fs.of(()->Files.createTempFile("before",".reg"));
    file.toFile().deleteOnExit();
    var ran= Shell.exec(List.of("reg","export",key,file.toString(),"/y"));
    saved.put(key, ran.filter(r->r.code() == 0).isPresent() ? file : null);
  }
  void restore(Function<String,RuntimeException> halfDone){
    for (var e: saved.entrySet()){
      try { put(e.getKey(), e.getValue()); }
      catch(RuntimeException t){ throw halfDone.apply(e.getKey()+"\n"+t); }
    }
    var apps= WindowsFileAssociations.hkcu(WindowsFileAssociations.registeredApplications);
    if (listed.isEmpty()){ Shell.exec(List.of("reg","delete",apps,"/v",name,"/f")); return; }
    Shell.exec(List.of("reg","add",apps,"/v",name,"/d",listed.get(),"/f"));
  }
  private static void put(String key, Path was){
    Shell.exec(List.of("reg","delete",key,"/f"));
    if (was == null){ return; }
    Shell.exec(List.of("reg","import",was.toString()));
  }
}
