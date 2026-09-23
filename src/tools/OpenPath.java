package tools;

import java.nio.file.Path;
//This file only exists because
//Desktop.getDesktop().open(path.toFile())
//has a bug connected with JPackage (shell poisoning)
public final class OpenPath{
  public static void open(Path path){
    var pb= new ProcessBuilder(command(path));
    pb.environment().remove("_JPACKAGE_LAUNCHER");
    Fs.ofV(pb::start);
  }
  private static String[] command(Path path){
    var p= path.toAbsolutePath().toString();
    if (Fs.isWindows()){ return new String[]{"explorer.exe", p}; }
    if (Fs.isMac()){ return new String[]{"open", p}; }
    return new String[]{"xdg-open", p};
  }
}
