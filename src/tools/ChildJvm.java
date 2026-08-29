package tools;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import utils.Bug;

public final class ChildJvm{
  public static final String lifelineKey= "fearless.parentLifeline";
  public static final String lifelineValue= "stdin";
  private final Process p;
  private final Thread pump;
  private final IOException[] pumpErr;
  private final List<String> cmd;
  private ChildJvm(Process p, Thread pump, IOException[] pumpErr, List<String> cmd){
    this.p= p; this.pump= pump; this.pumpErr= pumpErr; this.cmd= cmd;
  }
  public static ChildJvm start(List<String> args, Consumer<String> out){
    var cmd= new ArrayList<String>(args.size()+3);
    cmd.add(javaExe().toString());
    cmd.add("-ea");
    cmd.add("-D"+lifelineKey+"="+lifelineValue);
    cmd.addAll(args);
    var pb= new ProcessBuilder(cmd);
    pb.environment().remove("_JPACKAGE_LAUNCHER");
    Process p= Fs.of(()->pb.redirectErrorStream(true).start());
    var pumpErr= new IOException[1];
    var pump= new Thread(()->pumpOutput(p,out,pumpErr),"FearlessJvmOut");
    pump.setDaemon(true);
    pump.start();
    return new ChildJvm(p,pump,pumpErr,List.copyOf(cmd));
  }
  public List<String> cmd(){ return cmd; }
  public int await() throws InterruptedException{
    var lifeline= p.getOutputStream();
    int ec; try{ ec= p.waitFor(); }
    catch(InterruptedException | RuntimeException | Error e){
      closeQuietly(lifeline);
      waitForUninterruptibly(p,200);
      kill();
      joinUninterruptibly(pump);
      throw e;
    }
    finally{ closeQuietly(lifeline); }
    joinUninterruptibly(pump);
    if (pumpErr[0] != null){ throw Bug.of(pumpErr[0].toString()); }
    return ec;
  }
  public void kill(){
    if (!p.isAlive()){ return; }
    p.destroy();
    waitForUninterruptibly(p,200);
    if (!p.isAlive()){ return; }
    p.destroyForcibly();
    waitForUninterruptibly(p,0);
  }
  private static void pumpOutput(Process p, Consumer<String> out, IOException[] pumpErr){
    try(var in= p.getInputStream(); var sink= new Utf8Sink(out)){
      var buf= new byte[8192];
      for (int n; (n= in.read(buf)) != -1; ){ sink.write(buf,0,n); }
    }
    catch(IOException e){ if (p.isAlive()){ pumpErr[0]= e; } }
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
  private static void closeQuietly(Closeable c){
    try{ c.close(); }
    catch(IOException _){}
  }
  public static void watchParent(){
    if (!lifelineValue.equals(System.getProperty(lifelineKey))){ return; }
    var t= new Thread(ChildJvm::readTillEof,"FearlessParentLifeline");
    t.setDaemon(true);
    t.start();
  }
  private static void readTillEof(){
    try{ while(System.in.read() != -1){} }
    catch(IOException _){}
    Runtime.getRuntime().halt(121);
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
