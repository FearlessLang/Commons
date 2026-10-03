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
import java.util.regex.Matcher;
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
  private static final Pattern declared= Pattern.compile("<mime-type type=\"([^\"]*)\"");
  record Glob(String type, String pattern, boolean cs){
    boolean is(String ext){ return cs ? pattern.equals("*"+ext) : pattern.equalsIgnoreCase("*"+ext); }
  }
  record Db(List<Glob> globs, Set<String> names, Map<String,String> aliases, List<List<String>> subclasses, List<Map.Entry<String,String>> opened){
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
    List<String> known(String ext){ return globs.stream().filter(g->g.is(ext)).map(Glob::type).distinct().toList(); }
    List<String> types(String ext){
      var known= known(ext);
      return known.isEmpty() ? List.of(unalias(typeOf(ext))) : known;
    }
    Optional<Map.Entry<String,List<String>>> shared(String ext){
      var taken= known(ext).isEmpty() && (names.contains(typeOf(ext)) || aliases.containsKey(typeOf(ext)));
      return types(ext).stream().map(t->Map.entry(t, others(t, ext))).filter(e->taken || !e.getValue().isEmpty()).findFirst();
    }
    List<String> others(String type, String ext){
      var below= closure(type, 1);
      return globs.stream().filter(g->below.contains(g.type()) && !g.is(ext)).map(Glob::pattern).distinct().toList();
    }
    List<String> held(String ext){
      return types(ext).stream().map(t->closure(t, 0))
        .flatMap(above->opened.stream().filter(o->above.contains(unalias(o.getValue())))).map(Map.Entry::getKey).distinct().toList();
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
    extensions.forEach(icon->db.shared(icon.extension()).ifPresent(s->shared.put(icon.extension(), s)));
    if (!shared.isEmpty()){ throw sharedType.apply(Collections.unmodifiableMap(shared)); }
    var foreign= new LinkedHashMap<String,List<String>>();
    for (var icon: extensions){
      var held= db.held(icon.extension()).stream().filter(belongsToFamily.negate()).toList();
      if (!held.isEmpty()){ foreign.put(icon.extension(), held); }
    }
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    var unwritable= registrations().stream().filter(f->existing.contains(baseName(f)) && !Files.isWritable(f));
    var targets= extensions.isEmpty() && existing.isEmpty() ? Stream.<Path>of()
      : Stream.of(home("applications"), home("mime/packages"), iconDir("mimetypes"), iconDir("apps")).filter(d->!writableForCreation(d));
    var refused= Stream.concat(unwritable, targets).map(Path::toString).toList();
    if (!refused.isEmpty()){ throw notWritable.apply(String.join("\n", refused)); }
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, command, extensions, programPng, db);
    if (alreadyMatches(existing, identity, wanted)){ return; }
    owned(Push.of(existing, identity)::contains).forEach(f->Fs.ofV(()->Files.delete(f)));
    wanted.keySet().forEach(f->Fs.ensureDir(f.getParent()));
    wanted.forEach((f,bytes)->Fs.ofV(()->Files.write(f, bytes)));
    rebuild(halfDone);
  }
  static void eradicateAll(Predicate<String> belongsToFamily, Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    owned(belongsToFamily).forEach(f->Fs.ofV(()->Files.delete(f)));
    if (!existing.isEmpty()){ rebuild(halfDone); }
  }

  private static Stream<Path> desktops(){ return Xdg.appDirs().stream().flatMap(d->listed(d, ".desktop").stream()); }
  private static List<Path> registrations(){
    return Stream.concat(desktops(), mimeDirs().stream().flatMap(d->listed(d.resolve("packages"), ".xml").stream())).toList();
  }
  private static List<String> existingIdentities(Predicate<String> belongsToFamily){
    return registrations().stream().map(LinuxAssociations::baseName).filter(belongsToFamily).distinct().sorted().toList();
  }
  private static List<Path> owned(Predicate<String> owner){
    var icons= listed(iconDir("mimetypes"), ".png").stream()
      .filter(f->baseName(f).matches(".+-[0-9a-f]+") && owner.test(baseName(f).replaceFirst("-[0-9a-f]+$", "")));
    var named= Stream.of(listed(home("applications"), ".desktop"), listed(home("mime/packages"), ".xml"), listed(iconDir("apps"), ".png"))
      .flatMap(List::stream).filter(f->owner.test(baseName(f)));
    return Stream.concat(icons, named).toList();
  }
  private static Db db(Predicate<String> belongsToFamily){
    var globs= new ArrayList<Glob>();
    var cleared= new HashSet<String>();
    var names= new HashSet<String>();
    var aliases= new HashMap<String,String>();
    var subclasses= new ArrayList<List<String>>();
    for (var dir: mimeDirs()){
      var keep= keep(dir, belongsToFamily);
      var here= fields(dir.resolve("globs2"), ":").filter(f->f.size() > 2 && keep.test(f.get(1)) && !cleared.contains(f.get(1))).toList();
      here.stream().filter(f->!f.get(2).equals("__NOGLOBS__"))
        .forEach(f->globs.add(new Glob(f.get(1), f.get(2), f.size() > 3 && List.of(f.get(3).split(",")).contains("cs"))));
      here.stream().filter(f->f.get(2).equals("__NOGLOBS__")).forEach(f->cleared.add(f.get(1)));
      fields(dir.resolve("types"), " ").filter(f->keep.test(f.getFirst())).forEach(f->names.add(f.getFirst()));
      fields(dir.resolve("aliases"), " ").filter(f->f.size() == 2 && keep.test(f.get(1))).forEach(f->aliases.putIfAbsent(f.get(0), f.get(1)));
      fields(dir.resolve("subclasses"), " ").filter(f->f.size() == 2 && keep.test(f.get(0))).forEach(subclasses::add);
    }
    var desktops= desktops().flatMap(f->opens(f).map(t->Map.entry(baseName(f), t)));
    var opened= Stream.concat(desktops, Xdg.choiceFiles().stream().flatMap(f->chosen(f).stream())).toList();
    return new Db(List.copyOf(globs), Set.copyOf(names), Map.copyOf(aliases), List.copyOf(subclasses), opened);
  }
  private static Predicate<String> keep(Path dir, Predicate<String> belongsToFamily){
    var packages= listed(dir.resolve("packages"), ".xml").stream().collect(Collectors.partitioningBy(f->belongsToFamily.test(baseName(f))));
    var family= packages.get(true).stream().flatMap(f->lines(f).stream()).filter(l->l.contains("<glob "))
      .map(declared::matcher).filter(Matcher::find).map(m->m.group(1)).collect(Collectors.toSet());
    if (!dir.equals(home("mime"))){ return t->!family.contains(t); }
    var others= packages.get(false).stream().map(f->String.join("\n", lines(f))).collect(Collectors.joining("\n"));
    return t->!family.contains(t) && (others.contains("\""+t+"\"") || others.contains("'"+t+"'"));
  }
  private static Stream<List<String>> fields(Path file, String separator){
    return lines(file).stream().filter(l->!l.isBlank() && !l.startsWith("#")).map(l->List.of(l.strip().split(separator)));
  }
  private static Stream<String> opens(Path desktopFile){
    return lines(desktopFile).stream().filter(l->l.startsWith("MimeType=")).findFirst().stream().flatMap(l->split(l.substring("MimeType=".length())));
  }
  private static List<Map.Entry<String,String>> chosen(Path file){
    var res= new ArrayList<Map.Entry<String,String>>();
    var chosen= false;
    for (var line: lines(file)){
      if (line.startsWith("[")){ chosen= line.startsWith("[Default Applications]") || line.startsWith("[Added Associations]"); continue; }
      var eq= line.indexOf('=');
      if (!chosen || eq < 0){ continue; }
      split(line.substring(eq+1)).forEach(n->res.add(Map.entry(n.replaceFirst("\\.desktop$", ""), line.substring(0,eq).strip())));
    }
    return Collections.unmodifiableList(res);
  }
  private static boolean writableForCreation(Path dir){
    return Stream.iterate(dir, Objects::nonNull, Path::getParent).filter(Files::exists).findFirst().filter(Files::isWritable).isPresent();
  }
  private static Map<Path,byte[]> wanted(String identity, Path command, List<Icon> extensions, Path programPng, Db db){
    var res= new LinkedHashMap<Path,byte[]>();
    var body= new StringBuilder();
    for (var icon: extensions){
      var bytes= desktopPng(icon.png());
      var name= identity+"-"+hash(bytes);
      res.put(iconDir("mimetypes").resolve(name+".png"), bytes);
      body.append(mimeTypes(identity, icon.extension(), name, db.known(icon.extension())));
    }
    var types= extensions.stream().flatMap(i->db.types(i.extension()).stream()).toList();
    res.put(home("applications").resolve(identity+".desktop"), utf8(desktopEntry(identity, command.toString(), windowClass(), types)));
    res.put(home("mime/packages").resolve(identity+".xml"), utf8("""
      <?xml version="1.0" encoding="UTF-8"?>
      <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
      %s</mime-info>
      """.formatted(body)));
    res.put(iconDir("apps").resolve(identity+".png"), desktopPng(programPng));
    return Collections.unmodifiableMap(res);
  }
  private static boolean alreadyMatches(List<String> existing, String identity, Map<Path,byte[]> wanted){
    if (!existing.stream().allMatch(identity::equals) || !Set.copyOf(owned(identity::equals)).equals(wanted.keySet())){ return false; }
    return wanted.entrySet().stream().allMatch(e->Arrays.equals(Fs.of(()->Files.readAllBytes(e.getKey())), e.getValue()));
  }
  private static void rebuild(Function<String,RuntimeException> halfDone){
    run.accept(List.of("update-mime-database", home("mime").toString()), halfDone);
    Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
      .forEach(d->run.accept(List.of("update-desktop-database", d.toString()), halfDone));
  }
  private static Path home(String folder){ return Xdg.dataHome().resolve(folder); }
  private static Path iconDir(String kind){ return home("icons/hicolor/"+iconSide+"x"+iconSide).resolve(kind); }
  private static String typeOf(String ext){ return "application/x-"+ext.substring(1); }
  private static byte[] desktopPng(Path png){
    return Ico.png(Ico.scaled(Objects.requireNonNull(Fs.of(()->ImageIO.read(png.toFile()))), iconSide));
  }
  private static byte[] utf8(String text){ return text.getBytes(StandardCharsets.UTF_8); }
  public static String hash(byte[] bytes){
    var h= 0xcbf29ce484222325L;
    for (var b: bytes){ h= (h ^ (b & 0xff))*0x100000001b3L; }
    return Long.toHexString(h);
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
  private static Stream<String> split(String types){ return Stream.of(types.split(";")).map(String::strip).filter(s->!s.isEmpty()); }
  private static List<Path> listed(Path dir, String suffix){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.of(()->{ try(var s= Files.list(dir)){
      return s.filter(p->p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p)).sorted().toList(); }});
  }
  private static List<String> lines(Path file){
    if (!Files.isRegularFile(file)){ return List.of(); }
    return Fs.of(()->Files.readAllLines(file));
  }
  private static String baseName(Path file){
    var name= file.getFileName().toString();
    return name.substring(0, name.lastIndexOf('.'));
  }
}
