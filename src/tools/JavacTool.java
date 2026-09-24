package tools;

import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import static offensiveUtils.Require.*;

import utils.Push;

public final class JavacTool{
  private static final String javacArgFile="_javac.args";

  public static String compileTree(Path srcRoot, Path classesDir, Runnable postProcess, Path jarPath, List<Path> extraClasspathDirs){
    var srcs= javaSourcesUnder(srcRoot);
    Fs.of(()->Files.deleteIfExists(jarPath));
    check(!srcs.isEmpty(), "No .java files under "+srcRoot);
    var args= new ArrayList<String>(10+srcs.size());
    args.add("-encoding"); args.add("UTF-8");
    args.add("-d"); args.add(slash(classesDir));
    var cp= JavaTool.jarsCp(Push.of(jarPath.getParent(), extraClasspathDirs));
    if (!cp.isEmpty()){ args.add("-cp"); args.add(cp); }
    srcs.forEach(p->args.add(slash(p)));
    var javacOut= runJavacArgFile(jarPath.getParent(), args);
    postProcess.run();
    jar(classesDir, jarPath);
    return javacOut;
  }

  static List<Path> javaSourcesUnder(Path root){
    return Fs.walk(root,pi->pi
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

  private static String runJavacArgFile(Path dir, List<String> args){
    var file= dir.resolve(javacArgFile);
    Fs.writeUtf8(file, args.stream()
      .map(JavacTool::argToken)
      .collect(Collectors.joining("\n","","\n")));
    return Fs.runTool("javac", List.of("@"+slash(file)));
  }

  private static String slash(Path p){ return slash(p.toAbsolutePath().normalize().toString()); }
  private static String slash(String s){ return s.replace('\\','/'); }

  private static String argToken(String s){
    s= slash(s);
    if (s.indexOf('"')<0 && s.chars().noneMatch(Character::isWhitespace)){ return s; }
    return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";
  }

  public static final String launcherKey= "app.launcher";
  public static final String appDirKey= "app.dir";
  public static final String versionIdKey= "app.versionId";
  public static final String consoleKey= "console";
  public static final String winKey= "w";

  public static Path reqAppDir(Supplier<? extends RuntimeException> onMissing){
    var launcher= System.getProperty(launcherKey);
    var appDir= System.getProperty(appDirKey);
    if (launcher == null || appDir == null){ throw onMissing.get(); }
    var res= Path.of(appDir);
    if (!res.isAbsolute()){ throw onMissing.get(); }
    return res;
  }
  public static String reqVersionId(Supplier<? extends RuntimeException> onMissing){
    var versionId= System.getProperty(versionIdKey);
    if (versionId == null){ throw onMissing.get(); }
    return versionId;
  }
  //--enable-native-access needed to coordinator (example, forcing english language)
  public static final List<String> javaOptions= List.of("-ea","--enable-native-access=Commons,Coordinator","-D"+appDirKey+"=$APPDIR");

  // Local build-time staging dir we hand to `jpackage --module-path`, holding
  // the module jars (Commons/FearlessFrontend/Coordinator + external jars).
  public static final String buildModsDirName= "_mods";
  // jpackage's own app-image convention: it copies the `--module-path` content
  // into a dir named exactly this, inside the produced app (e.g. on Linux,
  // <dest>/<name>/lib/app/mods). We don't choose this name, jpackage does
  public static final String deployedModsDirName= "mods";

  public static String dataDirNameFor(String versionId){ return "fearless"+versionId; }

  public static void jpackage(Path dest, Path packaging, String appName, String versionId, String moduleMain, Path appContent){
    var slash= moduleMain.indexOf('/');
    check(slash > 0, "Bad moduleMain (need Mod/pkg.Main): "+moduleMain);
    Fs.reqDir(packaging, "packaging");
    Fs.reqDir(appContent, "app content");
    var modsDir= dest.resolve(buildModsDirName);
    check(Files.isDirectory(modsDir), "Missing "+modsDir+" (put your module jars there)");
    var tmp= dest.resolve("_tmp_jpackage");
    Fs.cleanDir(tmp);
    var runtimeImage= jlinkRuntimeImage(modsDir, tmp);
    try{ jpBody(dest, appName, versionId, moduleMain, modsDir, appContent, runtimeImage, tmp, packaging); }
    finally{ Fs.rmTree(tmp); }
    reqDeployedMods(dest);
  }
  private static Path jlinkRuntimeImage(Path modsDir, Path tmp){
    var javaHome= Path.of(System.getProperty("java.home"));
    var jmods= javaHome.resolve("jmods");
    check(Files.isDirectory(jmods), "No jmods dir at "+jmods+" (need a full JDK, not a JRE, to jlink a trimmed runtime)");
    var appModules= ModuleFinder.of(modsDir).findAll();
    var appModuleNames= appModules.stream().map(m->m.descriptor().name()).collect(Collectors.toSet());
    var platformModules= appModules.stream()
      .flatMap(m->m.descriptor().requires().stream())
      .filter(r->!r.modifiers().contains(Requires.Modifier.STATIC))
      .map(Requires::name)
      .filter(n->!appModuleNames.contains(n))
      .distinct().sorted()
      .collect(Collectors.joining(","));
    var runtimeImage= tmp.resolve("_runtime");
    Fs.runTool("jlink", List.of(
      "--module-path", jmods.toString(),
      "--add-modules", platformModules,
      "--output", runtimeImage.toString(),
      "--no-header-files", "--no-man-pages", "--strip-debug",
      "--compress", "zip-0"
    ));
    return runtimeImage;
  }
  private static void reqDeployedMods(Path dest){
    var found= Fs.walk(dest, s->s
      .filter(Files::isDirectory)
      .filter(p->p.getFileName().toString().equals(deployedModsDirName))
      .toList());
    check(found.size() == 1, "Expected exactly one '"+deployedModsDirName+"' dir under "+dest+" after jpackage, found: "+found);
    var jars= Fs.walk(found.getFirst(), s->s.filter(p->p.toString().endsWith(".jar")).toList());
    check(!jars.isEmpty(), "jpackage-produced '"+deployedModsDirName+"' dir has no jars: "+found.getFirst());
  }

  private static void jpBody(Path dest, String name, String versionId, String moduleMain, Path modsDir, Path appContent, Path runtimeImage, Path tmp, Path packaging){
    String wName= name + "w";
    var icon= iconForCurrentOs(packaging);
    var wProps= tmp.resolve(wName+".properties");
    var winConsole= Fs.isWindows() ? "win-console=false\n" : "";
    Fs.writeUtf8(wProps, "module="+moduleMain+"\njava-options="+String.join(" ", jvmOpts(winKey, versionId))+"\n"+winConsole+"icon="+icon.toString().replace("\\","\\\\")+"\n");
    var args= new ArrayList<String>(96);
    args.add("--type"); args.add("app-image");
    args.add("--dest"); args.add(dest.toString());
    args.add("--name"); args.add(name);
    args.add("--icon"); args.add(icon.toString());
    args.add("--module-path"); args.add(modsDir.toString());
    args.add("--module"); args.add(moduleMain);
    args.add("--runtime-image"); args.add(runtimeImage.toString());
    args.add("--java-options"); args.add(String.join(" ", jvmOpts(consoleKey, versionId)));
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
    var windows= iconFile(packaging, "windows", "icon.ico");
    var mac= iconFile(packaging, "macos", "icon.icns");
    var linux= iconFile(packaging, "linux", "icon.png");
    if (Fs.isWindows()){ return windows; }
    if (Fs.isMac()){ return mac; }
    check(Fs.isLinux(),"Unsupported OS: "+System.getProperty("os.name"));
    return linux;
  }

  private static List<String> jvmOpts(String launcherValue, String versionId){
    var xs= new ArrayList<String>(javaOptions.size()+2);
    xs.addAll(javaOptions);
    xs.add("-D"+launcherKey+"="+launcherValue);
    xs.add("-D"+versionIdKey+"="+versionId);
    return xs;
  }

  public static final List<String> javacArgs= List.of("-encoding","UTF-8","-Xlint:all,-auxiliaryclass,-missing-explicit-ctor","-Werror");

  public static void javac(List<Path> srcs, Path classesDir, Path modsDir){
    javac(srcs, classesDir, modsDir, List.of());
  }
  //extraLintDisables: for modules that must `requires` an automatic module (no module-info in
  //the jar, e.g. flexmark), since that is otherwise an unavoidable -Werror failure
  public static void javac(List<Path> srcs, Path classesDir, Path modsDir, List<String> extraLintDisables){
    srcs.forEach(src->Fs.reqDir(src, "source root"));
    Fs.reqDir(modsDir, "module path");
    Fs.cleanDir(classesDir);
    var mi= srcs.stream()
      .map(src->src.resolve("module-info.java"))
      .filter(Files::exists)
      .toList();
    check(mi.size() == 1, "No module-info or ambiguous module-info");
    var args= new ArrayList<String>(javacArgs);
    extraLintDisables.forEach(l->args.add("-Xlint:"+l));
    args.add("-d"); args.add(slash(classesDir));
    args.add("--module-path"); args.add(slash(modsDir));
    srcs.forEach(src->javaSourcesUnder(src).forEach(p->args.add(slash(p))));
    runJavacArgFile(classesDir.getParent(), args);
  }
}