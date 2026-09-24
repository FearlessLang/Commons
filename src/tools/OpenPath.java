package tools;

import java.nio.file.Path;
import java.util.List;
//This file only exists because
//Desktop.getDesktop().open(path.toFile())
//has a bug connected with JPackage (shell poisoning)
public final class OpenPath{
  public static void open(Path path){
    Fs.ofV(Fs.processBuilder(command(path))::start);
  }
  private static List<String> command(Path path){
    var p= path.toAbsolutePath().toString();
    if (Fs.isWindows()){ return List.of("explorer.exe", p); }
    if (Fs.isMac()){ return List.of("open", p); }
    return List.of("xdg-open", p);
  }
}
