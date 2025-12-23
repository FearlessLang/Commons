package tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import utils.Bug;

public final class JavaTool{
  public static String runMain(Path classesDir, String mainClass){
    try{ return _runMain(classesDir.toString(), mainClass); }
    catch(IOException | InterruptedException e){ throw Bug.of(e.toString()); }
  }
  private static String _runMain(String classesDir, String mainClass) throws IOException, InterruptedException {
    var p= new ProcessBuilder(javaExe().toString(), "-cp", classesDir, mainClass)
      .redirectErrorStream(true).start();
    p.getOutputStream().close();
    var baos= new ByteArrayOutputStream();
    var in= p.getInputStream();
    var buf= new byte[8192];
    for (int n; (n= in.read(buf)) != -1; ){
      baos.write(buf, 0, n);
      System.out.write(buf, 0, n);
    }
    var out= baos.toString(StandardCharsets.UTF_8);
    int ec= p.waitFor();
    if (ec != 0){ throw Bug.of(out); }
    return out;
  }
  static Path javaExe(){
    var bin= Path.of(System.getProperty("java.home"), "bin");
    var j= bin.resolve("java");
    if (Files.isRegularFile(j)){ return j; }
    j= bin.resolve("java.exe");
    if (Files.isRegularFile(j)){ return j; }
    throw Bug.of("No java launcher in "+bin);
  }
}