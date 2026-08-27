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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import utils.Bug;

public final class WindowsFileAssociations implements FileAssociations{
  WindowsFileAssociations(){}
  static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  public void associate(String name, List<String> exts, Path iconWin, Path iconLinux, String command,
      Function<String,RuntimeException> notOurs, Function<String,RuntimeException> stepFailed,
      Function<String,RuntimeException> notTaken, Function<String,RuntimeException> halfDone){
    var undo= new WinUndo(name, exts);
    try { Shell.req(importReg(regFile(name, exts, iconWin, command)), stepFailed); }
    catch(RuntimeException e){ undo.restore(halfDone); throw e; }
    for (var ext: exts){ settle(name, ext, notTaken, undo, halfDone); }
  }
  //The answer the desktop remembers is the user's own, given by hand, and only
  //another answer given by hand takes its place: no program writes it, and no
  //program removes it. The most we can do is open the window where it is
  //given, and wait there with them until they have answered.
  private void settle(String name, String ext, Function<String,RuntimeException> notTaken,
      WinUndo undo, Function<String,RuntimeException> halfDone){
    if (name.equals(effective(ext).orElse(""))){ return; }
    var before= givenByHand(ext);
    if (before.isPresent()){
      Shell.exec(whereToChoose(name));
      //Explorer writes other things under this key while the person browses, so the
      //answer they gave is the value changing, not the key changing.
      while (givenByHand(ext).equals(before)){ awaitChange(fileExts+ext); }
      if (name.equals(effective(ext).orElse(""))){ return; }
    }
    var was= effective(ext).orElse("nothing at all");
    undo.restore(halfDone);
    throw notTaken.apply(was);
  }
  public boolean taken(String name, List<String> exts){
    return exts.stream().allMatch(e->name.equals(effective(e).orElse("")));
  }
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
  public static String regFile(String name, List<String> exts, Path icon, String command){
    var app= "HKEY_CURRENT_USER\\Software\\"+name;
    var res= new StringBuilder("Windows Registry Editor Version 5.00\r\n");
    res.append(regEntry(classes+name, name));
    res.append(regEntry(classes+name+"\\DefaultIcon", icon+",0"));
    res.append(regEntry(classes+name+"\\shell\\open\\command", "\""+command+"\" \"%1\""));
    res.append(regEntry(app+"\\Capabilities", "ApplicationName", name));
    for (var ext: exts){
      res.append(regEntry(classes+ext, name));
      res.append(regEntry(classes+ext+"\\OpenWithProgids", name, ""));
      res.append(regEntry(app+"\\Capabilities\\FileAssociations", ext, name));
    }
    res.append(regEntry(registeredApplications, name, "Software\\"+name+"\\Capabilities"));
    return res.toString();
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
    save(classes+name);
    save("HKCU\\Software\\"+name);
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
