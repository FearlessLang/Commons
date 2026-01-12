package utils;

import java.nio.file.Path;

public class LocalResourcesTemplate { //public class LocalResources {
  //example for windows 
  static public final Path compilerPath= Path.of("C:\\")
    .resolve("Users","UserName",/*..,..*/"GitHub","Fearless","compiler");
  static public final Path stLibPath= Path.of("C:\\")
    .resolve("Users","UserName",/*..,..*/"GitHub","StandardLibrary","base");
  static public final Path stLibRTPath= Path.of("C:\\")
    .resolve("Users","UserName",/*..,..*/"GitHub","StandardLibrary","rt");
  static public final Path stLibDebugOut= Path.of("C:\\")
    .resolve("Users","UserName",/*..,..*/"GitHub","StandardLibrary","dbgOut");
  static public final String javaVersion= "24";
  //example for linux
  
  //example for mac
}