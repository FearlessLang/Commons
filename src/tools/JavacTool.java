package tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import static offensiveUtils.Require.*;

import utils.Bug;
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
    if (Fs.isWindows()){ b.append("win-console=").append(winConsole ? "true" : "false").append('\n'); }
    return b.toString();
  }
  private static String joinJvmOpts(List<String> opts){ return Join.of(opts,""," ","",""); }
  public static void javac(List<Path> srcs, Path classesDir){}
  public static final String launcherKey= "app.launcher";
  public static final String appDirKey= "app.dir";
  public static final String consoleKey= "console";
  public static final String winKey= "w";
  public static final List<String> javaOptions= List.of("-ea","-D"+appDirKey+"=$APPDIR");
  public static void jpackage(Path dest, Path packaging, String moduleMain, Path appContent){
    var slash= moduleMain.indexOf('/');
    check(slash > 0, "Bad moduleMain (need Mod/pkg.Main): "+moduleMain);
    check(Files.isDirectory(packaging), "Not a directory: "+packaging);
    check(Files.isDirectory(appContent), "Not a directory: "+appContent);
    var runtimeImage= Path.of(System.getProperty("java.home"));
    check(Files.isDirectory(runtimeImage), "No runtime image dir at "+runtimeImage);
    var tmp= dest.resolve("_tmp_jpackage");
    Fs.cleanDir(tmp); Fs.ensureDir(tmp);
    try{ jpBody(dest, moduleMain, appContent, runtimeImage, tmp, packaging); }
    finally{ Fs.rmTree(tmp); }
  }
  private static List<String> expected= List.of("windows","macos","linux");
  private static String getName(Path packaging){
    List<String> extra= IoErr.of(()->{
      try(var fs= Files.list(packaging)){
        return fs.map(pi->pi.getFileName().toString())
          .filter(pi->!expected.contains(pi)).toList();
        }});
    check(extra.size() == 1,"Not exactly one candidate name for the deployed app: "+extra);
    return extra.getFirst();
    }
  private static void jpBody(Path dest, String moduleMain, Path appContent, Path runtimeImage, Path tmp, Path packaging){
    String name= getName(packaging);
    String wName= name + "w";
    var icon= iconForCurrentOs(packaging);
    var wProps= tmp.resolve(wName+".properties");
    Fs.writeUtf8(wProps, launcherProps(moduleMain, jvmOpts(winKey), false)+"icon="+icon.toString().replace("\\","\\\\")+"\n");
    var modsDir= dest.resolve("_mods");
    check(Files.isDirectory(modsDir), "Missing "+modsDir+" (put your module jars there)");
    var args= new ArrayList<String>(96);
    args.add("--type"); args.add("app-image");
    args.add("--dest"); args.add(dest.toString());
    args.add("--name"); args.add(name);
    args.add("--icon"); args.add(icon.toString());
    args.add("--module-path"); args.add(modsDir.toString());
    args.add("--module"); args.add(moduleMain);
    args.add("--runtime-image"); args.add(runtimeImage.toString());
    var consoleOpts= joinJvmOpts(jvmOpts(consoleKey));
    if (!consoleOpts.isEmpty()){ args.add("--java-options"); args.add(consoleOpts); }
    if (Fs.isWindows()){ args.add("--win-console"); }
    args.add("--add-launcher"); args.add(wName+"="+wProps);
    args.add("--app-content"); args.add(appContent.toString());
    Fs.runTool("jpackage", args);
  }
  private static Path iconFile(Path packaging, String osDir, String file){
    var p= packaging.resolve(osDir).resolve(file);
    check(Files.isRegularFile(p), "Missing icon file: "+p);
    return p.toAbsolutePath().normalize();
  }
  private static Path iconForCurrentOs(Path packaging){
    iconFile(packaging, "windows", "icon.ico");
    iconFile(packaging, "macos", "icon.icns");
    iconFile(packaging, "linux", "icon.png");
    if (Fs.isWindows()){ return iconFile(packaging, "windows", "icon.ico"); }
    if (Fs.isMac()){ return iconFile(packaging, "macos", "icon.icns"); }
    if (Fs.isLinux()){ return iconFile(packaging, "linux", "icon.png"); }
    check(false,"Unsupported OS: "+System.getProperty("os.name"));
    throw Bug.unreachable();
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