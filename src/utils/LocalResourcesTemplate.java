package utils;

import java.nio.file.Path;

public class LocalResourcesTemplate { //public class LocalResources {
  //example for windows
  private static Path prefix=Path.of("C:\\").resolve("Users","...","OneDrive","Documents","GitHub");
  //example for linux  
  //private static Path prefix= Path.of("/").resolve("home","...","Desktop","Java25");
  //example for mac  
  static public final Path compilerPath= prefix.resolve("Fearless","compiler");
  static public final Path stLibPath= prefix.resolve("StandardLibrary","base");
  static public final Path stLibRTPath= prefix.resolve("StandardLibrary","rt");
  static public final Path stLibDebugOut= prefix.resolve("StandardLibrary","dbgOut");
  static public final Path integrationTests= prefix.resolve("StandardLibrary","integrationTests");
  
  static public final Path commonsSrc= prefix.resolve("Commons","src");
  static public final Path frontendSrc= prefix.resolve("Frontend","FearlessFrontend","src");
  static public final Path frontendSrcModule= prefix.resolve("Frontend","FearlessFrontend","srcModule");
  static public final Path coordinatorSrc= prefix.resolve("Coordinator","src");
  static public final Path coordinatorSrcModule= prefix.resolve("Coordinator","srcModule");

  static public final Path portableFolderOut= prefix.resolve("StandardLibrary","fearlessArtefact");
  static public final Path badZipCorpous= prefix.resolve("Coordinator","badZips");
  static public final Path packaging= prefix.resolve("Coordinator","_fearless_packaging");
  static public final String javaVersion= "24";
}