package fileAssociations;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import tools.Fs;
import utils.Bug;
import utils.Join;
import utils.Range;

public final class MacAssociations{
  public record Declared(String type, Path bundle){}
  public static Path home= Path.of(System.getProperty("user.home"), "Library", "Application Support", "fearlessAssociations");
  public static Function<String,List<Declared>> declared= MacAssociations::declaredOnDesktop;
  public static ToIntFunction<Path> register= MacAssociations::registerOnDesktop;
  public static BiConsumer<Path,String> offer= MacAssociations::offerOnDesktop;
  private static final Pattern launcher= Pattern.compile("#!/bin/sh\nexec '(.*)' FearlessCommandOpened\n", Pattern.DOTALL);
  private static final int utf8= 0x08000100;
  static void reconcile(String identity, String name, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programPng, Consumer<Path> replaced,
      Function<String,RuntimeException> ambiguous,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(Join.of(existing, "", "\n", "")); }
    var icons= extensions.stream().filter(i -> !i.extension().equals(".fearless")).toList();
    var others= new LinkedHashMap<String,Declared>();
    for (var i : icons){ declared.apply(i.extension().substring(1)).stream().filter(d -> !d.bundle().startsWith(home)).findFirst().ifPresent(d -> others.put(i.extension(), d)); }
    var foreign= new LinkedHashMap<String,List<String>>();
    others.forEach((e,d) -> { if (!Icon.system(e)){ foreign.put(e, List.of(baseName(d.bundle()))); } });
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, name, command, icons, programPng, others);
    if (alreadyMatches(existing, identity, wanted)){ if (!wanted.isEmpty()){ refresh(identity, halfDone); } return; }
    existing.forEach(s -> commandOf(s).filter(c -> !c.equals(command)).ifPresent(replaced));
    var before= text(plist(identity));
    existing.stream().filter(s -> wanted.isEmpty() || !s.equals(identity)).forEach(s -> remove(s, halfDone));
    if (wanted.isEmpty()){ return; }
    Fs.cleanDir(bundle(identity));
    wanted.keySet().forEach(f -> Fs.ensureDir(f.getParent()));
    wanted.forEach((f,bytes) -> Fs.ofV(() -> Files.write(f, bytes)));
    var executable= bundle(identity).resolve("Contents").resolve("MacOS").resolve(identity).toFile().setExecutable(true);
    assert executable;
    refresh(identity, halfDone);
    others.values().stream().filter(d -> !d.bundle().startsWith("/System") && !before.contains(string(d.type()))).forEach(d -> offer.accept(bundle(identity), d.type()));
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    existingIdentities(belongsToFamily).forEach(s -> remove(s, halfDone));
  }
  static List<String> existingIdentities(Predicate<String> belongsToFamily){
    if (!Files.isDirectory(home)){ return List.of(); }
    return Fs.of(() -> { try (var s= Files.list(home)){ return s.filter(p -> p.toString().endsWith(".app")).map(MacAssociations::baseName).filter(belongsToFamily).sorted().toList(); } });
  }
  public static String typeOf(String identity, String ext){ return identity.replace('_', '-').toLowerCase(Locale.ROOT)+ext; }
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
    var m= launcher.matcher(text(bundle(identity).resolve("Contents").resolve("MacOS").resolve(identity)));
    return m.matches() ? Optional.of(Path.of(m.group(1).replace("'\\''", "'"))) : Optional.empty();
  }
  private static String text(Path file){
    return Files.isRegularFile(file) ? new String(Fs.of(() -> Files.readAllBytes(file)), UTF_8) : "";
  }
  private static List<Path> files(Path dir){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.walk(dir, s -> s.filter(Files::isRegularFile).toList());
  }
  private static boolean alreadyMatches(List<String> existing, String identity, Map<Path,byte[]> wanted){
    var exactlyWanted= existing.stream().allMatch(identity::equals) && Set.copyOf(files(bundle(identity))).equals(wanted.keySet());
    if (!exactlyWanted){ return false; }
    return wanted.entrySet().stream().allMatch(e -> Arrays.equals(Fs.of(() -> Files.readAllBytes(e.getKey())), e.getValue()));
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
      documents.append(document(identity, ext, other.map(Declared::type).orElse(typeOf(identity, i.extension())), other.isPresent() ? "Alternate" : "Owner"));
      if (other.isEmpty()){ exported.append(exported(identity, ext)); }
    }
    res.put(contents.resolve("Resources").resolve("_program.icns"), icns(programPng));
    res.put(contents.resolve("Info.plist"), plist(identity, name, documents.toString(), exported.toString()).getBytes(UTF_8));
    res.put(contents.resolve("MacOS").resolve(identity), ("#!/bin/sh\nexec '"+command.toString().replace("'", "'\\''")+"' FearlessCommandOpened\n").getBytes(UTF_8));
    return Collections.unmodifiableMap(res);
  }
  private static byte[] icns(Path png){
    var image= Ico.png(Ico.scaled(Objects.requireNonNull(Fs.of(() -> ImageIO.read(png.toFile()))), 256));
    return ByteBuffer.allocate(16 + image.length).put("icns".getBytes(UTF_8)).putInt(16 + image.length).put("ic08".getBytes(UTF_8)).putInt(8 + image.length).put(image).array();
  }
  private static String string(String value){ return "<string>"+value+"</string>"; }
  private static String document(String identity, String ext, String type, String rank){
    return "<dict><key>CFBundleTypeName</key>"+string(identity)+"<key>CFBundleTypeRole</key>"+string("Viewer")+"<key>CFBundleTypeIconFile</key>"+string(ext+".icns")
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
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleIconFile</key><string>_program.icns</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleDocumentTypes</key><array>
%s</array>
<key>UTExportedTypeDeclarations</key><array>
%s</array>
</dict></plist>
""".formatted(identity, identity.replace('_', '-'), name, documents, exported);
  }
  private static String baseName(Path file){
    var name= file.getFileName().toString();
    return name.substring(0, name.lastIndexOf('.'));
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
    var type= FunctionDescriptor.of(res, Stream.of(args).map(MacAssociations::layout).toArray(MemoryLayout[]::new));
    try{ return (T)Linker.nativeLinker().downcallHandle(Desktop.lib.find(name).orElseThrow(), type).invokeWithArguments(args); }
    catch (Throwable e){ throw Bug.of(e); }
  }
  private static MemorySegment ptr(String name, Object... args){ return call(name, ValueLayout.ADDRESS, args); }
  private static MemorySegment cString(String s){ return Arena.global().allocateFrom(s); }
  private static MemorySegment cfString(String s){ return ptr("CFStringCreateWithCString", MemorySegment.NULL, cString(s), utf8); }
  private static MemorySegment cfUrl(Path p){
    var bytes= p.toString().getBytes(UTF_8);
    return ptr("CFURLCreateFromFileSystemRepresentation", MemorySegment.NULL, Arena.global().allocateFrom(ValueLayout.JAVA_BYTE, bytes), (long)bytes.length, true);
  }
  private static MemorySegment send(MemorySegment target, String selector, Object... args){
    return ptr("objc_msgSend", Stream.concat(Stream.of(target, ptr("sel_registerName", cString(selector))), Stream.of(args)).toArray());
  }
  private static List<Declared> declaredOnDesktop(String ext){
    var types= ptr("UTTypeCreateAllIdentifiersForTag", cfString("public.filename-extension"), cfString(ext), MemorySegment.NULL);
    var res= new ArrayList<Declared>();
    var buffer= Arena.global().allocate(4096);
    for (int i : Range.of(0, (int)(long)call("CFArrayGetCount", ValueLayout.JAVA_LONG, types))){
      var type= ptr("CFArrayGetValueAtIndex", types, (long)i);
      var url= ptr("UTTypeCopyDeclaringBundleURL", type);
      if (url.equals(MemorySegment.NULL)){ continue; }
      boolean named= call("CFStringGetCString", ValueLayout.JAVA_BOOLEAN, type, buffer, 4096L, utf8);
      var name= buffer.getString(0);
      boolean located= call("CFURLGetFileSystemRepresentation", ValueLayout.JAVA_BOOLEAN, url, true, buffer, 4096L);
      assert named && located;
      res.add(new Declared(name, Path.of(buffer.getString(0))));
    }
    return List.copyOf(res);
  }
  private static int registerOnDesktop(Path bundle){ return call("LSRegisterURL", ValueLayout.JAVA_INT, cfUrl(bundle), true); }
  private static void offerOnDesktop(Path bundle, String type){
    var workspace= send(ptr("objc_getClass", cString("NSWorkspace")), "sharedWorkspace");
    var contentType= send(ptr("objc_getClass", cString("UTType")), "typeWithIdentifier:", cfString(type));
    send(workspace, "setDefaultApplicationAtURL:toOpenContentType:completionHandler:", cfUrl(bundle), contentType, MemorySegment.NULL);
  }
}
