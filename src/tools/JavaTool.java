package tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import utils.Bug;

public final class JavaTool{
  public static String runMain(Path classesDir, String mainClass){
    try{ return _runMain(classesDir.toString(), mainClass); }
    catch(IOException | InterruptedException e){ throw Bug.of(e.toString()); }
  }

  public static String runMainFromJars(Path jarDir, String mainClass){
    try{
      String cp= jarsCp(jarDir);
      assert !cp.isEmpty() : "No jars under "+jarDir;
      return _runMain(cp, mainClass);
    }catch(IOException | InterruptedException e){ throw Bug.of(e.toString()); }
  }

  private static String _runMain(String classPath, String mainClass) throws IOException, InterruptedException{
    var cmd= List.of(javaExe().toString(), "-cp", classPath, mainClass);
    var p= new ProcessBuilder(cmd).redirectErrorStream(true).start();
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
    if (ec != 0){ throw Bug.of("java failed (ec="+ec+") cmd="+cmd+"\n"+out); }
    return out;
  }
  static String jarsCp(Path jarDir) throws IOException{
    return Fs.walk(jarDir,s->s
      .filter(p->p.toString().endsWith(".jar"))
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator)));
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