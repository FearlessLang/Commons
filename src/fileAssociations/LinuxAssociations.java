package fileAssociations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import tools.Fs;
import utils.Push;

public final class LinuxAssociations{
  private static final int iconSide= 256;
  static void reconcile(String identity, Predicate<String> belongsToFamily, Path command,
      List<Icon> extensions, Path programPng,
      Function<String,RuntimeException> ambiguous,
      Function<String,RuntimeException> notOurs,
      Function<String,RuntimeException> notWritable,
      Function<String,RuntimeException> halfDone){
    var existing= existingIdentities(belongsToFamily);
    if (existing.size() > 1){ throw ambiguous.apply(String.join("\n", existing)); }

    var foreign= new ArrayList<String>();
    for (var icon: extensions){
      for (var claimant: claimants(typeOf(icon.extension()))){
        if (!belongsToFamily.test(claimant)){ foreign.add(icon.extension()+" -> "+claimant); }
      }
    }
    if (!foreign.isEmpty()){ throw notOurs.apply(String.join("\n", foreign)); }

    var stale= existing.isEmpty() ? Optional.<String>empty() : Optional.of(existing.getFirst());
    var unwritable= new ArrayList<Path>();
    stale.ifPresent(s->unwritable.addAll(filesOf(s).stream().filter(f->!Files.isWritable(f)).toList()));
    if (!extensions.isEmpty() || stale.isPresent()){ unwritable.addAll(targetsNotWritable()); }
    if (!unwritable.isEmpty()){
      throw notWritable.apply(String.join("\n", unwritable.stream().map(Path::toString).toList()));
    }

    if (alreadyMatches(stale, identity, extensions, programPng)){ return; }

    stale.ifPresent(LinuxAssociations::eradicate);
    if (!extensions.isEmpty()){ create(identity, command, extensions, programPng); }
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
  private static boolean alreadyMatches(Optional<String> stale, String identity, List<Icon> extensions, Path programPng){
    if (extensions.isEmpty()){ return stale.isEmpty(); }
    if (stale.isEmpty() || !stale.get().equals(identity)){ return false; }
    var declared= readOwned(lines(ourPackage(identity)));
    if (declared.size() != extensions.size()){ return false; }
    for (var icon: extensions){
      if (!(identity+"-"+hash(desktopPng(icon.png()))).equals(declared.get(icon.extension()))){ return false; }
    }
    if (!lines(ourDesktop(identity)).contains("StartupWMClass="+windowClass())){ return false; }
    return programIconBytes(identity).map(b->Arrays.equals(b, desktopPng(programPng))).orElse(false);
  }
  private static void eradicate(String identity){
    Fs.ofV(()->Files.deleteIfExists(ourDesktop(identity)));
    Fs.ofV(()->Files.deleteIfExists(ourPackage(identity)));
  }
  private static void create(String identity, Path command, List<Icon> extensions, Path programPng){
    var icons= new LinkedHashMap<String,String>();
    extensions.forEach(icon->icons.put(icon.extension(), install(identity, icon.png())));
    Fs.writeUtf8(ourDesktop(identity), desktopEntry(identity, command.toString(), windowClass(), types(icons)));
    Fs.writeUtf8(ourPackage(identity), mimePackage(identity, icons));
    put(desktopPng(programPng), "apps", identity);
  }
  private static void rebuild(Function<String,RuntimeException> halfDone){
    Shell.req(List.of("update-mime-database", Xdg.dataHome().resolve("mime").toString()), halfDone);
    Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
      .forEach(d->Shell.req(List.of("update-desktop-database", d.toString()), halfDone));
  }
  private static Path ourDesktop(String identity){ return Xdg.dataHome().resolve("applications").resolve(identity+".desktop"); }
  private static Path ourPackage(String identity){ return Xdg.dataHome().resolve("mime").resolve("packages").resolve(identity+".xml"); }
  private static List<String> types(Map<String,String> icons){ return icons.keySet().stream().map(LinuxAssociations::typeOf).toList(); }
  public static String typeOf(String ext){ return "application/x-"+ext.substring(1); }
  private static String install(String identity, Path png){
    var bytes= desktopPng(png);
    return put(bytes, "mimetypes", identity+"-"+hash(bytes));
  }
  private static byte[] desktopPng(Path png){
    return Ico.png(Ico.scaled(Objects.requireNonNull(Fs.of(()->ImageIO.read(png.toFile()))), iconSide));
  }
  private static Path iconDir(){ return Xdg.dataHome().resolve("icons").resolve("hicolor").resolve(iconSide+"x"+iconSide); }
  private static String put(byte[] bytes, String context, String icon){
    var dest= iconDir().resolve(context).resolve(icon+".png");
    Fs.ensureDir(dest.getParent());
    Fs.ofV(()->Files.write(dest, bytes));
    return icon;
  }
  private static Optional<byte[]> programIconBytes(String identity){
    return Optional.of(iconDir().resolve("apps").resolve(identity+".png")).filter(Files::isRegularFile).map(LinuxAssociations::bytesOf);
  }
  private static byte[] bytesOf(Path file){ return Fs.of(()->Files.readAllBytes(file)); }
  public static String hash(byte[] bytes){
    var h= 0xcbf29ce484222325L;
    for (var b: bytes){ h= (h ^ (b & 0xff))*0x100000001b3L; }
    return Long.toHexString(h);
  }
  public static Map<String,String> readOwned(List<String> xml){
    var res= new LinkedHashMap<String,String>();
    for (var line: xml){
      var ext= between(line, "<glob pattern=\"*");
      var icon= between(line, "<icon name=\"");
      if (ext.isPresent() && icon.isPresent()){ res.put(ext.get(), icon.get()); }
    }
    return Collections.unmodifiableMap(res);//keep insertion order, unlike Map.copyOf
  }
  private static Optional<String> between(String line, String open){
    var i= line.indexOf(open);
    if (i < 0){ return Optional.empty(); }
    var rest= line.substring(i+open.length());
    var end= rest.indexOf('"');
    return end < 0 ? Optional.empty() : Optional.of(rest.substring(0, end));
  }
  public static String mimePackage(String identity, Map<String,String> icons){
    var body= new StringBuilder();
    icons.forEach((ext,icon)->body.append(mimeType(identity, ext, icon)));
    return """
      <?xml version="1.0" encoding="UTF-8"?>
      <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
      %s</mime-info>
      """.formatted(body);
  }
  private static String mimeType(String identity, String ext, String icon){
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
      """.formatted(identity, command, identity, windowClass, String.join(";", types));
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
