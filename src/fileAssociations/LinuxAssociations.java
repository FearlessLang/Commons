package fileAssociations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import tools.Fs;

public final class LinuxAssociations{
  private LinuxAssociations(){}

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
    for (var dir: Xdg.appDirs()){ desktopFiles(dir).forEach(f->res.add(baseName(f,".desktop"))); }
    for (var dir: mimeDirs()){ mimePackages(dir).forEach(f->res.add(baseName(f,".xml"))); }
    return res.stream().filter(belongsToFamily).sorted().toList();
  }
  private static Set<String> claimants(String type){
    var res= new LinkedHashSet<String>();
    for (var dir: Xdg.appDirs()){
      for (var file: desktopFiles(dir)){
        if (claimedTypes(file).contains(type)){ res.add(baseName(file,".desktop")); }
      }
    }
    for (var file: Xdg.choiceFiles()){
      chosenFor(file, type).forEach(name->res.add(name.endsWith(".desktop") ? name.substring(0,name.length()-8) : name));
    }
    return res;
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
  private static List<Path> filesOf(String identityName){
    var res= new ArrayList<Path>();
    for (var dir: Xdg.appDirs()){
      var f= dir.resolve(identityName+".desktop");
      if (Files.isRegularFile(f)){ res.add(f); }
    }
    for (var dir: mimeDirs()){
      var f= dir.resolve("packages").resolve(identityName+".xml");
      if (Files.isRegularFile(f)){ res.add(f); }
    }
    return res;
  }
  private static List<Path> targetsNotWritable(){
    var res= new ArrayList<Path>();
    var desktopDir= Xdg.dataHome().resolve("applications");
    if (!writableForCreation(desktopDir)){ res.add(desktopDir); }
    var mimeDir= Xdg.dataHome().resolve("mime").resolve("packages");
    if (!writableForCreation(mimeDir)){ res.add(mimeDir); }
    return res;
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
      if (!(identity+"-"+hash(bytesOf(icon.png()))).equals(declared.get(icon.extension()))){ return false; }
    }
    return programIconBytes(identity).map(b->java.util.Arrays.equals(b, bytesOf(programPng))).orElse(false);
  }
  private static void eradicate(String identityName){
    Fs.ofV(()->Files.deleteIfExists(ourDesktop(identityName)));
    Fs.ofV(()->Files.deleteIfExists(ourPackage(identityName)));
  }
  private static void create(String identity, Path command, List<Icon> extensions, Path programPng){
    var icons= new LinkedHashMap<String,String>();
    extensions.forEach(icon->icons.put(icon.extension(), install(identity, icon.png())));
    Fs.writeUtf8(ourDesktop(identity), desktopEntry(identity, command.toString(), types(icons)));
    Fs.writeUtf8(ourPackage(identity), mimePackage(identity, icons));
    put(bytesOf(programPng), "apps", identity);
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
    var bytes= bytesOf(png);
    return put(bytes, "mimetypes", identity+"-"+hash(bytes));
  }
  private static String put(byte[] bytes, String context, String icon){
    var side= side(bytes);
    var dest= Xdg.dataHome().resolve("icons").resolve("hicolor")
      .resolve(side+"x"+side).resolve(context).resolve(icon+".png");
    Fs.ensureDir(dest.getParent());
    Fs.ofV(()->Files.write(dest, bytes));
    return icon;
  }
  private static Optional<byte[]> programIconBytes(String identity){
    var hicolor= Xdg.dataHome().resolve("icons").resolve("hicolor");
    if (!Files.isDirectory(hicolor)){ return Optional.empty(); }
    return Fs.of(()->{ try(var s= Files.list(hicolor)){
      return s.map(d->d.resolve("apps").resolve(identity+".png")).filter(Files::isRegularFile)
        .findFirst().map(LinuxAssociations::bytesOf); }});
  }
  private static byte[] bytesOf(Path file){ return Fs.of(()->Files.readAllBytes(file)); }
  public static int side(byte[] png){
    var w= intAt(png, 16);
    assert w == intAt(png, 20);
    return w;
  }
  private static int intAt(byte[] b, int i){
    return ((b[i]&0xff)<<24)|((b[i+1]&0xff)<<16)|((b[i+2]&0xff)<<8)|(b[i+3]&0xff);
  }
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
    return res;
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
  public static String desktopEntry(String identity, String command, List<String> types){
    return """
      [Desktop Entry]
      Type=Application
      Name=%s
      Exec=%s %%f
      Icon=%s
      Terminal=false
      MimeType=%s;
      """.formatted(identity, command, identity, String.join(";", types));
  }
  private static List<Path> mimeDirs(){
    var res= new ArrayList<Path>();
    res.add(Xdg.dataHome().resolve("mime"));
    Xdg.dataDirs().forEach(d->res.add(d.resolve("mime")));
    return res;
  }
  private static List<Path> mimePackages(Path mimeDir){
    var dir= mimeDir.resolve("packages");
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.of(()->{ try(var s= Files.list(dir)){
      return s.filter(p->p.getFileName().toString().endsWith(".xml")).sorted().toList(); }});
  }
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
  private static List<Path> desktopFiles(Path dir){
    if (!Files.isDirectory(dir)){ return List.of(); }
    return Fs.of(()->{ try(var s= Files.list(dir)){
      return s.filter(p->p.getFileName().toString().endsWith(".desktop")).sorted().toList(); }});
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
