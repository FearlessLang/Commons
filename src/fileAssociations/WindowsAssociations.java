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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.Fs;
import utils.Bug;
import utils.Join;

public final class WindowsAssociations{
  private static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  private static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  private static final String softwareRoot= "HKEY_CURRENT_USER\\Software";
  private static final String fileExts= "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";
  private static final String valueNotSet= "(value not set)";
  private static final int assocChanged= 0x08000000;
  private static final Pattern opened= Pattern.compile("\"([^\"]*)\" \"%1\"");
  static void reconcile(String identity, String name, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Consumer<Path> replaced,
      Function<String,RuntimeException> ambiguous,
      Function<Map<String,List<String>>,RuntimeException> chosenByHand,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(Join.of(existing, "", "\n", "")); }
    var wanted= extensions.stream().map(Icon::extension).toList();
    var declared= existing.stream().flatMap(s -> regValues(hkcu(fileAssociations(s))).keySet().stream()).toList();
    var chosen= new LinkedHashMap<String,List<String>>();
    userChoices().forEach((e,ps) -> chosen.put(e, ps.stream().filter(p -> !Icon.system(e) || declared.contains(e) || belongsToFamily.test(owner(p, e))).toList()));
    chosen.values().removeIf(List::isEmpty);
    if (!chosen.isEmpty()){ throw chosenByHand.apply(Collections.unmodifiableMap(chosen)); }
    var foreign= new LinkedHashMap<String,List<String>>();
    wanted.stream().filter(e -> !Icon.system(e)).forEach(e -> foreign.put(e, held(e, belongsToFamily)));
    foreign.values().removeIf(List::isEmpty);
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    if (alreadyMatches(existing, identity, name, command, extensions)){ if (!extensions.isEmpty()){ notifyShellOfChange(); } return; }
    forget(wanted, notWritable);
    existing.forEach(s -> commandOf(s).filter(c -> !c.equals(command)).ifPresent(replaced));
    existing.forEach(s -> eradicate(s, belongsToFamily));
    if (!extensions.isEmpty()){
      extensions.forEach(icon -> forgetOpenWithHistory(icon.extension(), belongsToFamily));
      var previous= new LinkedHashMap<String,String>();
      extensions.forEach(icon -> regValue(hkcu(classes)+icon.extension(), "").ifPresent(p -> previous.put(icon.extension(), p)));
      importReg(regFile(identity, name, command, extensions, previous), halfDone);
    }
    notifyShellOfChange();
  }
  static void eradicateAll(Predicate<String> belongsToFamily){
    var existing= existingIdentities(belongsToFamily);
    if (existing.isEmpty()){ return; }
    existing.forEach(name -> eradicate(name, belongsToFamily));
    notifyShellOfChange();
  }
  static List<String> existingIdentities(Predicate<String> belongsToFamily){
    var declared= listSubkeys(softwareRoot).stream().filter(belongsToFamily).filter(n -> keyExists(hkcu(capabilities(n))));
    return Stream.concat(regValues(hkcu(registeredApplications)).keySet().stream(), declared).filter(belongsToFamily).distinct().sorted().toList();
  }
  static void forgetChoices(Map<String,List<String>> choices, Function<String,RuntimeException> notWritable){
    forget(choices.keySet(), notWritable);
    notifyShellOfChange();
  }
  private static void forget(Collection<String> extensions, Function<String,RuntimeException> notWritable){
    extensions.stream().flatMap(e -> Stream.of(userChoiceKey(e), hkcu(fileExts+e+"\\UserChoiceLatest"))).filter(WindowsAssociations::keyExists)
      .forEach(k -> Shell.req(List.of("reg", "delete", k, "/f"), notWritable));
  }
  public static String userChoiceKey(String ext){ return hkcu(fileExts+ext+"\\UserChoice"); }
  private static Map<String,List<String>> userChoices(){
    var res= new LinkedHashMap<String,List<String>>();
    var key= "";
    var listed= Shell.exec(List.of("reg", "query", hkcu(fileExts), "/s", "/v", "ProgId")).filter(ran -> ran.code() == 0).map(Shell.Ran::out).orElse("");
    for (var line : listed.lines().map(String::strip).toList()){
      if (line.startsWith("HKEY_")){ key = line; continue; }
      var choice= key.matches(Pattern.quote(fileExts)+"[^\\\\]+\\\\(UserChoice|UserChoiceLatest\\\\ProgId)");
      if (!choice || !line.contains("REG_")){ continue; }
      res.merge(key.substring(fileExts.length()).split("\\\\")[0].toLowerCase(Locale.ROOT), List.of(regQueried(line)), (a,b) -> Stream.concat(a.stream(), b.stream()).distinct().toList());
    }
    return Collections.unmodifiableMap(res);
  }
  private static List<String> held(String ext, Predicate<String> belongsToFamily){
    var progIds= Stream.concat(regValue(hkcu(classes)+ext, "").stream(), regValues(hkcu(classes)+ext+"\\OpenWithProgids").keySet().stream());
    return progIds.distinct().filter(p -> !belongsToFamily.test(owner(p, ext))).toList();
  }
  private static String owner(String progId, String ext){
    var suffix= "."+ext.substring(1);
    return progId.endsWith(suffix) ? progId.substring(0, progId.length() - suffix.length()) : progId;
  }
  private static boolean alreadyMatches(List<String> existing, String identity, String name, Path command, List<Icon> extensions){
    if (extensions.isEmpty()){ return existing.isEmpty(); }
    if (!existing.equals(List.of(identity))){ return false; }
    var declared= regValues(hkcu(fileAssociations(identity)));
    if (declared.size() != extensions.size()){ return false; }
    for (var icon : extensions){
      var progId= progId(identity, icon.extension());
      if (!progId.equals(declared.get(icon.extension()))){ return false; }
      var sameIcon= regValue(hkcu(classes)+progId+"\\DefaultIcon", "").equals(Optional.of(icon.ico()+",0"));
      if (!sameIcon){ return false; }
      var sameCommand= regValue(hkcu(classes)+progId+"\\shell\\open\\command", "").equals(Optional.of(openCommand(command)));
      if (!sameCommand){ return false; }
    }
    var sameName= regValue(hkcu(capabilities(identity)), "ApplicationName").equals(Optional.of(name));
    return sameName && regValue(hkcu(capabilities(identity)), "ApplicationIcon").equals(Optional.of(command+",0"));
  }
  private static Optional<Path> commandOf(String identity){
    return regValues(hkcu(fileAssociations(identity))).values().stream().findFirst()
      .flatMap(progId -> regValue(hkcu(classes)+progId+"\\shell\\open\\command", ""))
      .map(opened::matcher).filter(Matcher::matches).map(m -> Path.of(m.group(1)));
  }
  private static void eradicate(String identity, Predicate<String> belongsToFamily){
    regValues(hkcu(fileAssociations(identity))).forEach((ext,progId) -> dropClaim(ext, progId, belongsToFamily));
    Shell.exec(List.of("reg", "delete", hkcu(softwareRoot)+"\\"+identity, "/f"));
    Shell.exec(List.of("reg", "delete", hkcu(registeredApplications), "/v", identity, "/f"));
  }
  private static void dropClaim(String ext, String progId, Predicate<String> belongsToFamily){
    Shell.exec(List.of("reg", "delete", hkcu(classes)+progId, "/f"));
    var extKey= hkcu(classes)+ext;
    Shell.exec(List.of("reg", "delete", extKey+"\\OpenWithProgids", "/v", progId, "/f"));
    if (regValues(extKey+"\\OpenWithProgids").isEmpty()){
      Shell.exec(List.of("reg", "delete", extKey+"\\OpenWithProgids", "/f"));
    }
    if (regValue(extKey, "").equals(Optional.of(progId))){
      var previous= regValue(hkcu(previousDefaults(owner(progId, ext))), ext);
      Shell.exec(previous.map(p -> List.of("reg", "add", extKey, "/ve", "/d", p, "/f")).orElse(List.of("reg", "delete", extKey, "/ve", "/f")));
    }
    if (listSubkeys(classes+ext).isEmpty() && regValues(extKey).isEmpty()){
      Shell.exec(List.of("reg", "delete", extKey, "/f"));
    }
    forgetOpenWithHistory(ext, belongsToFamily);
  }
  private static void forgetOpenWithHistory(String ext, Predicate<String> belongsToFamily){
    var listKey= hkcu(fileExts)+ext+"\\OpenWithList";
    regValues(listKey).forEach((name,exe) -> {
      if (!name.equals("MRUList") && belongsToFamily.test(exe)){
        Shell.exec(List.of("reg", "delete", listKey, "/v", name, "/f"));
      }
    });
    if (regValues(listKey).isEmpty()){ Shell.exec(List.of("reg", "delete", listKey, "/f")); }
    var progIdsKey= hkcu(fileExts)+ext+"\\OpenWithProgids";
    regValues(progIdsKey).keySet().forEach(progId -> {
      if (belongsToFamily.test(owner(progId, ext))){ Shell.exec(List.of("reg", "delete", progIdsKey, "/v", progId, "/f")); }
    });
    if (regValues(progIdsKey).isEmpty()){ Shell.exec(List.of("reg", "delete", progIdsKey, "/f")); }
  }
  /// previous: the default value of Classes\(extension) before identity takes the extension, kept so that dropping the extension puts it back.
  public static String regFile(String identity, String name, Path command, List<Icon> extensions, Map<String,String> previous){
    var res= new StringBuilder("Windows Registry Editor Version 5.00\r\n");
    res.append(regEntry(capabilities(identity), "ApplicationName", name));
    res.append(regEntry(capabilities(identity), "ApplicationIcon", command+",0"));
    extensions.forEach(icon -> res.append(oneKind(identity, command, icon)));
    previous.forEach((ext, p) -> res.append(regEntry(previousDefaults(identity), ext, p)));
    res.append(regEntry(registeredApplications, identity, "Software\\"+identity+"\\Capabilities"));
    return res.toString();
  }
  private static String oneKind(String identity, Path command, Icon icon){
    var ext= icon.extension();
    var progId= progId(identity, ext);
    return regEntry(classes+progId+"\\DefaultIcon", icon.ico()+",0")
      +regEntry(classes+progId+"\\shell\\open\\command", openCommand(command))
      +regEntry(classes+ext, progId)
      +regEntry(classes+ext+"\\OpenWithProgids", progId, "")
      +regEntry(fileAssociations(identity), ext, progId);
  }
  private static String openCommand(Path command){ return "\""+command+"\" \"%1\""; }
  private static String capabilities(String identity){ return softwareRoot+"\\"+identity+"\\Capabilities"; }
  public static String fileAssociations(String identity){ return capabilities(identity)+"\\FileAssociations"; }
  private static String previousDefaults(String identity){ return softwareRoot+"\\"+identity+"\\PreviousDefaults"; }
  public static String progId(String identity, String ext){ return identity+"."+ext.substring(1); }
  private static String regEntry(String key, String data){ return regEntry(key, "", data); }
  private static String regEntry(String key, String name, String data){
    var shown= name.isEmpty() ? "@" : "\""+regData(name)+"\"";
    return "\r\n["+key+"]\r\n"+shown+"=\""+regData(data)+"\"\r\n";
  }
  private static String regData(String data){ return data.replace("\\", "\\\\").replace("\"", "\\\""); }
  private static Optional<String> regValue(String key, String name){
    var cmd= name.isEmpty()
      ? List.of("reg", "query", key, "/ve")
      : List.of("reg", "query", key, "/v", name);
    var raw= Shell.exec(cmd).filter(ran -> ran.code() == 0)
      .flatMap(ran -> ran.out().lines().map(String::strip).filter(l -> l.contains("REG_")).findFirst())
      .map(WindowsAssociations::regQueried);
    if (raw.filter(v -> v.equals(valueNotSet)).isEmpty()){ return raw; }
    var del= name.isEmpty() ? List.of("reg", "delete", key, "/ve", "/f") : List.of("reg", "delete", key, "/v", name, "/f");
    Shell.exec(del);
    return Optional.empty();
  }
  private static Map<String,String> regValues(String key){
    var res= new LinkedHashMap<String,String>();
    Shell.exec(List.of("reg", "query", key)).filter(ran -> ran.code() == 0)
      .ifPresent(ran -> ran.out().lines().map(String::strip)
        .filter(l -> l.contains("REG_")).forEach(l -> res.put(regName(l), regQueried(l))));
    return Collections.unmodifiableMap(res);//keep insertion order, unlike Map.copyOf
  }
  private static List<String> listSubkeys(String key){
    return Shell.exec(List.of("reg", "query", key)).filter(ran -> ran.code() == 0)
      .map(ran -> ran.out().lines().map(String::strip)
        .filter(l -> l.startsWith(key+"\\"))
        .map(l -> l.substring(key.length() + 1))
        .toList())
      .orElse(List.of());
  }
  private static boolean keyExists(String key){
    return Shell.exec(List.of("reg", "query", key)).filter(ran -> ran.code() == 0).isPresent();
  }
  public static String regName(String line){ return line.substring(0, line.indexOf("REG_")).strip(); }
  private static String regQueried(String line){
    var end= line.indexOf(' ', line.indexOf("REG_"));
    return end<0 ? "" : line.substring(end).strip();
  }
  private static String hkcu(String key){ return "HKCU"+key.substring("HKEY_CURRENT_USER".length()); }
  private static void importReg(String content, Function<String,RuntimeException> halfDone){
    var file= Fs.of(() -> Files.createTempFile("association", ".reg"));
    Fs.ofV(() -> Files.write(file, ("\uFEFF"+content).getBytes(StandardCharsets.UTF_16LE)));
    try{ Shell.req(List.of("reg", "import", file.toString()), halfDone); }
    finally{ Fs.ofV(() -> Fs.delete(file)); }
  }
  @SuppressWarnings("restricted")
  private static void notifyShellOfChange(){
    try (var arena= Arena.ofConfined()){
      var notify= Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("shell32.dll", arena).find("SHChangeNotify").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      notify.invokeWithArguments(assocChanged, 0, MemorySegment.NULL, MemorySegment.NULL);
    }
    catch (Throwable e){ throw Bug.of(e); }
  }
}
