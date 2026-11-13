package fileAssociations;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import utils.Bug;

public final class WindowsAssociations{
  public record Entry(String key, String name, String data){}
  private static final String classes= "HKEY_CURRENT_USER\\Software\\Classes\\";
  private static final String registeredApplications= "HKEY_CURRENT_USER\\Software\\RegisteredApplications";
  private static final String softwareRoot= "HKEY_CURRENT_USER\\Software";
  private static final String fileExts= "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\";
  private static final int assocChanged= 0x08000000;
  private static final int notFound= 2;
  private static final int noMoreItems= 259;
  private static final int moreData= 234;
  private static final int regSz= 1;
  private static final int onlyRegSz= 2;
  private static final int keyRead= 0x20019;
  private static final int delete= 0x00010000;
  private static final int accessDenied= 5;
  private static final int nameMax= 16384;
  private static final int formatFromSystem= 0x12FF;
  private static final Pattern opened= Pattern.compile("\"([^\"]*)\" \"%1\"");
  public static Runnable notifyShell= WindowsAssociations::notifyShellOfChange;
  static void reconcile(String identity, String name, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, List<String> existing, List<String> takenOver, Consumer<Path> replaced,
      Function<Map<String,String>,RuntimeException> notTaken,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var locked= new LinkedHashMap<String,List<String>>();
    takenOver.forEach(e -> locked.put(e, choices(e).flatMap(k -> tree(k).stream()).filter(k -> !deletable(k)).toList()));
    FileAssociations.refuseUntaken(locked, notTaken);
    takenOver.stream().flatMap(WindowsAssociations::choices).forEach(k -> deleteKey(k, notWritable));
    var left= leftovers(existing, belongsToFamily);
    if (left.isEmpty() && alreadyMatches(existing, identity, name, command, extensions)){ if (!extensions.isEmpty()){ notifyShell.run(); } return; }
    existing.forEach(s -> commandOf(s).filter(c -> !c.equals(command)).ifPresent(replaced));
    existing.forEach(s -> eradicate(s, belongsToFamily, halfDone));
    left.forEach(p -> dropClaim("."+p.substring(p.lastIndexOf('.') + 1), p, belongsToFamily, halfDone));
    if (!extensions.isEmpty()){
      extensions.forEach(icon -> forgetOpenWithHistory(icon.extension(), belongsToFamily, halfDone));
      var previous= new LinkedHashMap<String,String>();
      extensions.forEach(icon -> value(classes+icon.extension(), "").ifPresent(p -> previous.put(icon.extension(), p)));
      setup(identity, name, command, extensions, previous).forEach(e -> set(e, halfDone));
    }
    notifyShell.run();
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    var left= leftovers(existing, belongsToFamily);
    if (existing.isEmpty() && left.isEmpty()){ return; }
    existing.forEach(name -> eradicate(name, belongsToFamily, halfDone));
    left.forEach(p -> dropClaim("."+p.substring(p.lastIndexOf('.') + 1), p, belongsToFamily, halfDone));
    notifyShell.run();
  }
  private static List<String> leftovers(List<String> existing, Predicate<String> belongsToFamily){
    var declared= existing.stream().flatMap(s -> values(fileAssociations(s)).values().stream()).toList();
    return subkeys(softwareRoot+"\\Classes").stream()
      .filter(p -> p.lastIndexOf('.') > 0 && belongsToFamily.test(p.substring(0, p.lastIndexOf('.'))) && !declared.contains(p)).toList();
  }
  private static Stream<String> choices(String ext){ return Stream.of(userChoiceKey(ext), fileExts+ext+"\\UserChoiceLatest"); }
  private static List<String> tree(String key){ return Stream.concat(Stream.of(key), subkeys(key).stream().flatMap(s -> tree(key+"\\"+s).stream())).toList(); }
  private static boolean deletable(String key){
    var result= Arena.ofAuto().allocate(ValueLayout.ADDRESS);
    var status= reg("RegOpenKeyExW", key, 0, delete, result);
    if (status == 0){ call("RegCloseKey", result.get(ValueLayout.ADDRESS, 0)); }
    return status != accessDenied;
  }
  static List<String> existingIdentities(Predicate<String> belongsToFamily){
    var declared= subkeys(softwareRoot).stream().filter(belongsToFamily).filter(n -> subkeys(softwareRoot+"\\"+n).stream().anyMatch("Capabilities"::equalsIgnoreCase));
    return Stream.concat(names(registeredApplications).stream(), declared).filter(belongsToFamily).distinct().sorted().toList();
  }
  public static String userChoiceKey(String ext){ return fileExts+ext+"\\UserChoice"; }
  static Map<String,String> holders(List<String> extensions){
    var res= new LinkedHashMap<String,String>();
    extensions.forEach(e -> value(fileExts+e+"\\UserChoiceLatest\\ProgId", "ProgId").or(() -> value(userChoiceKey(e), "ProgId")).or(() -> value("HKEY_CLASSES_ROOT\\"+e, ""))
      .ifPresent(p -> res.put(e, owner(p, e))));
    return res;
  }
  private static String owner(String progId, String ext){
    var suffix= "."+ext.substring(1);
    return progId.endsWith(suffix) ? progId.substring(0, progId.length() - suffix.length()) : progId;
  }
  private static boolean alreadyMatches(List<String> existing, String identity, String name, Path command, List<Icon> extensions){
    if (extensions.isEmpty()){ return existing.isEmpty(); }
    if (!existing.equals(List.of(identity))){ return false; }
    var declared= values(fileAssociations(identity));
    if (declared.size() != extensions.size()){ return false; }
    for (var icon : extensions){
      var progId= progId(identity, icon.extension());
      var same= progId.equals(declared.get(icon.extension()))
        && value(classes+icon.extension(), "").equals(Optional.of(progId))
        && value(classes+progId+"\\DefaultIcon", "").equals(Optional.of(icon.ico()+",0"))
        && value(classes+progId+"\\shell\\open\\command", "").equals(Optional.of(openCommand(command)));
      if (!same){ return false; }
    }
    var sameName= value(capabilities(identity), "ApplicationName").equals(Optional.of(name));
    return sameName && value(capabilities(identity), "ApplicationIcon").equals(Optional.of(command+",0"));
  }
  private static Optional<Path> commandOf(String identity){
    return values(fileAssociations(identity)).values().stream().findFirst()
      .flatMap(progId -> value(classes+progId+"\\shell\\open\\command", ""))
      .map(opened::matcher).filter(Matcher::matches).map(m -> Path.of(m.group(1)));
  }
  private static void eradicate(String identity, Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    values(fileAssociations(identity)).forEach((ext,progId) -> dropClaim(ext, progId, belongsToFamily, halfDone));
    deleteKey(softwareRoot+"\\"+identity, halfDone);
    deleteValue(registeredApplications, identity, halfDone);
  }
  private static void dropClaim(String ext, String progId, Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    deleteKey(classes+progId, halfDone);
    var extKey= classes+ext;
    deleteValue(extKey+"\\OpenWithProgids", progId, halfDone);
    if (names(extKey+"\\OpenWithProgids").isEmpty()){ deleteKey(extKey+"\\OpenWithProgids", halfDone); }
    if (value(extKey, "").equals(Optional.of(progId))){
      value(previousDefaults(owner(progId, ext)), ext).ifPresentOrElse(p -> set(new Entry(extKey, "", p), halfDone), () -> deleteValue(extKey, "", halfDone));
    }
    if (subkeys(extKey).isEmpty() && names(extKey).isEmpty()){ deleteKey(extKey, halfDone); }
    forgetOpenWithHistory(ext, belongsToFamily, halfDone);
  }
  private static void forgetOpenWithHistory(String ext, Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    if (subkeys(fileExts+ext).isEmpty()){ return; }
    var listKey= fileExts+ext+"\\OpenWithList";
    values(listKey).forEach((name,exe) -> {
      if (!name.equals("MRUList") && belongsToFamily.test(exe)){ deleteValue(listKey, name, halfDone); }
    });
    if (names(listKey).isEmpty()){ deleteKey(listKey, halfDone); }
    var progIdsKey= fileExts+ext+"\\OpenWithProgids";
    names(progIdsKey).stream().filter(p -> belongsToFamily.test(owner(p, ext))).forEach(p -> deleteValue(progIdsKey, p, halfDone));
    if (names(progIdsKey).isEmpty()){ deleteKey(progIdsKey, halfDone); }
  }
  /// previous: the default value of Classes\(extension) before identity takes the extension, kept so that dropping the extension puts it back.
  public static List<Entry> setup(String identity, String name, Path command, List<Icon> extensions, Map<String,String> previous){
    var res= new ArrayList<Entry>();
    res.add(new Entry(capabilities(identity), "ApplicationName", name));
    res.add(new Entry(capabilities(identity), "ApplicationIcon", command+",0"));
    extensions.forEach(icon -> res.addAll(oneKind(identity, command, icon)));
    previous.forEach((ext, p) -> res.add(new Entry(previousDefaults(identity), ext, p)));
    res.add(new Entry(registeredApplications, identity, "Software\\"+identity+"\\Capabilities"));
    return List.copyOf(res);
  }
  private static List<Entry> oneKind(String identity, Path command, Icon icon){
    var ext= icon.extension();
    var progId= progId(identity, ext);
    return List.of(new Entry(classes+progId+"\\DefaultIcon", "", icon.ico()+",0"),
      new Entry(classes+progId+"\\shell\\open\\command", "", openCommand(command)),
      new Entry(classes+ext, "", progId),
      new Entry(classes+ext+"\\OpenWithProgids", progId, ""),
      new Entry(fileAssociations(identity), ext, progId));
  }
  private static String openCommand(Path command){ return "\""+command+"\" \"%1\""; }
  private static String capabilities(String identity){ return softwareRoot+"\\"+identity+"\\Capabilities"; }
  public static String fileAssociations(String identity){ return capabilities(identity)+"\\FileAssociations"; }
  private static String previousDefaults(String identity){ return softwareRoot+"\\"+identity+"\\PreviousDefaults"; }
  public static String progId(String identity, String ext){ return identity+"."+ext.substring(1); }
  private static Optional<String> value(String key, String name){
    var size= Arena.ofAuto().allocate(ValueLayout.JAVA_INT);
    size.set(ValueLayout.JAVA_INT, 0, 1024);
    var data= Arena.ofAuto().allocate(1024);
    var status= reg("RegGetValueW", key, wide(name), onlyRegSz, MemorySegment.NULL, data, size);
    if (status == moreData){ data = Arena.ofAuto().allocate(size.get(ValueLayout.JAVA_INT, 0)); status = reg("RegGetValueW", key, wide(name), onlyRegSz, MemorySegment.NULL, data, size); }
    return status == 0 ? Optional.of(data.getString(0, UTF_16LE)) : Optional.empty();
  }
  private static Map<String,String> values(String key){
    var res= new LinkedHashMap<String,String>();
    names(key).stream().filter(n -> !n.isEmpty()).forEach(n -> value(key, n).ifPresent(v -> res.put(n, v)));
    return Collections.unmodifiableMap(res);//keep insertion order, unlike Map.copyOf
  }
  private static List<String> names(String key){ return listed("RegEnumValueW", key); }
  private static List<String> subkeys(String key){ return listed("RegEnumKeyExW", key); }
  private static List<String> listed(String enumerate, String key){
    var result= Arena.ofAuto().allocate(ValueLayout.ADDRESS);
    if (reg("RegOpenKeyExW", key, 0, keyRead, result) != 0){ return List.of(); }
    var handle= result.get(ValueLayout.ADDRESS, 0);
    var name= Arena.ofAuto().allocate(2L * nameMax);
    var size= Arena.ofAuto().allocate(ValueLayout.JAVA_INT);
    var res= new ArrayList<String>();
    var status= 0;
    for (int i= 0; status == 0; i += 1){
      size.set(ValueLayout.JAVA_INT, 0, nameMax);
      status= call(enumerate, handle, i, name, size, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
      if (status == 0){ res.add(name.getString(0, UTF_16LE)); }
    }
    assert status == noMoreItems;
    call("RegCloseKey", handle);
    return List.copyOf(res);
  }
  private static void set(Entry e, Function<String,RuntimeException> halfDone){
    var data= wide(e.data());
    written(reg("RegSetKeyValueW", e.key(), wide(e.name()), regSz, data, (int)data.byteSize()), "RegSetKeyValueW "+e.key()+" "+e.name(), halfDone);
  }
  private static void deleteKey(String key, Function<String,RuntimeException> failed){
    written(reg("RegDeleteTreeW", key), "RegDeleteTreeW "+key, failed);
  }
  private static void deleteValue(String key, String name, Function<String,RuntimeException> failed){
    written(reg("RegDeleteKeyValueW", key, wide(name)), "RegDeleteKeyValueW "+key+" "+name, failed);
  }
  private static void written(int status, String step, Function<String,RuntimeException> failed){
    if (status!=0 && status!=notFound){ throw failed.apply(step+"\n"+said(status)); }
  }
  private static String said(int status){
    var text= Arena.ofAuto().allocate(2L * nameMax);
    var length= call("FormatMessageW", formatFromSystem, MemorySegment.NULL, status, 0, text, nameMax, MemorySegment.NULL);
    assert length > 0;
    return text.getString(0, UTF_16LE).strip();
  }
  private static MemorySegment wide(String s){ return Arena.ofAuto().allocateFrom(s, UTF_16LE); }
  private static int reg(String function, String key, Object... rest){
    var root= MemorySegment.ofAddress(key.startsWith("HKEY_CLASSES_ROOT\\") ? 0x80000000 : 0x80000001);
    var sub= wide(key.substring(key.indexOf('\\') + 1));
    return call(function, Stream.concat(Stream.of(root, sub), Stream.of(rest)).toArray());
  }
  private static final class Desktop{
    @SuppressWarnings("restricted")
    static final SymbolLookup lib= Stream.of("advapi32.dll", "kernel32.dll", "shell32.dll").map(n -> SymbolLookup.libraryLookup(n, Arena.global())).reduce(SymbolLookup::or).orElseThrow();
  }
  private static final Map<String,MethodHandle> handles= new ConcurrentHashMap<>();
  @SuppressWarnings("restricted")
  private static MethodHandle handle(String function, Object... args){
    var layouts= Stream.of(args).map(a -> a instanceof Integer ? ValueLayout.JAVA_INT : ValueLayout.ADDRESS).toArray(MemoryLayout[]::new);
    return Linker.nativeLinker().downcallHandle(Desktop.lib.find(function).orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, layouts));
  }
  private static int call(String function, Object... args){
    try{ return (int)handles.computeIfAbsent(function, f -> handle(f, args)).invokeWithArguments(args); }
    catch (Throwable e){ throw Bug.of(e); }
  }
  @SuppressWarnings("restricted")
  private static void notifyShellOfChange(){
    var notify= FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    try{ Linker.nativeLinker().downcallHandle(Desktop.lib.find("SHChangeNotify").orElseThrow(), notify).invokeWithArguments(assocChanged, 0, MemorySegment.NULL, MemorySegment.NULL); }
    catch (Throwable e){ throw Bug.of(e); }
  }
}
