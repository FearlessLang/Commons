package fileAssociations;

import static fileAssociations.FileAssociations.same;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.Fs;
import utils.Bug;
import utils.Join;
import utils.Range;
import utils.Streams;

public final class MacAssociations{
  public record Declared(String type, Path bundle){}
  public static Path home= Path.of(System.getProperty("user.home"), "Library", "Application Support", "fearlessAssociations");
  public static Function<String,List<Declared>> declared= MacAssociations::declaredOnDesktop;
  public static ToIntFunction<Path> register= MacAssociations::registerOnDesktop;
  public static Function<String,Optional<Path>> holder= MacAssociations::holderOnDesktop;
  public static String preferences= "com.apple.LaunchServices/com.apple.launchservices.secure";
  public static List<String> reload= List.of("killall", "lsd");
  private static final Pattern launcher= Pattern.compile("#!/bin/sh\nexec '(.*)' FearlessCommandOpened\n", Pattern.DOTALL);
  private static final int utf8= 0x08000100;
  static void reconcile(String identity, String name, Path command,
      List<Icon> extensions, List<String> existing, List<String> takenOver, Path programPng, Consumer<Path> replaced,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var others= new LinkedHashMap<String,Declared>();
    for (var i : extensions){ declared.apply(i.extension().substring(1)).stream().filter(d -> !d.bundle().startsWith(home)).findFirst().ifPresent(d -> others.put(i.extension(), d)); }
    var unwritable= existing.stream().flatMap(s -> Fs.walk(bundle(s), f -> f.filter(Files::isDirectory).toList()).stream()).filter(d -> !Files.isWritable(d));
    var targets= extensions.isEmpty() && existing.isEmpty() ? Stream.<Path>of() : Stream.of(home).filter(d -> !LinuxAssociations.writableForCreation(d));
    var choices= takenOver.isEmpty() ? Stream.<Path>of() : Stream.of(Path.of(System.getProperty("user.home"), "Library", "Preferences", preferences+".plist")).filter(f -> !LinuxAssociations.writableForCreation(f));
    var refused= Streams.of(unwritable, targets, choices).map(Path::toString).toList();
    if (!refused.isEmpty()){ throw notWritable.apply(Join.of(refused, "", "\n", "")); }
    if (!takenOver.isEmpty()){ choose(takenOver.stream().map(e -> typeOf(identity, others, e)).toList(), identity.replace('_', '-'), notWritable); }
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, name, command, extensions, programPng, others);
    if (!same(existing, identity, files(bundle(identity)), wanted)){
      existing.forEach(s -> commandOf(s).filter(c -> !c.equals(command)).ifPresent(replaced));
      existing.stream().filter(s -> wanted.isEmpty() || !s.equals(identity)).forEach(s -> remove(s, halfDone));
      if (wanted.isEmpty()){ return; }
      Fs.cleanDir(bundle(identity));
      wanted.keySet().forEach(f -> Fs.ensureDir(f.getParent()));
      wanted.forEach((f,bytes) -> Fs.ofV(() -> Files.write(f, bytes)));
      var executable= bundle(identity).resolve("Contents").resolve("MacOS").resolve(identity).toFile().setExecutable(true);
      assert executable;
    }
    if (wanted.isEmpty()){ return; }
    refresh(identity, halfDone);
  }
  static Map<String,String> holders(List<String> extensions){
    var res= new LinkedHashMap<String,String>();
    extensions.forEach(e -> holder.apply(e).ifPresent(h -> res.put(e, FileAssociations.baseName(h))));
    return res;
  }
  private static void choose(List<String> types, String bundleIdentifier, Function<String,RuntimeException> notWritable){
    boolean read= call("CFPreferencesAppSynchronize", ValueLayout.JAVA_BOOLEAN, cfString(preferences));
    assert read;
    var old= ptr("CFPreferencesCopyAppValue", cfString("LSHandlers"), cfString(preferences));
    var all= old.equals(MemorySegment.NULL) ? ptr("CFArrayCreateMutable", MemorySegment.NULL, 0L, symbol("kCFTypeArrayCallBacks")) : ptr("CFArrayCreateMutableCopy", MemorySegment.NULL, 0L, old);
    for (var i= count(all) - 1; i >= 0; i -= 1){ if (type(all, i).filter(t -> types.stream().anyMatch(t::equalsIgnoreCase)).isPresent()){ call("CFArrayRemoveValueAtIndex", null, all, i); } }
    for (var t : types){
      var choice= ptr("CFDictionaryCreateMutable", MemorySegment.NULL, 0L, symbol("kCFTypeDictionaryKeyCallBacks"), symbol("kCFTypeDictionaryValueCallBacks"));
      call("CFDictionarySetValue", null, choice, cfString("LSHandlerContentType"), cfString(t));
      call("CFDictionarySetValue", null, choice, cfString("LSHandlerRoleAll"), cfString(bundleIdentifier));
      call("CFArrayAppendValue", null, all, choice);
    }
    call("CFPreferencesSetAppValue", null, cfString("LSHandlers"), all, cfString(preferences));
    boolean written= call("CFPreferencesAppSynchronize", ValueLayout.JAVA_BOOLEAN, cfString(preferences));
    if (!written){ throw notWritable.apply(preferences); }
    LinuxAssociations.req(reload, notWritable);
  }
  private static long count(MemorySegment array){ return call("CFArrayGetCount", ValueLayout.JAVA_LONG, array); }
  private static Optional<String> type(MemorySegment array, long i){
    var type= ptr("CFDictionaryGetValue", ptr("CFArrayGetValueAtIndex", array, i), cfString("LSHandlerContentType"));
    return type.equals(MemorySegment.NULL) ? Optional.empty() : Optional.of(text(type));
  }
  private static String text(MemorySegment cf){
    var buffer= Arena.global().allocate(4096);
    boolean converted= call("CFStringGetCString", ValueLayout.JAVA_BOOLEAN, cf, buffer, 4096L, utf8);
    assert converted;
    return buffer.getString(0);
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    existingIdentities(belongsToFamily).forEach(s -> remove(s, halfDone));
  }
  static List<String> existingIdentities(Predicate<String> belongsToFamily){
    if (!Files.isDirectory(home)){ return List.of(); }
    return Fs.of(() -> { try (var s= Files.list(home)){ return s.filter(p -> p.toString().endsWith(".app")).map(FileAssociations::baseName).filter(belongsToFamily).sorted().toList(); } });
  }
  public static String typeOf(String identity, String ext){ return (identity+ext).replace('_', '-').toLowerCase(Locale.ROOT); }
  private static String typeOf(String identity, Map<String,Declared> others, String ext){ return Optional.ofNullable(others.get(ext)).map(Declared::type).orElse(typeOf(identity, ext)); }
  private static Path bundle(String identity){ return home.resolve(identity+".app"); }
  private static Path plist(String identity){ return bundle(identity).resolve("Contents").resolve("Info.plist"); }
  private static void refresh(String identity, Function<String,RuntimeException> halfDone){
    var code= register.applyAsInt(bundle(identity));
    if (code != 0){ throw halfDone.apply("LSRegisterURL "+bundle(identity)+"\n"+code); }
  }
  private static void remove(String identity, Function<String,RuntimeException> halfDone){
    Fs.cleanDir(bundle(identity));
    Fs.writeUtf8(plist(identity), plist(identity, "", "", ""));
    refresh(identity, halfDone);
    Fs.rmTree(bundle(identity));
  }
  private static Optional<Path> commandOf(String identity){
    var script= bundle(identity).resolve("Contents").resolve("MacOS").resolve(identity);
    var m= launcher.matcher(Files.isRegularFile(script) ? new String(Fs.of(() -> Files.readAllBytes(script)), UTF_8) : "");
    return m.matches() ? Optional.of(Path.of(m.group(1).replace("'\\''", "'"))) : Optional.empty();
  }
  private static List<Path> files(Path dir){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.walk(dir, s -> s.filter(Files::isRegularFile).toList());
  }
  private static Map<Path,byte[]> wanted(String identity, String name, Path command, List<Icon> icons, Path programPng, Map<String,Declared> others){
    var res= new LinkedHashMap<Path,byte[]>();
    var contents= bundle(identity).resolve("Contents");
    var documents= new StringBuilder();
    var exported= new StringBuilder();
    for (var i : icons){
      var ext= i.extension().substring(1);
      var other= Optional.ofNullable(others.get(i.extension()));
      res.put(contents.resolve("Resources").resolve(ext+".icns"), icns(i.png()));
      documents.append(document(ext, typeOf(identity, others, i.extension()), other.isPresent() ? "Alternate" : "Owner"));
      if (other.isEmpty()){ exported.append(exported(identity, ext)); }
    }
    res.put(contents.resolve("Resources").resolve("_program.icns"), icns(programPng));
    res.put(contents.resolve("Resources").resolve("en.lproj").resolve("InfoPlist.strings"), ("CFBundleDisplayName = \""+name+"\";\n").getBytes(UTF_8));
    res.put(contents.resolve("Info.plist"), plist(identity, name, documents.toString(), exported.toString()).getBytes(UTF_8));
    res.put(contents.resolve("MacOS").resolve(identity), ("#!/bin/sh\nexec '"+command.toString().replace("'", "'\\''")+"' FearlessCommandOpened\n").getBytes(UTF_8));
    return Collections.unmodifiableMap(res);
  }
  private static byte[] icns(Path png){
    var image= Ico.png(png);
    return ByteBuffer.allocate(16 + image.length).put("icns".getBytes(UTF_8)).putInt(16 + image.length).put("ic08".getBytes(UTF_8)).putInt(8 + image.length).put(image).array();
  }
  private static String string(String value){ return "<string>"+value+"</string>"; }
  private static String document(String ext, String type, String rank){
    return "<dict><key>CFBundleTypeRole</key>"+string("Viewer")+"<key>CFBundleTypeIconFile</key>"+string(ext+".icns")
      +"<key>LSHandlerRank</key>"+string(rank)+"<key>LSItemContentTypes</key><array>"+string(type)+"</array></dict>\n";
  }
  private static String exported(String identity, String ext){
    return "<dict><key>UTTypeIdentifier</key>"+string(typeOf(identity, "."+ext))+"<key>UTTypeIconFile</key>"+string(ext+".icns")
      +"<key>UTTypeConformsTo</key><array>"+string("public.data")+"</array>"
      +"<key>UTTypeTagSpecification</key><dict><key>public.filename-extension</key><array>"+string(ext)+"</array></dict></dict>\n";
  }
  public static String plist(String identity, String name, String documents, String exported){
    return """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>%s</string>
<key>CFBundleIdentifier</key><string>%s</string>
<key>CFBundleName</key><string>%s</string>
<key>CFBundleDisplayName</key><string>%s</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleIconFile</key><string>_program.icns</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleDocumentTypes</key><array>
%s</array>
<key>UTExportedTypeDeclarations</key><array>
%s</array>
</dict></plist>
""".formatted(identity, identity.replace('_', '-'), name, identity, documents, exported);
  }
  private static final class Desktop{
    @SuppressWarnings("restricted")
    static final SymbolLookup lib= SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", Arena.global());
  }
  private static MemoryLayout layout(Object arg){
    return switch (arg){
      case MemorySegment _ -> ValueLayout.ADDRESS;
      case Long _ -> ValueLayout.JAVA_LONG;
      case Integer _ -> ValueLayout.JAVA_INT;
      case Boolean _ -> ValueLayout.JAVA_BOOLEAN;
      default -> throw Bug.unreachable();
    };
  }
  @SuppressWarnings({"unchecked", "restricted"})
  private static <T> T call(String name, MemoryLayout res, Object... args){
    var layouts= Stream.of(args).map(MacAssociations::layout).toArray(MemoryLayout[]::new);
    var type= res == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(res, layouts);
    try{ return (T)Linker.nativeLinker().downcallHandle(Desktop.lib.find(name).orElseThrow(), type).invokeWithArguments(args); }
    catch (Throwable e){ throw Bug.of(e); }
  }
  private static MemorySegment ptr(String name, Object... args){ return call(name, ValueLayout.ADDRESS, args); }
  private static MemorySegment symbol(String name){ return Desktop.lib.find(name).orElseThrow(); }
  private static MemorySegment cfString(String s){ return ptr("CFStringCreateWithCString", MemorySegment.NULL, Arena.global().allocateFrom(s), utf8); }
  private static MemorySegment cfUrl(Path p){
    var bytes= p.toString().getBytes(UTF_8);
    return ptr("CFURLCreateFromFileSystemRepresentation", MemorySegment.NULL, Arena.global().allocateFrom(ValueLayout.JAVA_BYTE, bytes), (long)bytes.length, true);
  }
  private static List<Declared> declaredOnDesktop(String ext){
    var types= ptr("UTTypeCreateAllIdentifiersForTag", cfString("public.filename-extension"), cfString(ext), MemorySegment.NULL);
    var res= new ArrayList<Declared>();
    var buffer= Arena.global().allocate(4096);
    for (int i : Range.of(0, (int)count(types))){
      var type= ptr("CFArrayGetValueAtIndex", types, (long)i);
      var url= ptr("UTTypeCopyDeclaringBundleURL", type);
      if (url.equals(MemorySegment.NULL)){ continue; }
      var name= text(type);
      boolean located= call("CFURLGetFileSystemRepresentation", ValueLayout.JAVA_BOOLEAN, url, true, buffer, 4096L);
      assert located;
      res.add(new Declared(name, Path.of(buffer.getString(0))));
    }
    return List.copyOf(res);
  }
  private static int registerOnDesktop(Path bundle){ return call("LSRegisterURL", ValueLayout.JAVA_INT, cfUrl(bundle), true); }
  private static Optional<Path> holderOnDesktop(String ext){
    var type= ptr("UTTypeCreatePreferredIdentifierForTag", cfString("public.filename-extension"), cfString(ext.substring(1)), MemorySegment.NULL);
    var url= ptr("LSCopyDefaultApplicationURLForContentType", type, -1, MemorySegment.NULL);
    if (url.equals(MemorySegment.NULL)){ return Optional.empty(); }
    var buffer= Arena.global().allocate(4096);
    boolean located= call("CFURLGetFileSystemRepresentation", ValueLayout.JAVA_BOOLEAN, url, true, buffer, 4096L);
    assert located;
    return Optional.of(Path.of(buffer.getString(0)));
  }
}
