package tools;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import utils.Bug;

public interface FileAssociations{
  List<String> owned();
  void acquire(List<String> extensions);
  void release(List<String> extensions);
  void setIcon(List<Icon> icons);
  void setProgramIcon(Path ico, Path png);
  static FileAssociations of(String name, String command, Path ico, Path png,
      Function<String,RuntimeException> notOurs,
      Function<String,RuntimeException> stepFailed,
      Function<String,RuntimeException> notTaken,
      Function<String,RuntimeException> halfDone,
      Function<String,RuntimeException> halfThere){
    if (Fs.isLinux()){ return new LinuxFileAssociations(name, command, png, notOurs, stepFailed, notTaken, halfDone, halfThere); }
    if (Fs.isWindows()){ return new WindowsFileAssociations(name, command, ico, notOurs, stepFailed, notTaken, halfDone, halfThere); }
    throw Bug.unreachable();
  }
}
