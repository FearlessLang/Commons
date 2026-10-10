package fileAssociations;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import utils.Push;

final class Xdg{
  static Path home(){ return Path.of(System.getProperty("user.home")); }
  static Path dataHome(){ return dir("XDG_DATA_HOME", home().resolve(".local").resolve("share")); }
  static Path configHome(){ return dir("XDG_CONFIG_HOME", home().resolve(".config")); }
  static List<Path> dataDirs(){ return dirs("XDG_DATA_DIRS", List.of(Path.of("/usr/local/share"), Path.of("/usr/share"))); }
  static List<Path> configDirs(){ return dirs("XDG_CONFIG_DIRS", List.of(Path.of("/etc/xdg"))); }
  //Every applications folder the desktop reads, nearest first.
  static List<Path> appDirs(){ return Push.of(dataHome(), dataDirs()).stream().map(d->d.resolve("applications")).toList(); }
  //The desktop reads a prefixed list before the plain one, one prefix per name in XDG_CURRENT_DESKTOP.
  static List<String> listNames(){
    var prefixed= Stream.of(Objects.requireNonNullElse(LinuxAssociations.env.apply("XDG_CURRENT_DESKTOP"),"").split(":"))
      .filter(de->!de.isEmpty()).map(de->de.toLowerCase(Locale.ROOT)+"-mimeapps.list").toList();
    return Push.of(prefixed, "mimeapps.list");
  }
  //Every place a chosen answer can live, in the order the desktop consults them.
  static List<Path> choiceFiles(){
    return Stream.of(List.of(configHome()), configDirs(), appDirs()).flatMap(List::stream)
      .flatMap(root->listNames().stream().map(root::resolve)).toList();
  }
  private static Path dir(String name, Path fallback){
    var v= LinuxAssociations.env.apply(name);
    if (v == null || v.isBlank()){ return fallback; }
    var res= Path.of(v);
    return res.isAbsolute() ? res.normalize() : fallback;
  }
  private static List<Path> dirs(String name, List<Path> fallback){
    var v= LinuxAssociations.env.apply(name);
    if (v == null || v.isBlank()){ return fallback; }
    var res= Stream.of(v.split(":")).filter(part->!part.isBlank()).map(Path::of).filter(Path::isAbsolute).map(Path::normalize).toList();
    return res.isEmpty() ? fallback : res;
  }
}
