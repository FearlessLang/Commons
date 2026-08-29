package fileAssociations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import tools.Fs;
import utils.Bug;

final class Shell{
  private Shell(){}
  record Ran(int code, String out){}
  static String req(List<String> cmd, Function<String,RuntimeException> stepFailed){
    var ran= exec(cmd);
    var out= ran.map(Ran::out).orElse("The program could not be started.");
    var reported= (String.join(" ",cmd)+"\n"+out).strip();
    if (ran.isEmpty() || ran.get().code() != 0){ throw stepFailed.apply(reported); }
    return reported;
  }
  static Optional<Ran> exec(List<String> cmd){
    var pb= new ProcessBuilder(cmd).redirectErrorStream(true);
    pb.environment().remove("_JPACKAGE_LAUNCHER");
    Process p;
    try { p= pb.start(); }
    catch(IOException e){ return Optional.empty(); }
    var out= Fs.of(()->new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    return Optional.of(new Ran(waitFor(p), out));
  }
  private static int waitFor(Process p){
    try { return p.waitFor(); }
    catch(InterruptedException e){ Thread.currentThread().interrupt(); throw Bug.of(e); }
  }
}
