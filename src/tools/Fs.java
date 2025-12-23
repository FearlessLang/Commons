package tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

import utils.IoErr;

public final class Fs{
  public static void ensureDir(Path p){ IoErr.of(()->Files.createDirectories(p)); }
  public static void cleanDirContents(Path p){
    assert Files.isDirectory(p);
    IoErr.walkV(p,s-> s
      .filter(x->!x.equals(p))
      .sorted(Comparator.reverseOrder())
      .forEach(x->IoErr.ofV(()->Files.deleteIfExists(x))
    ));
  }
  public static void writeUtf8(Path file, String content){
    ensureDir(file.getParent());
    IoErr.ofV(()->Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING));
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
}