package tools;

import java.nio.file.Path;
import java.util.Locale;
//This file only exists because
//Desktop.getDesktop().open(path.toFile())
//has a bug connected with JPackage (shell poisoning)
public final class OpenPath{
  public static void open(Path path){ Fs.ofV(()->cleanPb(command(path)).start()); }

  private static ProcessBuilder cleanPb(String... cmd){
    var pb= new ProcessBuilder(cmd);
    pb.environment().remove("_JPACKAGE_LAUNCHER");
    return pb;
  }
  private static String[] command(Path path){
    var p= path.toAbsolutePath().toString();
    var os= System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (os.contains("win")){ return new String[]{"explorer.exe", p}; }
    if (os.contains("mac")){ return new String[]{"open", p}; }
    return new String[]{"xdg-open", p};
  }
}
