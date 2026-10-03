package fileAssociations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import tools.Fs;
import utils.Push;

public final class LinuxAssociations{
  public static Function<String,String> env= System::getenv;
  public static BiConsumer<List<String>,Function<String,RuntimeException>> run= Shell::req;
  private static final int iconSide= 256;
  record Glob(String type, String pattern, boolean cs){
    boolean is(String ext){ return cs ? pattern.equals("*"+ext) : pattern.equalsIgnoreCase("*"+ext); }
  }
  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programPng,
      Function<String,RuntimeException> ambiguous,
      Function<Map<String,Map.Entry<String,List<String>>>,RuntimeException> sharedType,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(String.join("\n", existing)); }
    var globs= globs(belongsToFamily);
    var shared= new LinkedHashMap<String,Map.Entry<String,List<String>>>();
    for (var icon: extensions){
      known(globs, icon.extension()).stream().map(t->Map.entry(t, others(globs, t, icon.extension())))
        .filter(e->!e.getValue().isEmpty()).findFirst().ifPresent(e->shared.put(icon.extension(), e));
    }
    if (!shared.isEmpty()){ throw sharedType.apply(Collections.unmodifiableMap(shared)); }
    var foreign= new LinkedHashMap<String,List<String>>();
    for (var icon: extensions){
      var held= types(globs, icon.extension()).stream().flatMap(t->claimants(t).stream()).distinct().filter(belongsToFamily.negate()).toList();
      if (!held.isEmpty()){ foreign.put(icon.extension(), held); }
    }
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    var stale= existing.isEmpty() ? Optional.<String>empty() : Optional.of(existing.getFirst());
    var unwritable= new ArrayList<Path>();
    stale.ifPresent(s->unwritable.addAll(filesOf(s).stream().filter(f->!Files.isWritable(f)).toList()));
    if (!extensions.isEmpty() || stale.isPresent()){ unwritable.addAll(targetsNotWritable()); }
    if (!unwritable.isEmpty()){
      throw notWritable.apply(String.join("\n", unwritable.stream().map(Path::toString).toList()));
    }
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, command, extensions, programPng, globs);
    if (stale.stream().allMatch(identity::equals) && alreadyMatches(identity, wanted)){ return; }
    stale.ifPresent(LinuxAssociations::eradicate);
    eradicate(identity);
    wanted.forEach(LinuxAssociations::write);
    rebuild(halfDone);
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.isEmpty()){ return; }
    existing.forEach(LinuxAssociations::eradicate);
    rebuild(halfDone);
  }

  private static List<String> existingIdentities(Predicate<String> belongsToFamily){
    var res= new LinkedHashSet<String>();
    for (var dir: Xdg.appDirs()){ listed(dir, ".desktop").forEach(f->res.add(baseName(f,".desktop"))); }
    for (var dir: mimeDirs()){ listed(dir.resolve("packages"), ".xml").forEach(f->res.add(baseName(f,".xml"))); }
    return res.stream().filter(belongsToFamily).sorted().toList();
  }
  private static List<Glob> globs(Predicate<String> belongsToFamily){
    var ours= familyTypes(belongsToFamily);
    var res= new ArrayList<Glob>();
    var cleared= new HashSet<String>();
    for (var dir: mimeDirs()){
      var here= lines(dir.resolve("globs2")).stream().filter(l->!l.startsWith("#")).map(l->l.split(":"))
        .filter(f->f.length > 2 && !ours.contains(f[1]) && !cleared.contains(f[1])).toList();
      here.stream().filter(f->!f[2].equals("__NOGLOBS__"))
        .forEach(f->res.add(new Glob(f[1], f[2], f.length > 3 && List.of(f[3].split(",")).contains("cs"))));
      here.stream().filter(f->f[2].equals("__NOGLOBS__")).forEach(f->cleared.add(f[1]));
    }
    return List.copyOf(res);
  }
  private static Set<String> familyTypes(Predicate<String> belongsToFamily){
    return mimeDirs().stream().flatMap(d->listed(d.resolve("packages"), ".xml").stream())
      .filter(f->belongsToFamily.test(baseName(f, ".xml"))).flatMap(f->lines(f).stream())
      .filter(l->l.contains("<glob ")).flatMap(l->between(l, "<mime-type type=\"").stream()).collect(Collectors.toSet());
  }
  private static List<String> known(List<Glob> globs, String ext){
    return globs.stream().filter(g->g.is(ext)).map(Glob::type).distinct().toList();
  }
  private static List<String> others(List<Glob> globs, String type, String ext){
    return globs.stream().filter(g->g.type().equals(type) && !g.is(ext)).map(Glob::pattern).distinct().toList();
  }
  private static List<String> types(List<Glob> globs, String ext){
    var known= known(globs, ext);
    return known.isEmpty() ? List.of(typeOf(ext)) : known;
  }
  private static Set<String> claimants(String type){
    var res= new LinkedHashSet<String>();
    for (var dir: Xdg.appDirs()){
      for (var file: listed(dir, ".desktop")){
        if (claimedTypes(file).contains(type)){ res.add(baseName(file,".desktop")); }
      }
    }
    for (var file: Xdg.choiceFiles()){
      chosenFor(file, type).forEach(name->res.add(name.endsWith(".desktop") ? name.substring(0,name.length()-".desktop".length()) : name));
    }
    return Collections.unmodifiableSet(res);//keep insertion order, unlike Set.copyOf
  }
  private static List<String> chosenFor(Path file, String type){
    var inDefaults= false;
    for (var line: lines(file)){
      if (line.startsWith("[")){ inDefaults= line.startsWith("[Default Applications]"); continue; }
      if (!inDefaults){ continue; }
      var eq= line.indexOf('=');
      if (eq < 0 || !line.substring(0,eq).strip().equals(type)){ continue; }
      return splitTypes(line.substring(eq+1));
    }
    return List.of();
  }
  private static List<Path> filesOf(String identity){
    var desktops= Xdg.appDirs().stream().map(d->d.resolve(identity+".desktop"));
    var packages= mimeDirs().stream().map(d->d.resolve("packages").resolve(identity+".xml"));
    return Stream.concat(desktops, packages).filter(Files::isRegularFile).toList();
  }
  private static List<Path> targetsNotWritable(){
    return Stream.of(Xdg.dataHome().resolve("applications"), Xdg.dataHome().resolve("mime").resolve("packages"))
      .filter(d->!writableForCreation(d)).toList();
  }
  private static boolean writableForCreation(Path dir){
    var p= dir;
    while (p != null && !Files.exists(p)){ p= p.getParent(); }
    return p != null && Files.isWritable(p);
  }
  private static Map<Path,byte[]> wanted(String identity, Path command, List<Icon> extensions, Path programPng, List<Glob> globs){
    var res= new LinkedHashMap<Path,byte[]>();
    var icons= new LinkedHashMap<String,String>();
    for (var icon: extensions){
      var bytes= desktopPng(icon.png());
      var name= identity+"-"+hash(bytes);
      icons.put(icon.extension(), name);
      res.put(iconDir().resolve("mimetypes").resolve(name+".png"), bytes);
    }
    var types= extensions.stream().flatMap(i->types(globs, i.extension()).stream()).toList();
    res.put(ourDesktop(identity), utf8(desktopEntry(identity, command.toString(), windowClass(), types)));
    res.put(ourPackage(identity), utf8(mimePackage(identity, icons, globs)));
    res.put(appIcon(identity), desktopPng(programPng));
    return Collections.unmodifiableMap(res);
  }
  private static boolean alreadyMatches(String identity, Map<Path,byte[]> wanted){
    if (!Set.copyOf(ownedFiles(identity)).equals(wanted.keySet())){ return false; }
    return wanted.entrySet().stream().allMatch(e->Arrays.equals(bytesOf(e.getKey()), e.getValue()));
  }
  private static List<Path> ownedFiles(String identity){
    var icon= Pattern.compile(Pattern.quote(identity)+"-[0-9a-f]+\\.png");
    var icons= listed(iconDir().resolve("mimetypes"), ".png").stream().filter(f->icon.matcher(f.getFileName().toString()).matches());
    return Stream.concat(icons, Stream.of(ourDesktop(identity), ourPackage(identity), appIcon(identity)).filter(Files::isRegularFile)).toList();
  }
  private static void eradicate(String identity){ ownedFiles(identity).forEach(f->Fs.ofV(()->Files.delete(f))); }
  private static void write(Path file, byte[] bytes){
    Fs.ensureDir(file.getParent());
    Fs.ofV(()->Files.write(file, bytes));
  }
  private static void rebuild(Function<String,RuntimeException> halfDone){
    run.accept(List.of("update-mime-database", Xdg.dataHome().resolve("mime").toString()), halfDone);
    Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
      .forEach(d->run.accept(List.of("update-desktop-database", d.toString()), halfDone));
  }
  private static Path ourDesktop(String identity){ return Xdg.dataHome().resolve("applications").resolve(identity+".desktop"); }
  private static Path ourPackage(String identity){ return Xdg.dataHome().resolve("mime").resolve("packages").resolve(identity+".xml"); }
  private static Path appIcon(String identity){ return iconDir().resolve("apps").resolve(identity+".png"); }
  private static String typeOf(String ext){ return "application/x-"+ext.substring(1); }
  private static byte[] desktopPng(Path png){
    return Ico.png(Ico.scaled(Objects.requireNonNull(Fs.of(()->ImageIO.read(png.toFile()))), iconSide));
  }
  private static Path iconDir(){ return Xdg.dataHome().resolve("icons").resolve("hicolor").resolve(iconSide+"x"+iconSide); }
  private static byte[] bytesOf(Path file){ return Fs.of(()->Files.readAllBytes(file)); }
  private static byte[] utf8(String text){ return text.getBytes(StandardCharsets.UTF_8); }
  public static String hash(byte[] bytes){
    var h= 0xcbf29ce484222325L;
    for (var b: bytes){ h= (h ^ (b & 0xff))*0x100000001b3L; }
    return Long.toHexString(h);
  }
  private static Optional<String> between(String line, String open){
    var i= line.indexOf(open);
    if (i < 0){ return Optional.empty(); }
    var rest= line.substring(i+open.length());
    var end= rest.indexOf('"');
    return end < 0 ? Optional.empty() : Optional.of(rest.substring(0, end));
  }
  private static String mimePackage(String identity, Map<String,String> icons, List<Glob> globs){
    var body= new StringBuilder();
    icons.forEach((ext,icon)->body.append(mimeTypes(identity, ext, icon, known(globs, ext))));
    return """
      <?xml version="1.0" encoding="UTF-8"?>
      <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
      %s</mime-info>
      """.formatted(body);
  }
  private static String mimeTypes(String identity, String ext, String icon, List<String> known){
    if (!known.isEmpty()){
      return known.stream().map(t->"  <mime-type type=\"%s\"><icon name=\"%s\"/></mime-type>\n".formatted(t, icon)).collect(Collectors.joining());
    }
    return ("  <mime-type type=\"%s\"><comment>%s</comment>"
      +"<glob pattern=\"*%s\" weight=\"100\"/><icon name=\"%s\"/></mime-type>\n")
      .formatted(typeOf(ext), identity, ext, icon);
  }
  public static String desktopEntry(String identity, String command, String windowClass, List<String> types){
    return """
      [Desktop Entry]
      Type=Application
      Name=%s
      Exec=%s %%f
      Icon=%s
      StartupWMClass=%s
      Terminal=false
      MimeType=%s;
      """.formatted(identity, execArg(command), identity, windowClass, String.join(";", types));
  }
  private static String execArg(String s){
    return ("\""+s.replaceAll("[\"`$\\\\]", "\\\\$0")+"\"").replace("%", "%%").replace("\\", "\\\\");
  }
  public static String windowClass(String javaCommand){
    var main= javaCommand.split(" ")[0];
    return main.substring(main.indexOf('/')+1).replace('.', '-');
  }
  private static String windowClass(){ return windowClass(System.getProperty("sun.java.command")); }
  private static List<Path> mimeDirs(){ return Push.of(Xdg.dataHome(), Xdg.dataDirs()).stream().map(d->d.resolve("mime")).toList(); }
  private static List<String> splitTypes(String types){
    return List.of(types.split(";")).stream().map(String::strip).filter(s->!s.isEmpty()).toList();
  }
  static List<String> claimedTypes(Path desktopFile){
    for (var line: lines(desktopFile)){
      if (!line.startsWith("MimeType=")){ continue; }
      return splitTypes(line.substring("MimeType=".length()));
    }
    return List.of();
  }
  private static List<Path> listed(Path dir, String suffix){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.of(()->{ try(var s= Files.list(dir)){
      return s.filter(p->p.getFileName().toString().endsWith(suffix)).sorted().toList(); }});
  }
  private static List<String> lines(Path file){
    if (!Files.isRegularFile(file)){ return List.of(); }
    return Fs.of(()->Files.readAllLines(file));
  }
  private static String baseName(Path file, String suffix){
    var name= file.getFileName().toString();
    return name.substring(0, name.length()-suffix.length());
  }
}
