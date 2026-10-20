package tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import offensiveUtils.Require;
import utils.Bug;
import utils.Join;

public final class Fs{
  // ASCII whitelist
  public static final String allowed=
    "0123456789" +
    "abcdefghijklmnopqrstuvwxyz" +
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
    "+-*/=<>,.;:()[]{}" +
    "`'\"!?@#$%^&_|~\\" +
    " \n";
  public static final int maxExtSeg= 16;
  public static boolean isExtSegChar(char c){ return ('a'<=c && c<='z') || ('0'<=c && c<='9'); }
  public static boolean isExtSeg(String s){
    return !s.isEmpty() && s.length()<=maxExtSeg && s.chars().allMatch(c -> isExtSegChar((char)c));
  }
  public static Function<Path,RuntimeException> onSymbolicLink= p -> new IllegalArgumentException(
    "\""+p+"\" is a symbolic link; Fs does not read, write, copy or delete through symbolic links: replace it with the file or folder it points to.");
  private static Path noLink(Path p){
    if (Files.isSymbolicLink(p)){ throw onSymbolicLink.apply(p); }
    return p;
  }
  public static void ensureDir(Path p){ ofV(() -> Files.createDirectories(noLink(p))); }
  public static void cleanDirContents(Path p){
    reqDir(p, "cleaning");
    var xs= walk(p, s -> s
      .filter(x -> !x.equals(p))
      .sorted(Comparator.reverseOrder())
      .toList()
    );
    xs.forEach(x -> ofV(() -> forceDelete(x)));
  }
  private static void forceDelete(Path p) throws IOException{
    if (isWindows()){ Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS); }
    for (int attempt= 0; ; attempt += 1){
      try{ Files.deleteIfExists(p); return; }
      catch (AccessDeniedException e){
        if (!isWindows() || attempt>=9){ throw e; }
        if (attempt == 0){ System.gc(); }
        sleepBriefly();
      }
    }
  }
  private static void sleepBriefly(){
    try{ Thread.sleep(25); }
    catch (InterruptedException _){ Thread.currentThread().interrupt(); }
  }
  public static void writeUtf8(Path file, String content){
    ensureDir(file.getParent());
    ofV(() -> Files.writeString(noLink(file), content));
  }
  public static String readUtf8(Path file){ return of(() -> Files.readString(noLink(file))); }
  public static void copyTree(Path from, Path to){
    var srcs= walk(from, Stream::toList);
    srcs.forEach(src -> ofV(() -> copyOne(from, noLink(to), src)));
  }
  private static void copyOne(Path fromRoot, Path toRoot, Path src) throws IOException{
    var rel= fromRoot.relativize(src);
    var dst= noLink(toRoot.resolve(rel));
    if (Files.isDirectory(src)){ Files.createDirectories(dst); return; }
    Files.createDirectories(dst.getParent());
    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }
  public static long lastModified(Path file){
    try{ return Files.getLastModifiedTime(noLink(file)).toMillis(); }
    catch (NoSuchFileException _){ return -1; }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  // Writes (overwriting if needed) and guarantees mtime > minExclusiveMillis. Returns the actual mtime.
  public static long writeUtf8(Path file, String content, long minExclusiveMillis){
    for (;;){
      writeUtf8(file, content);
      var m= lastModified(file);
      if (m > minExclusiveMillis){ return m; }
      try{ Thread.sleep(10); }
      catch (InterruptedException e){ Thread.currentThread().interrupt(); throw Bug.of(e); }
    }
  }
  public static void reqDir(Path p, String what){ Require.check(Files.isDirectory(noLink(p)), "Expected a directory for "+what+": "+p); }
  public static void cleanDir(Path p){
    if (!Files.exists(noLink(p))){ ensureDir(p); return; }
    cleanDirContents(p);
  }
  public static void rmTree(Path p){
    if (Files.isDirectory(noLink(p))){ cleanDirContents(p); }
    ofV(() -> Files.deleteIfExists(p));
  }
  public static void copyFresh(Path from, Path to){
    rmTree(to);
    copyTree(from, to);
  }
  public static String runTool(String tool, List<String> args){
    var tp= ToolProvider.findFirst(tool).orElseThrow();
    var baos= new ByteArrayOutputStream();
    var ps= new PrintStream(baos, true, StandardCharsets.UTF_8);
    int rc= tp.run(ps, ps, args.toArray(String[]::new));
    var out= baos.toString(StandardCharsets.UTF_8);
    checkTool(tool, rc, args, out);
    return out;
  }
  static void checkTool(String tool, int rc, List<String> args, String out){
    Require.check(rc == 0,
      "Tool error: "+tool+
      "\nExit code: "+rc+
      "\nArgs:\n"+Join.of(args, "", "\n", "", "")+
      "\nOutput length: "+out.length()+
      "\nOutput:\n<<<\n"+out+"\n>>>");
  }
  public static ProcessBuilder processBuilder(List<String> cmd){
    var pb= new ProcessBuilder(cmd);
    pb.environment().remove("_JPACKAGE_LAUNCHER");
    return pb;
  }
  ///Returns the filename with extension (the substring after the last '/').
  public static String fileNameWithExtension(String s){
    var res= s.substring(lastSlashIndex(s)+1);
    assert !res.isEmpty();
    return res;
  }
  /// Returns the filename without the extension. Example: "fear:/a/b/c.tar.gz" -> "c"
  public static String fileNameWithoutExtension(String s){
    int slash= lastSlashIndex(s);
    int dot= s.indexOf('.', slash + 1); // first dot after last slash
    assert dot>slash+1 && dot+1<s.length();
    return s.substring(slash + 1, dot);
  }
  public static String removeFileNameAllowTop(String s){
    int i= s.lastIndexOf('/');
    if (i == -1){ return ""; }
    int j= s.indexOf(":/");
    return i==j+1 ? s.substring(0, i+1) : s.substring(0, i);
  }
  private static int lastSlashIndex(String s){
    int i= s.lastIndexOf('/');
    assert i>=0 && i+1<s.length();
    return i;
  }
  public static boolean isWindows(){ return osName().contains("win"); }
  public static boolean isMac(){ return osName().contains("mac"); }
  public static boolean isLinux(){ return osName().contains("linux"); }
  private static String osName(){ return System.getProperty("os.name").toLowerCase(Locale.ROOT); }
  public interface RunVoid{ void run() throws IOException; }
  public interface Run<T>{ T run() throws IOException; }
  public interface WalkVoid{ void walk(Stream<Path> p) throws IOException; }
  public interface Walk<T>{ T walk(Stream<Path> p) throws IOException; }
  public static void ofV(RunVoid f){
    try{ f.run(); }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  public static <T> T of(Run<T> f){
    try{ return f.run(); }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  public static void walkV(Path p, WalkVoid f){
    try (Stream<Path> s= Files.walk(p)){ f.walk(s.map(Fs::noLink)); }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  public static <T> T walk(Path p, Walk<T> f){
    try (Stream<Path> s= Files.walk(p)){ return f.walk(s.map(Fs::noLink)); }
    catch (IOException e){ throw new UncheckedIOException(e); }
  }
  public static void copyTreeFlat(Path from, Path to){
    var files= walk(from, s -> s
      .filter(Files::isRegularFile)
      .toList()
    );
    var duplicates= files.stream()
      .collect(Collectors.groupingBy(p -> p.getFileName().toString()))
      .entrySet().stream()
      .filter(e -> e.getValue().size() > 1)
      .toList();
    Require.check(duplicates.isEmpty(), "Expected distinct file names to flatten "+from+" into "+to+", found:\n"+duplicates);
    ensureDir(to);
    files.forEach(src -> ofV(() -> Files.copy(src, noLink(to.resolve(src.getFileName())), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)));
  }
}