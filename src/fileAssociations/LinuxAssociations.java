package fileAssociations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
  private static final String defaults= "[Default Applications]";
  private static final String added= "[Added Associations]";
  record Glob(String type, String pattern, boolean cs){
    boolean is(String ext){
      var regex= new StringBuilder();
      for (var c : (cs ? pattern : pattern.toLowerCase(Locale.ROOT)).replace("[!", "[^").toCharArray()){ regex.append(wildcard(c)); }
      return ("*"+ext).matches(regex.toString());
    }
    private static String wildcard(char c){
      return switch(c){
        case '*' -> ".*";
        case '?' -> ".";
        case '[', ']', '-', '^' -> String.valueOf(c);
        default -> Character.isLetterOrDigit(c) ? String.valueOf(c) : "\\"+c;
      };
    }
  }
  record Opener(String program, String type, String section){}
  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programPng,
      Function<String,RuntimeException> ambiguous,
      Function<List<String>,RuntimeException> userLocked,
      Function<Map<String,List<String>>,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(String.join("\n", existing)); }
    var globs= globs(belongsToFamily);
    var openers= openers().stream().filter(o->!belongsToFamily.test(o.program())).toList();
    var locked= extensions.stream().map(Icon::extension).filter(Icon::system)
      .filter(e->openers.stream().anyMatch(o->o.section().equals(defaults) && o.type().equals(typeOf(e)))).toList();
    if (!locked.isEmpty()){ throw userLocked.apply(locked); }
    var foreign= new LinkedHashMap<String,List<String>>();
    extensions.stream().map(Icon::extension).filter(e->!Icon.system(e)).forEach(e->foreign.put(e, held(e, globs, openers)));
    foreign.values().removeIf(List::isEmpty);
    if (!foreign.isEmpty()){ throw notOurs.apply(Collections.unmodifiableMap(foreign)); }
    var unwritable= registrations().stream().filter(f->existing.contains(baseName(f)) && !Files.isWritable(f));
    var targets= extensions.isEmpty() && existing.isEmpty() ? Stream.<Path>of()
      : Stream.of(home("applications"), home("mime/packages"), iconDir("mimetypes"), iconDir("apps")).filter(d->!writableForCreation(d));
    var refused= Stream.concat(unwritable, targets).map(Path::toString).toList();
    if (!refused.isEmpty()){ throw notWritable.apply(String.join("\n", refused)); }
    var wanted= extensions.isEmpty() ? Map.<Path,byte[]>of() : wanted(identity, command, extensions, programPng, globs);
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
  private static List<String> held(String ext, List<Glob> globs, List<Opener> openers){
    var programs= openers.stream().filter(o->o.type().equals(typeOf(ext)) && !(ext.equals(".fearless") && o.section().equals(added))).map(Opener::program);
    return Stream.concat(programs, globTypes(ext, globs).stream()).distinct().toList();
  }
  private static List<String> globTypes(String ext, List<Glob> globs){
    if (ext.equals(".fearless")){ return List.of(); }
    return globs.stream().filter(g->g.is(ext)).map(Glob::type).distinct().toList();
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
  private static List<Glob> globs(Predicate<String> belongsToFamily){
    var res= new ArrayList<Glob>();
    var cleared= new HashSet<String>();
    for (var dir : mimeDirs()){
      var keep= keep(dir, belongsToFamily);
      var here= fields(dir.resolve("globs2")).filter(f->f.size() > 2 && keep.test(f.get(1)) && !cleared.contains(f.get(1))).toList();
      here.stream().filter(f->!f.get(2).equals("__NOGLOBS__"))
        .forEach(f->res.add(new Glob(f.get(1), f.get(2), f.size() > 3 && List.of(f.get(3).split(",")).contains("cs"))));
      here.stream().filter(f->f.get(2).equals("__NOGLOBS__")).forEach(f->cleared.add(f.get(1)));
    }
    return List.copyOf(res);
  }
  private static List<Opener> openers(){
    var listing= desktops().flatMap(f->opens(f).map(t->new Opener(baseName(f), t, "[Desktop Entry]")));
    return Stream.concat(listing, Xdg.choiceFiles().stream().flatMap(f->chosen(f).stream())).toList();
  }
  private static Predicate<String> keep(Path dir, Predicate<String> belongsToFamily){
    var packages= listed(dir.resolve("packages"), ".xml").stream().collect(Collectors.partitioningBy(f->belongsToFamily.test(baseName(f))));
    var family= packages.get(true).stream().flatMap(f->lines(f).stream()).filter(l->l.contains("<glob "))
      .map(declared::matcher).filter(Matcher::find).map(m->m.group(1)).collect(Collectors.toUnmodifiableSet());
    if (!dir.equals(home("mime"))){ return t->!family.contains(t); }
    var others= packages.get(false).stream().map(f->String.join("\n", lines(f))).collect(Collectors.joining("\n"));
    return t->!family.contains(t) && (others.contains("\""+t+"\"") || others.contains("'"+t+"'"));
  }
  private static Stream<List<String>> fields(Path file){
    return lines(file).stream().filter(l->!l.isBlank() && !l.startsWith("#")).map(l->List.of(l.strip().split(":")));
  }
  private static Stream<String> opens(Path desktopFile){
    return lines(desktopFile).stream().filter(l->l.startsWith("MimeType=")).findFirst().stream().flatMap(l->split(l.substring("MimeType=".length())));
  }
  private static List<Opener> chosen(Path file){
    var res= new ArrayList<Opener>();
    var section= "";
    for (var line : lines(file)){
      if (line.startsWith("[")){ section= line.strip(); continue; }
      var eq= line.indexOf('=');
      if (eq < 0 || !List.of(defaults, added).contains(section)){ continue; }
      var at= section;
      split(line.substring(eq+1)).forEach(n->res.add(new Opener(n.replaceFirst("\\.desktop$", ""), line.substring(0,eq).strip(), at)));
    }
    return Collections.unmodifiableList(res);
  }
  private static boolean writableForCreation(Path dir){
    return Stream.iterate(dir, Objects::nonNull, Path::getParent).filter(Files::exists).findFirst().filter(Files::isWritable).isPresent();
  }
  private static Map<Path,byte[]> wanted(String identity, Path command, List<Icon> extensions, Path programPng, List<Glob> globs){
    var res= new LinkedHashMap<Path,byte[]>();
    var body= new StringBuilder();
    for (var icon : extensions){
      var bytes= desktopPng(icon.png());
      var name= identity+"-"+hash(bytes);
      res.put(iconDir("mimetypes").resolve(name+".png"), bytes);
      body.append(mimeType(identity, icon.extension(), name, globTypes(icon.extension(), globs)));
    }
    var types= extensions.stream().map(i->typeOf(i.extension())).toList();
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
    var exactlyWanted= existing.stream().allMatch(identity::equals) && Set.copyOf(owned(identity::equals)).equals(wanted.keySet());
    if (!exactlyWanted){ return false; }
    return wanted.entrySet().stream().allMatch(e->Arrays.equals(Fs.of(()->Files.readAllBytes(e.getKey())), e.getValue()));
  }
  private static void rebuild(Function<String,RuntimeException> halfDone){
    run.accept(List.of("update-mime-database", home("mime").toString()), halfDone);
    Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
      .forEach(d->run.accept(List.of("update-desktop-database", d.toString()), halfDone));
  }
  private static Path home(String folder){ return Xdg.dataHome().resolve(folder); }
  private static Path iconDir(String kind){ return home("icons/hicolor/"+iconSide+"x"+iconSide).resolve(kind); }
  public static String typeOf(String ext){ return "application/x-fearless"+(ext.equals(".fearless") ? "" : "-"+ext.substring(1)); }
  private static byte[] desktopPng(Path png){
    return Ico.png(Ico.scaled(Objects.requireNonNull(Fs.of(()->ImageIO.read(png.toFile()))), iconSide));
  }
  private static byte[] utf8(String text){ return text.getBytes(StandardCharsets.UTF_8); }
  public static String hash(byte[] bytes){
    var h= 0xcbf29ce484222325L;
    for (var b : bytes){ h= (h ^ (b & 0xff))*0x100000001b3L; }
    return Long.toHexString(h);
  }
  private static String mimeType(String identity, String ext, String icon, List<String> supertypes){
    var subclass= supertypes.stream().map(t->"<sub-class-of type=\"%s\"/>".formatted(t)).collect(Collectors.joining());
    return ("  <mime-type type=\"%s\"><comment>%s</comment>"
      +"<glob pattern=\"*%s\" weight=\"100\"/>%s<icon name=\"%s\"/></mime-type>\n")
      .formatted(typeOf(ext), identity, ext, subclass, icon);
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
