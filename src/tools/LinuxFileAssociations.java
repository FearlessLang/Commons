package tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

public final class LinuxFileAssociations implements FileAssociations{
  private final String name;
  private final String command;
  private final Path png;
  private final Function<String,RuntimeException> notOurs;
  private final Function<String,RuntimeException> stepFailed;
  private final Function<String,RuntimeException> notTaken;
  private final Function<String,RuntimeException> halfDone;
  private final LinkedHashMap<String,String> icons= new LinkedHashMap<>();
  LinuxFileAssociations(String name, String command, Path png,
      Function<String,RuntimeException> notOurs, Function<String,RuntimeException> stepFailed,
      Function<String,RuntimeException> notTaken, Function<String,RuntimeException> halfDone,
      Function<String,RuntimeException> halfThere){
    this.name= name; this.command= command; this.png= png;
    this.notOurs= notOurs; this.stepFailed= stepFailed; this.notTaken= notTaken; this.halfDone= halfDone;
    var pkg= Files.isRegularFile(ourPackage());
    if (pkg != Files.isRegularFile(ourDesktop())){ throw halfThere.apply(ourPackage()+"\n"+ourDesktop()); }
    if (!pkg){ write(); return; }
    icons.putAll(readOwned(lines(ourPackage())));
    var orphan= icons.values().stream().distinct().filter(i->iconFile(i).isEmpty()).toList();
    if (!orphan.isEmpty()){ throw halfThere.apply(missingIcons(orphan)); }
    var resolved= resolvedMap();
    var lost= icons.keySet().stream().filter(e->!mine().equals(resolved.get(typeOf(e)))).toList();
    if (!lost.isEmpty()){ acquire(lost); }
  }
  public List<String> owned(){ return List.copyOf(icons.keySet()); }
  public void acquire(List<String> exts){
    var globs= globs();
    var fresh= exts.stream().filter(e->!icons.containsKey(e)).toList();
    fresh.forEach(e->reqFree(globs, e));
    var types= new LinkedHashSet<>(exts.stream().map(LinuxFileAssociations::typeOf).toList());
    var doomed= doomed(types);
    var lists= choiceFilesNaming(types);
    var was= new LinkedHashMap<>(icons);
    var undo= new Undo();
    try {
      doomed.forEach(undo::keep);
      lists.forEach(undo::keep);
      undo.keep(ourPackage());
      undo.keep(ourDesktop());
      fresh.forEach(e->icons.put(e, install(png)));
      doomed.forEach(f->Fs.ofV(()->Files.deleteIfExists(f)));
      lists.forEach(f->Fs.writeUtf8(f, withoutLinesFor(f, types)));
      write();
    }
    catch(RuntimeException e){ back(was, undo); throw e; }
    var resolved= resolvedMap();
    var wrong= types.stream().filter(t->!mine().equals(resolved.get(t))).findFirst();
    if (wrong.isEmpty()){ return; }
    var current= resolved.getOrDefault(wrong.get(), "nothing at all");
    back(was, undo);
    throw notTaken.apply(current);
  }
  public void release(List<String> exts){ edit(()->exts.forEach(icons::remove)); }
  public void setIcon(List<Icon> wanted){ edit(()->wanted.forEach(this::reIcon)); }
  ///Only our own two files change here, so what was written is all there is to put back.
  private void edit(Runnable body){
    var was= new LinkedHashMap<>(icons);
    var undo= new Undo();
    undo.keep(ourPackage());
    undo.keep(ourDesktop());
    try { body.run(); write(); }
    catch(RuntimeException e){ back(was, undo); throw e; }
    sweep(was);
  }
  public void setProgramIcon(Path ico, Path png){ put(Fs.of(()->Files.readAllBytes(png)), "apps", name); }
  private void reIcon(Icon i){
    assert icons.containsKey(i.extension());
    icons.put(i.extension(), install(i.png()));
  }
  private void back(Map<String,String> was, Undo undo){
    icons.clear();
    icons.putAll(was);
    undo.restore(halfDone);
    rebuild();
  }
  ///An icon file nobody names any more is ours to take away.
  private void sweep(Map<String,String> was){
    was.values().stream().distinct().filter(i->!icons.containsValue(i))
      .forEach(i->iconFile(i).ifPresent(f->Fs.ofV(()->Files.deleteIfExists(f))));
  }
  private void reqFree(Map<String,String> globs, String ext){
    var known= globs.get("*"+ext.toLowerCase(Locale.ROOT));
    if (known == null || known.equals(typeOf(ext))){ return; }
    throw notOurs.apply(ext+"\nThis kind of file is already declared as: "+known);
  }
  private void write(){
    Fs.writeUtf8(ourPackage(), mimePackage(name, icons));
    Fs.writeUtf8(ourDesktop(), desktopEntry(name, command, types()));
    rebuild();
  }
  private void rebuild(){
    Shell.req(List.of("update-mime-database", Xdg.dataHome().resolve("mime").toString()), stepFailed);
    Xdg.appDirs().stream().filter(Files::isDirectory).filter(Files::isWritable)
      .forEach(d->Shell.req(List.of("update-desktop-database", d.toString()), stepFailed));
  }
  private List<String> types(){ return icons.keySet().stream().map(LinuxFileAssociations::typeOf).toList(); }
  private String mine(){ return name+".desktop"; }
  public static String typeOf(String ext){ return "application/x-"+ext.substring(1); }
  ///The name in the declaration is looked for by file name under every icon size, so the
  ///picture is copied in under a name of its own content and shared by every kind naming it.
  private String install(Path src){
    var bytes= Fs.of(()->Files.readAllBytes(src));
    return put(bytes, "mimetypes", name+"-"+hash(bytes));
  }
  private static String put(byte[] bytes, String context, String icon){
    var side= side(bytes);
    var dest= Xdg.dataHome().resolve("icons").resolve("hicolor")
      .resolve(side+"x"+side).resolve(context).resolve(icon+".png");
    Fs.ensureDir(dest.getParent());
    Fs.ofV(()->Files.write(dest, bytes));
    return icon;
  }
  private static Optional<Path> iconFile(String icon){
    var hicolor= Xdg.dataHome().resolve("icons").resolve("hicolor");
    if (!Files.isDirectory(hicolor)){ return Optional.empty(); }
    return Fs.of(()->{ try(var s= Files.list(hicolor)){
      return s.map(d->d.resolve("mimetypes").resolve(icon+".png")).filter(Files::isRegularFile).findFirst(); }});
  }
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
  public static LinkedHashMap<String,String> readOwned(List<String> xml){
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
  ///Every kind we declare is ours alone, so it is declared outright and at the weight
  ///that settles which declaration a file name answers to.
  public static String mimePackage(String name, Map<String,String> icons){
    var body= new StringBuilder();
    icons.forEach((ext,icon)->body.append(mimeType(name, ext, icon)));
    return """
      <?xml version="1.0" encoding="UTF-8"?>
      <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
      %s</mime-info>
      """.formatted(body);
  }
  private static String mimeType(String name, String ext, String icon){
    return ("  <mime-type type=\"%s\"><comment>%s</comment>"
      +"<glob pattern=\"*%s\" weight=\"100\"/><icon name=\"%s\"/></mime-type>\n")
      .formatted(typeOf(ext), name, ext, icon);
  }
  public static String desktopEntry(String name, String command, List<String> types){
    return """
      [Desktop Entry]
      Type=Application
      Name=%s
      Exec=%s %%f
      Icon=%s
      Terminal=false
      MimeType=%s;
      """.formatted(name, command, name, String.join(";", types));
  }
  private static Map<String,String> globs(){
    var res= new LinkedHashMap<String,String>();
    for (var dir: mimeDirs()){
      for (var line: lines(dir.resolve("globs2"))){
        var parts= line.split(":", 3);
        if (parts.length != 3){ continue; }
        res.putIfAbsent(parts[2].toLowerCase(Locale.ROOT), parts[1]);
      }
    }
    return res;
  }
  private static List<Path> mimeDirs(){
    var res= new ArrayList<Path>();
    res.add(Xdg.dataHome().resolve("mime"));
    Xdg.dataDirs().forEach(d->res.add(d.resolve("mime")));
    return res;
  }
  ///Every program offering one of these kinds must go, or we are not the one the desktop picks.
  ///A program offering anything else as well is not ours to delete.
  private List<Path> doomed(Set<String> types){
    var res= new ArrayList<Path>();
    for (var dir: Xdg.appDirs()){
      for (var file: desktopFiles(dir)){
        var claimed= claimedTypes(file);
        if (claimed.stream().noneMatch(types::contains)){ continue; }
        if (file.getFileName().toString().equals(mine())){ continue; }
        if (!types.containsAll(claimed)){ throw notOurs.apply(alsoOpens(file, claimed, types)); }
        if (!Files.isWritable(file.getParent())){ throw notOurs.apply(cannotChange(file)); }
        res.add(file);
      }
    }
    return res;
  }
  private List<Path> choiceFilesNaming(Set<String> types){
    var res= new ArrayList<Path>();
    for (var file: Xdg.choiceFiles()){
      if (!Files.isRegularFile(file) || namesNone(file, types)){ continue; }
      if (!Files.isWritable(file)){ throw notOurs.apply(cannotChange(file)); }
      res.add(file);
    }
    return res;
  }
  private static boolean namesNone(Path file, Set<String> types){
    return lines(file).stream().noneMatch(l->keyOf(l).filter(types::contains).isPresent());
  }
  private static Optional<String> keyOf(String line){
    var eq= line.indexOf('=');
    return eq < 0 ? Optional.empty() : Optional.of(line.substring(0, eq).strip());
  }
  private static String withoutLinesFor(Path file, Set<String> types){
    var kept= lines(file).stream().filter(l->keyOf(l).filter(types::contains).isEmpty()).toList();
    return kept.isEmpty() ? "" : String.join("\n", kept)+"\n";
  }
  ///The answer the desktop would give for every kind at once, read the way the desktop reads
  ///it: a chosen answer in the nearest list that names one, otherwise the first program
  ///offering the kind. Read once, because there are thousands of kinds to answer for.
  static Map<String,String> resolvedMap(){
    var known= existingDesktops();
    var res= new LinkedHashMap<String,String>();
    Xdg.choiceFiles().forEach(f->chosenIn(f, known, res));
    Xdg.appDirs().forEach(d->offeredIn(d.resolve("mimeinfo.cache"), known, res));
    return res;
  }
  private static Set<String> existingDesktops(){
    var res= new HashSet<String>();
    Xdg.appDirs().forEach(d->desktopFiles(d).forEach(f->res.add(f.getFileName().toString())));
    return res;
  }
  private static void chosenIn(Path file, Set<String> known, Map<String,String> res){
    var inDefaults= false;
    for (var line: lines(file)){
      if (line.startsWith("[")){ inDefaults= line.startsWith("[Default Applications]"); continue; }
      if (!inDefaults){ continue; }
      record(line, known, res);
    }
  }
  private static void offeredIn(Path cache, Set<String> known, Map<String,String> res){
    lines(cache).forEach(l->record(l, known, res));
  }
  private static void record(String line, Set<String> known, Map<String,String> res){
    var key= keyOf(line);
    if (key.isEmpty() || res.containsKey(key.get())){ return; }
    firstExisting(line.substring(line.indexOf('=')+1), known).ifPresent(v->res.put(key.get(), v));
  }
  private static Optional<String> firstExisting(String names, Set<String> known){
    for (var n: names.split(";")){
      var one= n.strip();
      if (known.contains(one)){ return Optional.of(one); }
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
  private Path ourPackage(){ return Xdg.dataHome().resolve("mime").resolve("packages").resolve(name+".xml"); }
  private Path ourDesktop(){ return Xdg.dataHome().resolve("applications").resolve(mine()); }
  private static String alsoOpens(Path file, List<String> claimed, Set<String> types){
    var others= claimed.stream().filter(t->!types.contains(t)).toList();
    return file+"\nIt also opens: "+String.join(", ", others);
  }
  private static String cannotChange(Path file){ return file+"\nThis file is not yours to change."; }
  private static String missingIcons(List<String> orphan){
    return "These pictures are named but not there: "+String.join(", ", orphan);
  }
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
