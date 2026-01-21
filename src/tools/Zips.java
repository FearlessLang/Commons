package tools;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import utils.IoErr;

public final class Zips{
  public static void zipDir(Path dir, Path zip){
    if (!Files.isDirectory(dir)){ throw new AssertionError("Not a dir: "+dir); }
    IoErr.of(()->Files.deleteIfExists(zip));
    try(var out= new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))){
      out.setLevel(Deflater.BEST_COMPRESSION);
      var files= Files.walk(dir)
        .filter(Files::isRegularFile)
        .sorted(Comparator.comparing(p->dir.relativize(p).toString().replace('\\','/')))
        .toList();
      for (var p: files){
        var rel= dir.relativize(p).toString();
        assert !rel.contains("\\");
        var e= new ZipEntry(rel);
        e.setTime(Files.getLastModifiedTime(p).toMillis());
        out.putNextEntry(e);
        Files.copy(p, out);
        out.closeEntry();
      }
    }
    catch(IOException e){ throw new UncheckedIOException(e); }
  }
}