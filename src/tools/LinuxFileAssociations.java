package tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public final class LinuxFileAssociations implements FileAssociations{
  LinuxFileAssociations(){}
  public void associate(String name, List<String> exts, Path iconWin, Path iconLinux, String command,
      Function<String,RuntimeException> notOurs, Function<String,RuntimeException> stepFailed,
      Function<String,RuntimeException> notTaken, Function<String,RuntimeException> halfDone){
    var types= exts.stream().map(LinuxFileAssociations::typeOf).toList();
    var mine= name+".desktop";
    var doomed= doomed(types, mine, notOurs);
    var lists= choiceFilesNaming(types, notOurs);
    var undo= new Undo();
    try {
      doomed.forEach(undo::keep);
      lists.forEach(undo::keep);
      undo.keep(ourPackage(name));
      undo.keep(ourDesktop(mine));
      doomed.forEach(f->Fs.ofV(()->Files.deleteIfExists(f)));
      lists.forEach(f->Fs.writeUtf8(f, withoutLinesFor(f, types)));
      Fs.writeUtf8(ourPackage(name), mimePackage(name, exts, types));
      Fs.writeUtf8(ourDesktop(mine), desktopEntry(name, iconLinux, command, types));
      Shell.req(List.of("update-mime-database", Xdg.dataHome().resolve("mime").toString()), stepFailed);
      Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
        .forEach(d->Shell.req(List.of("update-desktop-database", d.toString()), stepFailed));
    }
    catch(RuntimeException e){ undo.restore(halfDone); throw e; }
    var wrong= types.stream().filter(t->!mine.equals(resolved(t).orElse(""))).findFirst();
    if (wrong.isEmpty()){ return; }
    var was= resolved(wrong.get()).orElse("nothing at all");
    undo.restore(halfDone);
    throw notTaken.apply(was);
  }
  public boolean taken(String name, List<String> exts){
    return exts.stream().allMatch(e->(name+".desktop").equals(resolved(typeOf(e)).orElse("")));
  }
  public static String typeOf(String ext){ return knownType(ext).orElse("application/x-"+ext.substring(1)); }
  static Optional<String> knownType(String ext){
    var pattern= "*"+ext.toLowerCase(Locale.ROOT);
    var best= -1;
    String res= null;
    for (var dir: mimeDirs()){
      for (var line: lines(dir.resolve("globs2"))){
        var parts= line.split(":", 3);
        if (parts.length != 3 || !parts[2].toLowerCase(Locale.ROOT).equals(pattern)){ continue; }
        var w= weight(parts[0]);
        if (w <= best){ continue; }
        best= w;
        res= parts[1];
      }
    }
    return Optional.ofNullable(res);
  }
  private static int weight(String s){
    try { return Integer.parseInt(s.strip()); }
    catch(NumberFormatException e){ return -1; }
  }
  private static List<Path> mimeDirs(){
    var res= new ArrayList<Path>();
    res.add(Xdg.dataHome().resolve("mime"));
    Xdg.dataDirs().forEach(d->res.add(d.resolve("mime")));
    return res;
  }
  ///Every program offering one of these kinds must go, or we are not the one the desktop picks.
  ///A program offering anything else as well is not ours to delete.
  private static List<Path> doomed(List<String> types, String mine, Function<String,RuntimeException> notOurs){
    var res= new ArrayList<Path>();
    for (var dir: Xdg.appDirs()){
      for (var file: desktopFiles(dir)){
        var claimed= claimedTypes(file);
        if (claimed.stream().noneMatch(types::contains)){ continue; }
        if (file.getFileName().toString().equals(mine)){ continue; }
        if (!types.containsAll(claimed)){ throw notOurs.apply(alsoOpens(file, claimed, types)); }
        if (!Files.isWritable(file)){ throw notOurs.apply(cannotChange(file)); }
        res.add(file);
      }
    }
    return res;
  }
  private static List<Path> choiceFilesNaming(List<String> types, Function<String,RuntimeException> notOurs){
    var res= new ArrayList<Path>();
    for (var file: Xdg.choiceFiles()){
      if (!Files.isRegularFile(file) || namesNone(file, types)){ continue; }
      if (!Files.isWritable(file)){ throw notOurs.apply(cannotChange(file)); }
      res.add(file);
    }
    return res;
  }
  private static boolean namesNone(Path file, List<String> types){
    return lines(file).stream().noneMatch(l->keyOf(l).filter(types::contains).isPresent());
  }
  private static Optional<String> keyOf(String line){
    var eq= line.indexOf('=');
    return eq < 0 ? Optional.empty() : Optional.of(line.substring(0, eq).strip());
  }
  private static String withoutLinesFor(Path file, List<String> types){
    var kept= lines(file).stream().filter(l->keyOf(l).filter(types::contains).isEmpty()).toList();
    return kept.isEmpty() ? "" : String.join("\n", kept)+"\n";
  }
  ///The answer the desktop would give, read the way the desktop reads it: a chosen
  ///answer in the nearest list that names one, otherwise the first program offering the kind.
  static Optional<String> resolved(String type){
    for (var file: Xdg.choiceFiles()){
      var chosen= chosenIn(file, type);
      if (chosen.isPresent()){ return chosen; }
    }
    for (var dir: Xdg.appDirs()){
      var offered= firstNamed(dir.resolve("mimeinfo.cache"), type);
      if (offered.isPresent()){ return offered; }
    }
    return Optional.empty();
  }
  private static Optional<String> chosenIn(Path file, String type){
    var inDefaults= false;
    for (var line: lines(file)){
      if (line.startsWith("[")){ inDefaults= line.startsWith("[Default Applications]"); continue; }
      if (!inDefaults || keyOf(line).filter(type::equals).isEmpty()){ continue; }
      return firstExisting(line.substring(line.indexOf('=')+1));
    }
    return Optional.empty();
  }
  private static Optional<String> firstNamed(Path cache, String type){
    for (var line: lines(cache)){
      if (keyOf(line).filter(type::equals).isEmpty()){ continue; }
      return firstExisting(line.substring(line.indexOf('=')+1));
    }
    return Optional.empty();
  }
  private static Optional<String> firstExisting(String names){
    for (var n: names.split(";")){
      var name= n.strip();
      if (name.isEmpty()){ continue; }
      if (Xdg.appDirs().stream().anyMatch(d->Files.isRegularFile(d.resolve(name)))){ return Optional.of(name); }
    }
    return Optional.empty();
  }
  static List<String> claimedTypes(Path desktopFile){
    for (var line: lines(desktopFile)){
      if (!line.startsWith("MimeType=")){ continue; }
      return List.of(line.substring("MimeType=".length()).split(";")).stream()
        .map(String::strip).filter(s->!s.isEmpty()).toList();
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
  private static Path ourPackage(String name){ return Xdg.dataHome().resolve("mime").resolve("packages").resolve(name+".xml"); }
  private static Path ourDesktop(String mine){ return Xdg.dataHome().resolve("applications").resolve(mine); }
  public static String desktopEntry(String name, Path icon, String command, List<String> types){
    return """
      [Desktop Entry]
      Type=Application
      Name=%s
      Exec=%s %%f
      Icon=%s
      Terminal=false
      MimeType=%s;
      """.formatted(name, command, icon, String.join(";", types));
  }
  ///Only the kinds nobody declared yet: taking over an existing kind must not redefine it.
  public static String mimePackage(String name, List<String> exts, List<String> types){
    var body= new StringBuilder();
    for (var i= 0; i < exts.size(); i++){
      if (knownType(exts.get(i)).isPresent()){ continue; }
      body.append("""
          <mime-type type="%s">
            <comment>%s</comment>
            <glob pattern="*%s"/>
          </mime-type>
        """.formatted(types.get(i), name, exts.get(i)));
    }
    return """
      <?xml version="1.0" encoding="UTF-8"?>
      <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
      %s</mime-info>
      """.formatted(body);
  }
  private static String alsoOpens(Path file, List<String> claimed, List<String> types){
    var others= claimed.stream().filter(t->!types.contains(t)).toList();
    return file+"\nIt also opens: "+String.join(", ", others);
  }
  private static String cannotChange(Path file){ return file+"\nThis file is not yours to change."; }
}
final class Undo{
  private final LinkedHashMap<Path,byte[]> before= new LinkedHashMap<>();
  void keep(Path p){ before.computeIfAbsent(p, q->Files.isRegularFile(q) ? Fs.of(()->Files.readAllBytes(q)) : null); }
  void restore(Function<String,RuntimeException> halfDone){
    for (var e: before.entrySet()){
      try { put(e.getKey(), e.getValue()); }
      catch(RuntimeException t){ throw halfDone.apply(e.getKey()+"\n"+t); }
    }
  }
  private static void put(Path p, byte[] was) {
    if (was == null){ Fs.ofV(()->Files.deleteIfExists(p)); return; }
    Fs.ensureDir(p.getParent());
    Fs.ofV(()->Files.write(p, was));
  }
}
