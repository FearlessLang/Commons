package fileAssociations;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.Fs;
import utils.Bug;

public final class WindowsAssociations{
  private WindowsAssociations(){}
  private static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  private static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  private static final String softwareRoot= "HKEY_CURRENT_USER\\Software";
  private static final String fileExts= "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";

  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programIco,
      Function<String,RuntimeException> ambiguous,
      Function<String,RuntimeException> userLocked,
      Function<String,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(String.join("\n", existing)); }

    var locked= extensions.stream().map(Icon::extension).filter(e->userChoice(e).isPresent()).toList();
    if (!locked.isEmpty()){ throw userLocked.apply(String.join("\n", locked)); }

    var foreign= new ArrayList<String>();
    for (var icon: extensions){
      for (var progId: claimants(icon.extension())){
        if (!belongsToFamily.test(owner(progId, icon.extension()))){ foreign.add(icon.extension()+" -> "+progId); }
      }
    }
    if (!foreign.isEmpty()){ throw notOurs.apply(String.join("\n", foreign)); }

    var stale= existing.isEmpty() ? Optional.<String>empty() : Optional.of(existing.getFirst());
    if (alreadyMatches(stale, identity, command, extensions, programIco)){ return; }

    stale.ifPresent(WindowsAssociations::eradicate);
    if (!extensions.isEmpty()){ create(identity, command, extensions, programIco, halfDone); }
    notifyShellOfChange();
  }

  private static List<String> existingIdentities(Predicate<String> belongsToFamily){
    var res= new LinkedHashSet<String>();
    res.addAll(regValues(hkcu(registeredApplications)).keySet());
    for (var name: listSubkeys(hkcu(softwareRoot))){
      if (keyExists(hkcu(softwareRoot)+"\\"+name+"\\Capabilities")){ res.add(name); }
    }
    return res.stream().filter(belongsToFamily).sorted().toList();
  }
  public static String userChoiceKey(String ext){ return hkcu(fileExts+ext+"\\UserChoice"); }
  private static Optional<String> userChoice(String ext){ return regValue(userChoiceKey(ext), "ProgId"); }
  private static List<String> claimants(String ext){
    var res= new ArrayList<String>();
    regValue(hkcu(classes)+ext, "").ifPresent(res::add);
    res.addAll(regValues(hkcu(classes)+ext+"\\OpenWithProgids").keySet());
    return res;
  }
  private static String owner(String progId, String ext){
    var suffix= "."+ext.substring(1);
    return progId.endsWith(suffix) ? progId.substring(0, progId.length()-suffix.length()) : progId;
  }
  private static boolean alreadyMatches(Optional<String> stale, String identity, Path command, List<Icon> extensions, Path programIco){
    if (extensions.isEmpty()){ return stale.isEmpty(); }
    if (stale.isEmpty() || !stale.get().equals(identity)){ return false; }
    var declared= regValues(hkcu(fileAssociations(identity)));
    if (declared.size() != extensions.size()){ return false; }
    for (var icon: extensions){
      var progId= progId(identity, icon.extension());
      if (!progId.equals(declared.get(icon.extension()))){ return false; }
      if (!regValue(hkcu(classes)+progId+"\\DefaultIcon", "").equals(Optional.of(icon.ico()+",0"))){ return false; }
      if (!regValue(hkcu(classes)+progId+"\\shell\\open\\command", "").equals(Optional.of(openCommand(command)))){ return false; }
    }
    return regValue(hkcu(capabilities(identity)), "ApplicationIcon").equals(Optional.of(programIco+",0"));
  }
  private static void eradicate(String identityName){
    var declared= regValues(hkcu(fileAssociations(identityName)));
    declared.forEach((ext,progId)->{
      Shell.exec(List.of("reg","delete",hkcu(classes)+progId,"/f"));
      Shell.exec(List.of("reg","delete",hkcu(classes)+ext+"\\OpenWithProgids","/v",progId,"/f"));
      if (regValue(hkcu(classes)+ext, "").equals(Optional.of(progId))){
        Shell.exec(List.of("reg","delete",hkcu(classes)+ext,"/ve","/f"));
      }
    });
    Shell.exec(List.of("reg","delete",hkcu(softwareRoot)+"\\"+identityName,"/f"));
    Shell.exec(List.of("reg","delete",hkcu(registeredApplications),"/v",identityName,"/f"));
  }
  private static void create(String identity, Path command, List<Icon> extensions, Path programIco,
      Function<String,RuntimeException> halfDone){
    Shell.req(importReg(regFile(identity, command, extensions, programIco)), halfDone);
  }
  public static String regFile(String identity, Path command, List<Icon> extensions, Path programIco){
    var res= new StringBuilder("Windows Registry Editor Version 5.00\r\n");
    res.append(regEntry(capabilities(identity), "ApplicationName", identity));
    res.append(regEntry(capabilities(identity), "ApplicationIcon", programIco+",0"));
    extensions.forEach(icon->res.append(oneKind(identity, command, icon)));
    res.append(regEntry(registeredApplications, identity, "Software\\"+identity+"\\Capabilities"));
    return res.toString();
  }
  private static String oneKind(String identity, Path command, Icon icon){
    var ext= icon.extension();
    var progId= progId(identity, ext);
    return regEntry(classes+progId, identity)
      +regEntry(classes+progId+"\\DefaultIcon", icon.ico()+",0")
      +regEntry(classes+progId+"\\shell\\open\\command", openCommand(command))
      +regEntry(classes+ext, progId)
      +regEntry(classes+ext+"\\OpenWithProgids", progId, "")
      +regEntry(fileAssociations(identity), ext, progId);
  }
  private static String openCommand(Path command){ return "\""+command+"\" \"%1\""; }
  private static String capabilities(String identity){ return "HKEY_CURRENT_USER\\Software\\"+identity+"\\Capabilities"; }
  public static String fileAssociations(String identity){ return capabilities(identity)+"\\FileAssociations"; }
  public static String progId(String identity, String ext){ return identity+"."+ext.substring(1); }
  private static String regEntry(String key, String data){ return regEntry(key,"",data); }
  private static String regEntry(String key, String name, String data){
    var shown= name.isEmpty() ? "@" : "\""+regData(name)+"\"";
    return "\r\n["+key+"]\r\n"+shown+"=\""+regData(data)+"\"\r\n";
  }
  private static String regData(String data){ return data.replace("\\","\\\\").replace("\"","\\\""); }
  private static Optional<String> regValue(String key, String name){
    var cmd= name.isEmpty()
      ? List.of("reg","query",key,"/ve")
      : List.of("reg","query",key,"/v",name);
    return Shell.exec(cmd).filter(ran->ran.code() == 0)
      .flatMap(ran->ran.out().lines().map(String::strip).filter(l->l.contains("REG_")).findFirst())
      .map(WindowsAssociations::regQueried);
  }
  private static Map<String,String> regValues(String key){
    var res= new LinkedHashMap<String,String>();
    Shell.exec(List.of("reg","query",key)).filter(ran->ran.code() == 0)
      .ifPresent(ran->ran.out().lines().map(String::strip)
        .filter(l->l.contains("REG_")).forEach(l->res.put(regName(l), regQueried(l))));
    return res;
  }
  private static List<String> listSubkeys(String key){
    return Shell.exec(List.of("reg","query",key)).filter(ran->ran.code() == 0)
      .map(ran->ran.out().lines().map(String::strip)
        .filter(l->l.startsWith(key+"\\"))
        .map(l->l.substring(key.length()+1))
        .toList())
      .orElse(List.of());
  }
  private static boolean keyExists(String key){
    return Shell.exec(List.of("reg","query",key)).filter(ran->ran.code() == 0).isPresent();
  }
  public static String regName(String line){ return line.substring(0, line.indexOf("REG_")).strip(); }
  private static String regQueried(String line){
    var end= line.indexOf(' ', line.indexOf("REG_"));
    return end < 0 ? "" : line.substring(end).strip();
  }
  private static String hkcu(String key){ return "HKCU"+key.substring("HKEY_CURRENT_USER".length()); }
  private static List<String> importReg(String content){
    var file= Fs.of(()->Files.createTempFile("association",".reg"));
    Fs.ofV(()->Files.write(file, ("\uFEFF"+content).getBytes(StandardCharsets.UTF_16LE)));
    file.toFile().deleteOnExit();
    return List.of("reg","import",file.toString());
  }
  private static void notifyShellOfChange(){
    try(var arena= Arena.ofConfined()){
      var linker= Linker.nativeLinker();
      var lib= SymbolLookup.libraryLookup("shell32.dll", arena);
      var notify= handle(linker, lib, "SHChangeNotify", FunctionDescriptor.ofVoid(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      call(notify, assocChanged, 0, MemorySegment.NULL, MemorySegment.NULL);
    }
  }
  private static final int assocChanged= 0x08000000;
  private static MethodHandle handle(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd){
    return linker.downcallHandle(lib.find(name).orElseThrow(), fd);
  }
  private static Object call(MethodHandle h, Object... args){
    try { return h.invokeWithArguments(args); }
    catch(Throwable t){ throw Bug.of(t); }
  }
}
