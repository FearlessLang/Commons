package fileAssociations;

import static fileAssociations.FileAssociations.baseName;
import static fileAssociations.FileAssociations.same;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import tools.Fs;
import utils.DistinctBy;
import utils.Join;
import utils.Push;
import utils.Streams;

public final class LinuxAssociations{
  public static Function<String,String> env= System::getenv;
  public static BiConsumer<List<String>,Function<String,RuntimeException>> run= LinuxAssociations::req;
  private static final Pattern declared= Pattern.compile("<mime-type type=[\"']([^\"']*)[\"']");
  record Glob(int weight, String type, String pattern, boolean cs){
    boolean matches(String name){
      var regex= new StringBuilder();
      for (var c : (cs ? pattern : pattern.toLowerCase(Locale.ROOT)).replace("[!", "[^").toCharArray()){ regex.append(wildcard(c)); }
      return name.matches(regex.toString());
    }
    private static String wildcard(char c){
      return switch (c){
        case '*' -> ".*";
        case '?' -> ".";
        case '[', ']', '-', '^' -> String.valueOf(c);
        default -> Character.isLetterOrDigit(c) ? String.valueOf(c) : "\\"+c;
      };
    }
  }
  static void reconcile(String identity, String name, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, List<String> existing, List<String> takenOver, List<WindowIcon> windows, Path programPng, Consumer<Path> replaced,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var types= takenOver.stream().map(LinuxAssociations::typeOf).toList();
    var chosen= choiceFiles().stream().filter(f -> lines(f).stream().anyMatch(l -> keyed(l, types))).toList();
    var unwritable= registrations().stream().filter(f -> existing.contains(identityOf(f)))
      .flatMap(f -> Stream.of(f, f.getParent())).distinct().filter(f -> !Files.isWritable(f));
    var targets= (extensions.isEmpty() && existing.isEmpty()) ? Stream.<Path>of()
      : Stream.of(home("applications"), home("mime/packages"), iconDir("mimetypes"), iconDir("apps")).filter(d -> !writableForCreation(d));
    var refused= Streams.of(unwritable, targets, chosen.stream().filter(f -> !Files.isWritable(f))).map(Path::toString).toList();
    if (!refused.isEmpty()){ throw notWritable.apply(Join.of(refused, "", "\n", "")); }
    chosen.forEach(f -> Fs.ofV(() -> Files.writeString(f, Join.of(lines(f).stream().filter(l -> !keyed(l, types)), "", "\n", "\n", ""))));
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, name, command, extensions, windows, programPng, globs(belongsToFamily));
    if (same(existing, identity, owned(identity::equals), wanted)){ if (!wanted.isEmpty()){ rebuild(List.of(), halfDone); } return; }
    existing.forEach(s -> commandOf(s).filter(c -> !c.equals(command)).ifPresent(replaced));
    var gone= owned(Push.of(existing, identity)::contains);
    gone.forEach(f -> Fs.ofV(() -> Files.delete(f)));
    wanted.keySet().forEach(f -> Fs.ensureDir(f.getParent()));
    wanted.forEach((f,bytes) -> Fs.ofV(() -> Files.write(f, bytes)));
    rebuild(gone, halfDone);
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    var gone= owned(belongsToFamily);
    gone.forEach(f -> Fs.ofV(() -> Files.delete(f)));
    if (!existing.isEmpty()){ rebuild(gone, halfDone); }
  }
  static Map<String,String> holders(List<String> extensions){
    var database= globs(_ -> false);
    var parents= mimeDirs().stream().flatMap(d -> fields(d.resolve("subclasses"), " ")).toList();
    var res= new LinkedHashMap<String,String>();
    extensions.forEach(e -> holder(e, database, parents).ifPresent(h -> res.put(e, h)));
    return res;
  }
  private static Optional<String> holder(String ext, List<Glob> database, List<List<String>> parents){
    var types= database.stream().filter(g -> g.matches("x"+ext)).max(Comparator.comparingInt(Glob::weight)).map(Glob::type).stream().collect(Collectors.toCollection(ArrayList::new));
    for (var i= 0; i<types.size(); i++){
      var type= types.get(i);
      parents.stream().filter(p -> p.get(0).equals(type) && !types.contains(p.get(1))).forEach(p -> types.add(p.get(1)));
    }
    return types.stream().flatMap(t -> holderOf(t).stream()).findFirst();
  }
  private static Optional<String> holderOf(String type){
    var installed= desktops().map(f -> f.getFileName().toString()).toList();
    var removed= entries("[Removed Associations]", type).toList();
    var listing= desktops().gather(DistinctBy.of(Path::getFileName)).filter(f -> opens(f).anyMatch(type::equals)).map(f -> f.getFileName().toString());
    return Stream.concat(entries("[Default Applications]", type), Stream.concat(entries("[Added Associations]", type), listing).filter(n -> !removed.contains(n)))
      .filter(installed::contains).findFirst().map(n -> n.replaceFirst("\\.desktop$", ""));
  }
  private static Stream<String> entries(String section, String type){
    var res= new ArrayList<String>();
    for (var list : choiceFiles()){
      var here= "";
      for (var l : lines(list).stream().map(String::strip).toList()){
        if (l.startsWith("[")){ here = l; continue; }
        if (here.equals(section) && keyed(l, List.of(type))){ split(l.substring(l.indexOf('=') + 1)).forEach(res::add); }
      }
    }
    return res.stream();
  }
  private static boolean keyed(String line, List<String> types){
    var eq= line.indexOf('=');
    return eq>0 && types.contains(line.substring(0, eq).strip());
  }
  private static Stream<Path> desktops(){ return appDirs().stream().flatMap(d -> listed(d, ".desktop").stream()); }
  private static Optional<Path> commandOf(String identity){
    return desktops().filter(f -> baseName(f).equals(identity)).findFirst()
      .flatMap(f -> lines(f).stream().filter(l -> l.startsWith("TryExec=")).findFirst())
      .map(l -> Path.of(l.substring("TryExec=".length()).replace("\\\\", "\\")));
  }
  private static List<Path> registrations(){
    return Stream.concat(desktops(), mimeDirs().stream().flatMap(d -> listed(d.resolve("packages"), ".xml").stream())).toList();
  }
  static List<String> existingIdentities(Predicate<String> belongsToFamily){
    return registrations().stream().map(LinuxAssociations::identityOf).filter(belongsToFamily).distinct().sorted().toList();
  }
  private static List<Path> owned(Predicate<String> owner){
    var icons= listed(iconDir("mimetypes"), ".png").stream()
      .filter(f -> baseName(f).matches(".+-[0-9a-f]+") && owner.test(identityOf(f)));
    var named= listed(iconDir("apps"), ".png").stream().filter(f -> owner.test(baseName(f)));
    var registered= registrations().stream().filter(f -> owner.test(identityOf(f)));
    return Streams.of(icons, named, registered).toList();
  }
  private static List<Glob> globs(Predicate<String> belongsToFamily){
    var res= new ArrayList<Glob>();
    var cleared= new HashSet<String>();
    for (var dir : mimeDirs()){
      var keep= keep(dir, belongsToFamily);
      var here= fields(dir.resolve("globs2"), ":").filter(f -> f.size()>2 && keep.test(f.get(1)) && !cleared.contains(f.get(1))).toList();
      here.stream().filter(f -> !f.get(2).equals("__NOGLOBS__"))
        .forEach(f -> res.add(new Glob(Integer.parseInt(f.get(0)), f.get(1), f.get(2), f.size()>3 && List.of(f.get(3).split(",")).contains("cs"))));
      here.stream().filter(f -> f.get(2).equals("__NOGLOBS__")).forEach(f -> cleared.add(f.get(1)));
    }
    return List.copyOf(res);
  }
  private static Predicate<String> keep(Path dir, Predicate<String> belongsToFamily){
    var packages= listed(dir.resolve("packages"), ".xml").stream().collect(Collectors.partitioningBy(f -> belongsToFamily.test(baseName(f))));
    var family= packages.get(true).stream().flatMap(f -> lines(f).stream())
      .map(declared::matcher).filter(Matcher::find).map(m -> m.group(1)).collect(Collectors.toUnmodifiableSet());
    if (!dir.equals(home("mime"))){ return t -> !family.contains(t); }
    var others= Join.of(packages.get(false).stream().flatMap(f -> lines(f).stream()), "", "\n", "", "");
    return t -> !family.contains(t) && (others.contains("\""+t+"\"") || others.contains("'"+t+"'"));
  }
  private static Stream<List<String>> fields(Path file, String separator){
    return lines(file).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> List.of(l.strip().split(separator)));
  }
  private static Stream<String> opens(Path desktopFile){
    return lines(desktopFile).stream().filter(l -> l.startsWith("MimeType=")).findFirst().stream().flatMap(l -> split(l.substring("MimeType=".length())));
  }
  static boolean writableForCreation(Path dir){
    return Stream.iterate(dir, Objects::nonNull, Path::getParent).filter(Files::exists).findFirst().filter(Files::isWritable).isPresent();
  }
  private static Map<Path,byte[]> wanted(String identity, String name, Path command, List<Icon> extensions, List<WindowIcon> windows, Path programPng, List<Glob> globs){
    var res= new LinkedHashMap<Path,byte[]>();
    var body= new StringBuilder();
    for (var icon : extensions){
      body.append(mimeType(icon.extension(), icon(res, identity, icon.png()), globs));
    }
    for (var w : windows){
      var entry= windowEntry(iconDir("mimetypes").resolve(icon(res, identity, w.png())+".png").toString(), command.toString(), w);
      res.put(home("applications").resolve(identity+"-"+hash(utf8(w.windowClass()))+".desktop"), utf8(entry));
    }
    var types= extensions.stream().map(i -> typeOf(i.extension())).toList();
    res.put(home("applications").resolve(identity+".desktop"), utf8(desktopEntry(identity, name, command.toString(), windowClass(System.getProperty("sun.java.command")), types)));
    res.put(home("mime/packages").resolve(identity+".xml"), utf8("""
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
%s</mime-info>
""".formatted(body)));
    res.put(iconDir("apps").resolve(identity+".png"), Ico.png(programPng));
    return Collections.unmodifiableMap(res);
  }
  private static String icon(LinkedHashMap<Path,byte[]> res, String identity, Path png){
    var bytes= Ico.png(png);
    var name= identity+"-"+hash(bytes);
    res.put(iconDir("mimetypes").resolve(name+".png"), bytes);
    return name;
  }
  private static void rebuild(List<Path> gone, Function<String,RuntimeException> halfDone){
    mimeDirs().stream().filter(d -> d.equals(home("mime")) || gone.stream().anyMatch(f -> f.getParent().equals(d.resolve("packages"))))
      .forEach(d -> run.accept(List.of("update-mime-database", d.toString()), halfDone));
    appDirs().stream().filter(d -> d.equals(home("applications")) || gone.stream().anyMatch(f -> f.getParent().equals(d)))
      .forEach(d -> run.accept(List.of("update-desktop-database", d.toString()), halfDone));
  }
  private static Path home(String folder){ return dataHome().resolve(folder); }
  private static Path iconDir(String kind){ return home("icons/hicolor/256x256").resolve(kind); }
  public static String typeOf(String ext){ return "application/x-fearless"+(ext.equals(".fearless") ? "" : "-"+ext.substring(1)); }
  private static byte[] utf8(String text){ return text.getBytes(StandardCharsets.UTF_8); }
  public static String hash(byte[] bytes){
    var h= 0xcbf29ce484222325L;
    for (var b : bytes){ h = (h ^ (b & 0xff)) * 0x100000001b3L; }
    return Long.toHexString(h);
  }
  private static String mimeType(String ext, String icon, List<Glob> globs){
    var subclass= Join.of(globs.stream().filter(g -> g.matches("*"+ext)).map(Glob::type).distinct().map(t -> "<sub-class-of type=\"%s\"/>".formatted(t)), "", "", "", "");
    return ("  <mime-type type=\"%s\">"
      +"<glob pattern=\"*%s\" weight=\"100\"/>%s<icon name=\"%s\"/></mime-type>\n")
      .formatted(typeOf(ext), ext, subclass, icon);
  }
  public static String desktopEntry(String identity, String name, String command, String windowClass, List<String> types){
    return """
[Desktop Entry]
Type=Application
Name=%s
Exec=env LC_ALL=C.UTF-8 %s %%f
TryExec=%s
Icon=%s
StartupWMClass=%s
Terminal=false
MimeType=%s;
""".formatted(name, execArg(command), command.replace("\\", "\\\\"), identity, windowClass, Join.of(types, "", ";", ""));
  }
  private static String windowEntry(String icon, String command, WindowIcon w){
    return """
[Desktop Entry]
Type=Application
Name=%s
Exec=env LC_ALL=C.UTF-8 %s %s
Icon=%s
StartupWMClass=%s
NoDisplay=true
Terminal=false
""".formatted(w.name(), execArg(command), execArg(w.target().toString()), icon, w.windowClass());
  }
  private static String execArg(String s){
    return ("\""+s.replaceAll("[\"`$\\\\]", "\\\\$0")+"\"").replace("%", "%%").replace("\\", "\\\\");
  }
  public static String windowClass(String javaCommand){
    var main= javaCommand.split(" ")[0];
    return main.substring(main.indexOf('/') + 1).replace('.', '-');
  }
  private static List<Path> mimeDirs(){ return Push.of(dataHome(), dataDirs()).stream().map(d -> d.resolve("mime")).toList(); }
  private static Stream<String> split(String types){ return Stream.of(types.split(";")).map(String::strip).filter(s -> !s.isEmpty()); }
  private static List<Path> listed(Path dir, String suffix){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.of(() -> { try (var s= Files.list(dir)){ return s.filter(p -> p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p)).sorted().toList(); } });
  }
  // The desktop tools refuse a file that is not UTF-8 as a whole, so such a file declares nothing
  private static List<String> lines(Path file){
    if (!Files.isRegularFile(file)){ return List.of(); }
    try{ return Files.readAllLines(file); }
    catch (MalformedInputException _){ return List.of(); }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  private static String identityOf(Path file){ return baseName(file).replaceFirst("-[0-9a-f]+$", ""); }
  private static Path dataHome(){ return dir("XDG_DATA_HOME", userHome().resolve(".local").resolve("share")); }
  private static Path configHome(){ return dir("XDG_CONFIG_HOME", userHome().resolve(".config")); }
  private static Path userHome(){ return Path.of(System.getProperty("user.home")); }
  private static List<Path> dataDirs(){ return dirs("XDG_DATA_DIRS", List.of(Path.of("/usr/local/share"), Path.of("/usr/share"))); }
  private static List<Path> configDirs(){ return dirs("XDG_CONFIG_DIRS", List.of(Path.of("/etc/xdg"))); }
  //Every applications folder the desktop reads, nearest first.
  private static List<Path> appDirs(){ return Push.of(dataHome(), dataDirs()).stream().map(d -> d.resolve("applications")).toList(); }
  //The desktop reads a prefixed list before the plain one, one prefix per name in XDG_CURRENT_DESKTOP.
  private static List<String> listNames(){
    var prefixed= Stream.of(Objects.requireNonNullElse(env.apply("XDG_CURRENT_DESKTOP"), "").split(":"))
      .filter(de -> !de.isEmpty()).map(de -> de.toLowerCase(Locale.ROOT)+"-mimeapps.list").toList();
    return Push.of(prefixed, "mimeapps.list");
  }
  //Every place a chosen answer can live, in the order the desktop consults them.
  private static List<Path> choiceFiles(){
    return Stream.of(List.of(configHome()), configDirs(), appDirs()).flatMap(List::stream)
      .flatMap(root -> listNames().stream().map(root::resolve)).toList();
  }
  private static Path dir(String name, Path fallback){
    var v= env.apply(name);
    if (v==null || v.isBlank()){ return fallback; }
    var res= Path.of(v);
    return res.isAbsolute() ? res.normalize() : fallback;
  }
  private static List<Path> dirs(String name, List<Path> fallback){
    var v= env.apply(name);
    if (v==null || v.isBlank()){ return fallback; }
    var res= Stream.of(v.split(":")).filter(part -> !part.isBlank()).map(Path::of).filter(Path::isAbsolute).map(Path::normalize).toList();
    return res.isEmpty() ? fallback : res;
  }
  static void req(List<String> cmd, Function<String,RuntimeException> stepFailed){
    var shown= Join.of(cmd, "", " ", "")+"\n";
    Process p;
    try{ p= Fs.processBuilder(cmd).redirectErrorStream(true).start(); }
    catch (IOException _){ throw stepFailed.apply(shown+"The program could not be started."); }
    var out= Fs.of(() -> new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    if (p.onExit().join().exitValue() != 0){ throw stepFailed.apply((shown+out).strip()); }
  }
}
