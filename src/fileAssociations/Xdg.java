package fileAssociations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Xdg{
  private Xdg(){}
  static Path home(){ return Path.of(System.getProperty("user.home")); }
  static Path dataHome(){ return dir("XDG_DATA_HOME", home().resolve(".local").resolve("share")); }
  static Path configHome(){ return dir("XDG_CONFIG_HOME", home().resolve(".config")); }
  static List<Path> dataDirs(){ return dirs("XDG_DATA_DIRS", List.of(Path.of("/usr/local/share"), Path.of("/usr/share"))); }
  static List<Path> configDirs(){ return dirs("XDG_CONFIG_DIRS", List.of(Path.of("/etc/xdg"))); }
  //Every applications folder the desktop reads, nearest first.
  static List<Path> appDirs(){
    var res= new ArrayList<Path>();
    res.add(dataHome().resolve("applications"));
    dataDirs().forEach(d->res.add(d.resolve("applications")));
    return res;
  }
  //The desktop reads a prefixed list before the plain one, one prefix per name in XDG_CURRENT_DESKTOP.
  static List<String> listNames(){
    var res= new ArrayList<String>();
    for (var de: System.getenv().getOrDefault("XDG_CURRENT_DESKTOP","").split(":")){
      if (!de.isEmpty()){ res.add(de.toLowerCase(Locale.ROOT)+"-mimeapps.list"); }
    }
    res.add("mimeapps.list");
    return res;
  }
  //Every place a chosen answer can live, in the order the desktop consults them.
  static List<Path> choiceFiles(){
    var res= new ArrayList<Path>();
    var roots= new ArrayList<Path>();
    roots.add(configHome());
    roots.addAll(configDirs());
    appDirs().forEach(roots::add);
    for (var root: roots){ listNames().forEach(n->res.add(root.resolve(n))); }
    return res;
  }
  private static Path dir(String name, Path fallback){
    var v= System.getenv(name);
    if (v == null || v.isBlank()){ return fallback; }
    var res= Path.of(v);
    return res.isAbsolute() ? res.normalize() : fallback;
  }
  private static List<Path> dirs(String name, List<Path> fallback){
    var v= System.getenv(name);
    if (v == null || v.isBlank()){ return fallback; }
    var res= new ArrayList<Path>();
    for (var part: v.split(":")){
      if (part.isBlank()){ continue; }
      var p= Path.of(part);
      if (p.isAbsolute()){ res.add(p.normalize()); }
    }
    return res.isEmpty() ? fallback : List.copyOf(res);
  }
}
