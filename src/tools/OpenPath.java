package tools;

import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.List;

//This file only exists because
//Desktop.getDesktop().open(path.toFile())
//has a bug connected with JPackage (shell poisoning)
public final class OpenPath{
  public static Process open(Path path){
    return Fs.of(Fs.processBuilder(command(path)).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD)::start);
  }
  private static List<String> command(Path path){
    var p= path.toAbsolutePath().toString();
    if (Fs.isWindows()){ return List.of("explorer.exe", p); }
    if (Fs.isMac()){ return List.of("open", p); }
    return List.of("xdg-open", p);
  }
}
