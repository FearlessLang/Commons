package tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import utils.Bug;

public final class JavaTool{
  public static String runMain(List<String> jvmArgs, Path classesDir, String mainClass) throws InterruptedException{
    try{ return _runMain(jvmArgs,classesDir.toString(),mainClass); }
    catch(IOException e){ throw Bug.of(e.toString()); }
  }
  public static String runMainFromJars(List<String> jvmArgs, Path jarDir, String mainClass) throws InterruptedException{
    try{
      String cp= jarsCp(jarDir);
      assert !cp.isEmpty() : "No jars under "+jarDir;
      return _runMain(jvmArgs,cp,mainClass);
    }
    catch(IOException e){ throw Bug.of(e.toString()); }
  }
  private static String _runMain(List<String> jvmArgs,String classPath,String mainClass) throws IOException, InterruptedException{
    var cmd= new ArrayList<String>();
    cmd.add(javaExe().toString());
    cmd.add("-ea");
    cmd.addAll(jvmArgs);
    cmd.add("-Dfearless.parentLifeline=stdin");
    cmd.add("-cp");
    cmd.add(classPath);
    cmd.add(mainClass);
    Process p= new ProcessBuilder(cmd).redirectErrorStream(true).start();
    var lifeline= p.getOutputStream();
    var baos= new ByteArrayOutputStream();
    var pumpErr= new IOException[1];
    var pump= new Thread(() -> pumpOutput(p,baos,pumpErr),"FearlessJvmOut");
    pump.setDaemon(true);
    pump.start();
    int ec; try{ ec= p.waitFor(); }
    catch(InterruptedException | RuntimeException | Error e){
      closeQuietly(lifeline);
      waitForUninterruptibly(p,200);
      if (p.isAlive()){ killAndWait(p); }
      joinUninterruptibly(pump);
      throw e;
    }
    finally{ closeQuietly(lifeline); }
    joinUninterruptibly(pump);
    if (pumpErr[0] != null){ throw pumpErr[0]; }
    var out= baos.toString(StandardCharsets.UTF_8);
    if (ec != 0){ throw Bug.of("java failed (ec="+ec+") cmd="+cmd+"\n"+out); }
    return out;
  }
  private static void pumpOutput(Process p, ByteArrayOutputStream baos, IOException[] pumpErr){
    try(var in= p.getInputStream()){
      var buf= new byte[8192];
      for (int n; (n= in.read(buf)) != -1; ){
        baos.write(buf,0,n);
        System.out.write(buf,0,n);
      }
    }
    catch(IOException e){ if (p.isAlive()){ pumpErr[0]= e; } }
  }
  private static void killAndWait(Process p){
    if (!p.isAlive()){ return; }
    p.destroy();
    waitForUninterruptibly(p,200);
    if (!p.isAlive()){ return; }
    p.destroyForcibly();
    waitForUninterruptibly(p,0);
  }
  private static void waitForUninterruptibly(Process p, long millis){
    boolean interrupted= false;
    try{
      if (millis > 0){
        long end= System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while(p.isAlive()){
          long left= end - System.nanoTime();
          if (left <= 0){ return; }
          try{
            if (p.waitFor(TimeUnit.NANOSECONDS.toMillis(left),TimeUnit.MILLISECONDS)){ return; }
          }
          catch(InterruptedException e){ interrupted= true; }
        }
        return;
      }
      while(p.isAlive()){
        try{ p.waitFor(); }
        catch(InterruptedException e){ interrupted= true; }
      }
    }
    finally{ if (interrupted){ Thread.currentThread().interrupt(); } }
  }
  private static void joinUninterruptibly(Thread t){
    boolean interrupted= false;
    try{
      while(t.isAlive()){
        try{ t.join(); }
        catch(InterruptedException e){ interrupted= true; }
      }
    }
    finally{ if (interrupted){ Thread.currentThread().interrupt(); } }
  }
  private static void closeQuietly(java.io.Closeable c){
    try{ c.close(); }
    catch(IOException _){}
  }
  static String jarsCp(Path jarDir) throws IOException{
    return Fs.walk(jarDir,s->s
      .filter(p->p.toString().endsWith(".jar"))
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator)));
  }
  static Path javaExe(){
    var bin= Path.of(System.getProperty("java.home"),"bin");
    var j= bin.resolve("java");
    if (Files.isRegularFile(j)){ return j; }
    j= bin.resolve("java.exe");
    if (Files.isRegularFile(j)){ return j; }
    throw Bug.of("No java launcher in "+bin);
  }
}