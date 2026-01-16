package tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.spi.ToolProvider;

import utils.IoErr;

public final class Fs{
  public static void ensureDir(Path p){ IoErr.of(()->Files.createDirectories(p)); }
  public static void cleanDirContents(Path p){
    req(Files.isDirectory(p), "Expected directory: "+p);
    IoErr.walkV(p,s-> s
      .filter(x->!x.equals(p))
      .sorted(Comparator.reverseOrder())
      .forEach(x->IoErr.ofV(()->Files.deleteIfExists(x))
    ));
  }
  public static void writeUtf8(Path file, String content){
    ensureDir(file.getParent());
    IoErr.ofV(()->Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
  }
  public static void copyTree(Path from, Path to){
    IoErr.walkV(from,s->s.forEach(src->IoErr.ofV(()->copyOne(from, to, src))));
  }
  private static void copyOne(Path fromRoot, Path toRoot, Path src) throws IOException{
    var rel= fromRoot.relativize(src);
    var dst= toRoot.resolve(rel);
    if (Files.isDirectory(src)){ Files.createDirectories(dst); return; }
    Files.createDirectories(dst.getParent());
    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }
  public static long lastModified(Path file){
    try{ return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(); }
    catch(NoSuchFileException _){ return -1; }
    catch(IOException ioe){ throw new UncheckedIOException(ioe); }
  }
  //----
  // Writes (overwriting if needed) and guarantees mtime > minExclusiveMillis. Returns the actual mtime.
  public static long writeUtf8(Path file, String content, long minExclusiveMillis){
    ensureDir(file.getParent());
    for(;;){
      IoErr.ofV(()->Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
      var m= lastModified(file);
      if (m > minExclusiveMillis){ return m; }
      try{ Thread.sleep(10); }
      catch(InterruptedException ie){ Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
    }
  }
  public static void req(boolean ok, String msg){ if (!ok){ throw new IllegalArgumentException(msg); } }
  public static void reqDir(Path p, String what){ req(Files.isDirectory(p), "Expected dir "+what+": "+p); }
  public static void cleanDir(Path p){
    if (!Files.exists(p)){ ensureDir(p); return; }
    req(Files.isDirectory(p), "Expected directory: "+p);
    cleanDirContents(p);
  }
  public static void rmTree(Path p){
    if (!Files.exists(p)){ return; }
    if (!Files.isDirectory(p)){ IoErr.ofV(()->Files.deleteIfExists(p)); return; }
    cleanDirContents(p);
    IoErr.ofV(()->Files.deleteIfExists(p));
  }
  public static void copyFresh(Path from, Path to){
    rmTree(to);
    copyTree(from, to);
  }
  public static String runTool(String tool, List<String> args, String ctx){
    var tp= ToolProvider.findFirst(tool).orElseThrow();
    var baos= new ByteArrayOutputStream();
    var ps= new PrintStream(baos, true, StandardCharsets.UTF_8);
    int rc= tp.run(ps, ps, args.toArray(String[]::new));
    var out= baos.toString(StandardCharsets.UTF_8);
    req(rc == 0, ctx+"\n"+out);
    return out;
  }
}