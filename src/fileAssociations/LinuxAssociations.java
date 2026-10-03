package fileAssociations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
  record Db(List<Glob> globs, Set<String> types, Map<String,String> aliases, List<List<String>> subclasses){
    String unalias(String type){ return aliases.getOrDefault(type, type); }
    Set<String> closure(String type, int from){
      var res= new LinkedHashSet<String>(List.of(type));
      var size= 0;
      while (size != res.size()){
        size= res.size();
        subclasses.stream().filter(s->res.contains(unalias(s.get(from)))).map(s->unalias(s.get(1-from))).toList().forEach(res::add);
      }
      return Collections.unmodifiableSet(res);
    }
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
    var db= db(belongsToFamily);
    var shared= new LinkedHashMap<String,Map.Entry<String,List<String>>>();
    for (var icon: extensions){
      types(db, icon.extension()).stream().map(t->Map.entry(t, others(db, t, icon.extension())))
        .filter(e->!e.getValue().isEmpty() || taken(db, icon.extension())).findFirst().ifPresent(e->shared.put(icon.extension(), e));
    }
    if (!shared.isEmpty()){ throw sharedType.apply(Collections.unmodifiableMap(shared)); }
    var foreign= new LinkedHashMap<String,List<String>>();
    for (var icon: extensions){
      var held= types(db, icon.extension()).stream().flatMap(t->claimants(db, db.closure(t, 0)).stream()).distinct().filter(belongsToFamily.negate()).toList();
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
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, command, extensions, programPng, db);
    if (stale.stream().allMatch(identity::equals) && alreadyMatches(identity, wanted)){ return; }
    stale.ifPresent(LinuxAssociations::eradicate);
    eradicate(identity);
    wanted.forEach(LinuxAssociations::write);
    rebuild(halfDone);
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    existing.forEach(LinuxAssociations::eradicate);
    familyIcons(belongsToFamily).forEach(f->Fs.ofV(()->Files.delete(f)));
    if (!existing.isEmpty()){ rebuild(halfDone); }
  }

  private static List<String> existingIdentities(Predicate<String> belongsToFamily){
    var res= new LinkedHashSet<String>();
    for (var dir: Xdg.appDirs()){ listed(dir, ".desktop").forEach(f->res.add(baseName(f,".desktop"))); }
    for (var dir: mimeDirs()){ listed(dir.resolve("packages"), ".xml").forEach(f->res.add(baseName(f,".xml"))); }
    return res.stream().filter(belongsToFamily).sorted().toList();
  }
  private static Db db(Predicate<String> belongsToFamily){
    var globs= new ArrayList<Glob>();
    var cleared= new HashSet<String>();
    var types= new HashSet<String>();
    var aliases= new HashMap<String,String>();
    var subclasses= new ArrayList<List<String>>();
    for (var dir: mimeDirs()){
      var keep= keep(dir, belongsToFamily);
      var here= fields(dir.resolve("globs2"), ":").filter(f->f.size() > 2 && keep.test(f.get(1)) && !cleared.contains(f.get(1))).toList();
      here.stream().filter(f->!f.get(2).equals("__NOGLOBS__"))
        .forEach(f->globs.add(new Glob(f.get(1), f.get(2), f.size() > 3 && List.of(f.get(3).split(",")).contains("cs"))));
      here.stream().filter(f->f.get(2).equals("__NOGLOBS__")).forEach(f->cleared.add(f.get(1)));
      fields(dir.resolve("types"), " ").filter(f->keep.test(f.getFirst())).forEach(f->types.add(f.getFirst()));
      fields(dir.resolve("aliases"), " ").filter(f->f.size() == 2 && keep.test(f.get(1))).forEach(f->aliases.putIfAbsent(f.get(0), f.get(1)));
      fields(dir.resolve("subclasses"), " ").filter(f->f.size() == 2 && keep.test(f.get(0))).forEach(subclasses::add);
    }
    return new Db(List.copyOf(globs), Set.copyOf(types), Map.copyOf(aliases), List.copyOf(subclasses));
  }
  private static Predicate<String> keep(Path dir, Predicate<String> belongsToFamily){
    var packages= listed(dir.resolve("packages"), ".xml");
    var family= packages.stream().filter(f->belongsToFamily.test(baseName(f, ".xml"))).flatMap(f->lines(f).stream())
      .filter(l->l.contains("<glob ")).flatMap(l->between(l, "<mime-type type=\"").stream()).collect(Collectors.toSet());
    if (!dir.equals(Xdg.dataHome().resolve("mime"))){ return t->!family.contains(t); }
    var others= packages.stream().filter(f->!belongsToFamily.test(baseName(f, ".xml"))).map(f->String.join("\n", lines(f))).toList();
    return t->!family.contains(t) && others.stream().anyMatch(p->p.contains("\""+t+"\"") || p.contains("'"+t+"'"));
  }
  private static Stream<List<String>> fields(Path file, String separator){
    return lines(file).stream().filter(l->!l.isBlank() && !l.startsWith("#")).map(l->List.of(l.strip().split(separator)));
  }
  private static List<String> known(List<Glob> globs, String ext){
    return globs.stream().filter(g->g.is(ext)).map(Glob::type).distinct().toList();
  }
  private static List<String> others(Db db, String type, String ext){
    var kinds= db.closure(type, 1);
    return db.globs().stream().filter(g->kinds.contains(g.type()) && !g.is(ext)).map(Glob::pattern).distinct().toList();
  }
  private static List<String> types(Db db, String ext){
    var known= known(db.globs(), ext);
    return known.isEmpty() ? List.of(db.unalias(typeOf(ext))) : known;
  }
  private static boolean taken(Db db, String ext){
    return known(db.globs(), ext).isEmpty() && (db.types().contains(typeOf(ext)) || db.aliases().containsKey(typeOf(ext)));
  }
  private static Set<String> claimants(Db db, Set<String> types){
    Predicate<String> opens= t->types.contains(db.unalias(t));
    var res= new LinkedHashSet<String>();
    for (var dir: Xdg.appDirs()){
      for (var file: listed(dir, ".desktop")){
        if (claimedTypes(file).stream().anyMatch(opens)){ res.add(baseName(file,".desktop")); }
      }
    }
    for (var file: Xdg.choiceFiles()){
      chosenFor(file, opens).forEach(name->res.add(name.endsWith(".desktop") ? name.substring(0,name.length()-".desktop".length()) : name));
    }
    return Collections.unmodifiableSet(res);//keep insertion order, unlike Set.copyOf
  }
  private static List<String> chosenFor(Path file, Predicate<String> opens){
    var res= new ArrayList<String>();
    var chosen= false;
    for (var line: lines(file)){
      if (line.startsWith("[")){ chosen= line.startsWith("[Default Applications]") || line.startsWith("[Added Associations]"); continue; }
      var eq= line.indexOf('=');
      if (!chosen || eq < 0 || !opens.test(line.substring(0,eq).strip())){ continue; }
      res.addAll(splitTypes(line.substring(eq+1)));
    }
    return Collections.unmodifiableList(res);
  }
  private static List<Path> filesOf(String identity){
    var desktops= Xdg.appDirs().stream().map(d->d.resolve(identity+".desktop"));
    var packages= mimeDirs().stream().map(d->d.resolve("packages").resolve(identity+".xml"));
    return Stream.concat(desktops, packages).filter(Files::isRegularFile).toList();
  }
  private static List<Path> targetsNotWritable(){
    return Stream.of(Xdg.dataHome().resolve("applications"), Xdg.dataHome().resolve("mime").resolve("packages"), iconDir().resolve("mimetypes"), iconDir().resolve("apps"))
      .filter(d->!writableForCreation(d)).toList();
  }
  private static boolean writableForCreation(Path dir){
    var p= dir;
    while (p != null && !Files.exists(p)){ p= p.getParent(); }
    return p != null && Files.isWritable(p);
  }
  private static Map<Path,byte[]> wanted(String identity, Path command, List<Icon> extensions, Path programPng, Db db){
    var res= new LinkedHashMap<Path,byte[]>();
    var icons= new LinkedHashMap<String,String>();
    for (var icon: extensions){
      var bytes= desktopPng(icon.png());
      var name= identity+"-"+hash(bytes);
      icons.put(icon.extension(), name);
      res.put(iconDir().resolve("mimetypes").resolve(name+".png"), bytes);
    }
    var types= extensions.stream().flatMap(i->types(db, i.extension()).stream()).toList();
    res.put(ourDesktop(identity), utf8(desktopEntry(identity, command.toString(), windowClass(), types)));
    res.put(ourPackage(identity), utf8(mimePackage(identity, icons, db.globs())));
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
  private static List<Path> familyIcons(Predicate<String> belongsToFamily){
    var icons= listed(iconDir().resolve("mimetypes"), ".png").stream().filter(f->ofFamily(belongsToFamily, f));
    var apps= listed(iconDir().resolve("apps"), ".png").stream().filter(f->belongsToFamily.test(baseName(f, ".png")));
    return Stream.concat(icons, apps).toList();
  }
  private static boolean ofFamily(Predicate<String> belongsToFamily, Path icon){
    var m= Pattern.compile("(.+)-[0-9a-f]+\\.png").matcher(icon.getFileName().toString());
    return m.matches() && belongsToFamily.test(m.group(1));
  }
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
