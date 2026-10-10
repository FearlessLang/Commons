package fileAssociations;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import tools.Fs;
import utils.Bug;

public final class WindowsAssociations{
  private static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  private static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  private static final String softwareRoot= "HKEY_CURRENT_USER\\Software";
  private static final String fileExts= "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";
  private static final String valueNotSet= "(value not set)";
  private static final int assocChanged= 0x08000000;

  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programIco,
      Function<String,RuntimeException> ambiguous,
      Function<List<String>,RuntimeException> userLocked,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(String.join("\n", existing)); }
    var locked= extensions.stream().map(Icon::extension)
      .filter(e->userChoice(e).isPresent() || userChoiceLatest(e).isPresent()).toList();
    if (!locked.isEmpty()){ throw userLocked.apply(locked); }
    var foreign= new LinkedHashMap<String,List<String>>();
    extensions.stream().map(Icon::extension).filter(e->!Icon.system(e)).forEach(e->foreign.put(e, held(e, belongsToFamily)));
    foreign.values().removeIf(List::isEmpty);
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    if (alreadyMatches(existing, identity, command, extensions, programIco)){ return; }
    existing.forEach(s->eradicate(s, belongsToFamily));
    if (!extensions.isEmpty()){
      extensions.forEach(icon->forgetOpenWithHistory(icon.extension(), belongsToFamily));
      Shell.req(importReg(regFile(identity, command, extensions, programIco)), halfDone);
    }
    notifyShellOfChange();
  }
  static void eradicateAll(Predicate<String> belongsToFamily){
    var existing= existingIdentities(belongsToFamily);
    if (existing.isEmpty()){ return; }
    existing.forEach(name->eradicate(name, belongsToFamily));
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
  private static Optional<String> userChoiceLatest(String ext){
    return regValue(hkcu(fileExts+ext+"\\UserChoiceLatest\\ProgId"), "ProgId");
  }
  private static List<String> held(String ext, Predicate<String> belongsToFamily){
    var progIds= Stream.concat(regValue(hkcu(classes)+ext, "").stream(), regValues(hkcu(classes)+ext+"\\OpenWithProgids").keySet().stream());
    return progIds.distinct().filter(p->!belongsToFamily.test(owner(p, ext))).toList();
  }
  private static String owner(String progId, String ext){
    var suffix= "."+ext.substring(1);
    return progId.endsWith(suffix) ? progId.substring(0, progId.length()-suffix.length()) : progId;
  }
  private static boolean alreadyMatches(List<String> existing, String identity, Path command, List<Icon> extensions, Path programIco){
    if (extensions.isEmpty()){ return existing.isEmpty(); }
    if (!existing.equals(List.of(identity))){ return false; }
    var declared= regValues(hkcu(fileAssociations(identity)));
    if (declared.size() != extensions.size()){ return false; }
    for (var icon: extensions){
      var progId= progId(identity, icon.extension());
      if (!progId.equals(declared.get(icon.extension()))){ return false; }
      var sameIcon= regValue(hkcu(classes)+progId+"\\DefaultIcon", "").equals(Optional.of(icon.ico()+",0"));
      if (!sameIcon){ return false; }
      var sameCommand= regValue(hkcu(classes)+progId+"\\shell\\open\\command", "").equals(Optional.of(openCommand(command)));
      if (!sameCommand){ return false; }
    }
    return regValue(hkcu(capabilities(identity)), "ApplicationIcon").equals(Optional.of(programIco+",0"));
  }
  private static void eradicate(String identity, Predicate<String> belongsToFamily){
    var declared= regValues(hkcu(fileAssociations(identity)));
    declared.forEach((ext,progId)->{
      Shell.exec(List.of("reg","delete",hkcu(classes)+progId,"/f"));
      dropClaim(ext, progId);
      forgetOpenWithHistory(ext, belongsToFamily);
    });
    Shell.exec(List.of("reg","delete",hkcu(softwareRoot)+"\\"+identity,"/f"));
    Shell.exec(List.of("reg","delete",hkcu(registeredApplications),"/v",identity,"/f"));
  }
  private static void dropClaim(String ext, String progId){
    var extKey= hkcu(classes)+ext;
    Shell.exec(List.of("reg","delete",extKey+"\\OpenWithProgids","/v",progId,"/f"));
    if (regValues(extKey+"\\OpenWithProgids").isEmpty()){
      Shell.exec(List.of("reg","delete",extKey+"\\OpenWithProgids","/f"));
    }
    if (regValue(extKey, "").equals(Optional.of(progId))){
      Shell.exec(List.of("reg","delete",extKey,"/ve","/f"));
    }
    if (listSubkeys(extKey).isEmpty() && regValues(extKey).isEmpty()){
      Shell.exec(List.of("reg","delete",extKey,"/f"));
    }
  }
  private static void forgetOpenWithHistory(String ext, Predicate<String> belongsToFamily){
    var listKey= hkcu(fileExts)+ext+"\\OpenWithList";
    regValues(listKey).forEach((name,exe)->{
      if (!name.equals("MRUList") && belongsToFamily.test(exe)){
        Shell.exec(List.of("reg","delete",listKey,"/v",name,"/f"));
      }
    });
    if (regValues(listKey).isEmpty()){ Shell.exec(List.of("reg","delete",listKey,"/f")); }

    var progIdsKey= hkcu(fileExts)+ext+"\\OpenWithProgids";
    regValues(progIdsKey).keySet().forEach(progId->{
      if (belongsToFamily.test(owner(progId, ext))){ Shell.exec(List.of("reg","delete",progIdsKey,"/v",progId,"/f")); }
    });
    if (regValues(progIdsKey).isEmpty()){ Shell.exec(List.of("reg","delete",progIdsKey,"/f")); }
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
    var raw= Shell.exec(cmd).filter(ran->ran.code() == 0)
      .flatMap(ran->ran.out().lines().map(String::strip).filter(l->l.contains("REG_")).findFirst())
      .map(WindowsAssociations::regQueried);
    if (!raw.filter(v->v.equals(valueNotSet)).isPresent()){ return raw; }
    var del= name.isEmpty() ? List.of("reg","delete",key,"/ve","/f") : List.of("reg","delete",key,"/v",name,"/f");
    Shell.exec(del);
    return Optional.empty();
  }
  private static Map<String,String> regValues(String key){
    var res= new LinkedHashMap<String,String>();
    Shell.exec(List.of("reg","query",key)).filter(ran->ran.code() == 0)
      .ifPresent(ran->ran.out().lines().map(String::strip)
        .filter(l->l.contains("REG_")).forEach(l->res.put(regName(l), regQueried(l))));
    return Collections.unmodifiableMap(res);//keep insertion order, unlike Map.copyOf
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
  @SuppressWarnings("restricted")
  private static void notifyShellOfChange(){
    try(var arena= Arena.ofConfined()){
      var notify= Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("shell32.dll", arena).find("SHChangeNotify").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      notify.invokeWithArguments(assocChanged, 0, MemorySegment.NULL, MemorySegment.NULL);
    }
    catch(Throwable t){ throw Bug.of(t); }
  }
}
