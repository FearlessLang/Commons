package tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import static offensiveUtils.Require.*;
import utils.IoErr;
import utils.Join;

public final class JavacTool{
  public static String compileTree(Path srcRoot, Path classesDir, Path jarPath) throws IOException{
    var srcs= javaSourcesUnder(srcRoot);
    IoErr.of(()->Files.deleteIfExists(jarPath));
    check(!srcs.isEmpty(), "No .java files under "+srcRoot);
    var args= new ArrayList<String>(10+srcs.size());
    args.add("-encoding"); args.add("UTF-8");
    args.add("-d"); args.add(classesDir.toString());
    var cp= jarsCp(jarPath);
    if (!cp.isEmpty()){ args.add("-cp"); args.add(cp); }
    srcs.forEach(p->args.add(p.toString()));
    var javacOut= Fs.runTool("javac", args);
    jar(classesDir, jarPath);
    return javacOut;
  }
  static List<Path> javaSourcesUnder(Path root){
    return IoErr.walk(root,pi->pi
      .filter(p->p.toString().endsWith(".java"))
      .sorted(Comparator.comparing(p->root.relativize(p).toString()))
      .toList());
  }
  public static void jar(Path classesDir, Path jarFile){
    Fs.ensureDir(jarFile.getParent());
    Fs.runTool("jar", List.of(
      "--create","--file",jarFile.toString(),
      "-C",classesDir.toString(),"."));
  }
  static String jarsCp(Path jarFile){
    return IoErr.<String>walk(jarFile.getParent(),s->s
      .filter(p->p.toString().endsWith(".jar"))
      .filter(p->!p.equals(jarFile))
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator)));
  }  
  private static String launcherProps(String moduleMain, List<String> javaOptions, boolean winConsole){
    var b= new StringBuilder();
    b.append("module=").append(moduleMain).append('\n');
    var opts= joinJvmOpts(javaOptions);
    if (!opts.isEmpty()){ b.append("java-options=").append(opts).append('\n'); }
    if (isWindows()){ b.append("win-console=").append(winConsole ? "true" : "false").append('\n'); }
    return b.toString();
  }
  private static String joinJvmOpts(List<String> opts){ return Join.of(opts,""," ","",""); }
  private static boolean isWindows(){ return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
  public static void javac(List<Path> srcs, Path classesDir){}
  public static final String launcherKey= "app.launcher";
  public static final String appDirKey= "app.dir";
  public static final String consoleKey= "console";
  public static final String winKey= "w";
  public static final List<String> javaOptions= List.of("-ea","-D"+appDirKey+"=$APPDIR");
  public static void jpackage(Path dest, String appName, String moduleMain, Path appContent){
    var slash= moduleMain.indexOf('/');
    check(slash > 0, "Bad moduleMain (need Mod/pkg.Main): "+moduleMain);
    check(!appName.isEmpty(), "Empty appName");
    check(Files.isDirectory(appContent), "Not a directory: "+appContent);
    var modsDir= dest.resolve("_mods");
    check(Files.isDirectory(modsDir), "Missing "+modsDir+" (put your module jars there)");
    var runtimeImage= Path.of(System.getProperty("java.home"));
    check(Files.isDirectory(runtimeImage), "No runtime image dir at "+runtimeImage);
    var tmp= dest.resolve("_tmp_jpackage");
    Fs.cleanDir(tmp); Fs.ensureDir(tmp);
    try{ jpBody(dest, moduleMain, appName, appName+"w", appContent, modsDir, runtimeImage, tmp); }
    finally{ Fs.rmTree(tmp); }
  }
  private static void jpBody(
    Path dest, String moduleMain, String appName, String wName,
    Path appContent, Path modsDir, Path runtimeImage, Path tmp
  ){
    var wProps= tmp.resolve(wName+".properties");
    Fs.writeUtf8(wProps, launcherProps(moduleMain, jvmOpts(winKey), false));
    var args= new ArrayList<String>(96);
    args.add("--type"); args.add("app-image");
    args.add("--dest"); args.add(dest.toString());
    args.add("--name"); args.add(appName);
    args.add("--module-path"); args.add(modsDir.toString());
    args.add("--module"); args.add(moduleMain);
    args.add("--runtime-image"); args.add(runtimeImage.toString());
    var consoleOpts= joinJvmOpts(jvmOpts(consoleKey));
    if (!consoleOpts.isEmpty()){ args.add("--java-options"); args.add(consoleOpts); }
    if (isWindows()){ args.add("--win-console"); }
    args.add("--add-launcher"); args.add(wName+"="+wProps);
    args.add("--app-content"); args.add(appContent.toString());
    Fs.runTool("jpackage", args);
  }
  private static List<String> jvmOpts(String launcherValue){
    var xs= new ArrayList<String>(javaOptions.size()+1);
    xs.addAll(javaOptions);
    xs.add("-D"+launcherKey+"="+launcherValue);
    return xs;
  }
  public static final List<String> javacArgs= List.of("-encoding","UTF-8");
  public static void javac(List<Path> srcs, Path classesDir, Path modsDir){
    srcs.forEach(src->check(Files.isDirectory(src), "Not a directory: "+src));
    check(Files.isDirectory(modsDir), "Not a directory: "+modsDir);
    Fs.cleanDir(classesDir); Fs.ensureDir(classesDir);
    var mi= srcs.stream()
      .map(src->src.resolve("module-info.java"))
      .filter(Files::exists)
      .toList();
    check(mi.size() == 1, "No module-info or ambiguous module-info");
    var args= new ArrayList<String>(64);
    args.addAll(javacArgs);
    args.add("-d"); args.add(classesDir.toString());
    args.add("--module-path"); args.add(modsDir.toString());
    srcs.forEach(src->IoErr.walkV(src,s->s
      .filter(p->p.toString().endsWith(".java"))
      .forEach(p->args.add(p.toString()))));
    Fs.runTool("javac", args);
  }
}