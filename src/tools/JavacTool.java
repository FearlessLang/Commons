package tools;

import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import offensiveUtils.Require;
import utils.Join;
import utils.Push;

public final class JavacTool{
  private static final String javacArgFile= "_javac.args";
  public static final String launcherKey= "app.launcher";
  public static final String appDirKey= "app.dir";
  public static final String versionIdKey= "app.versionId";
  public static final String consoleKey= "console";
  public static final String winKey= "w";
  //--enable-native-access needed to coordinator (example, forcing english language)
  public static final List<String> javaOptions= List.of("-ea", "-Xss16m", "--enable-native-access=Commons,Coordinator", "-D"+appDirKey+"=$APPDIR");
  // Local build-time staging dir we hand to `jpackage --module-path`, holding
  // the module jars (the compiled modules of the app + external jars).
  public static final String buildModsDirName= "_mods";
  // jpackage's own app-image convention: it copies the `--module-path` content
  // into a dir named exactly this, inside the produced app (e.g. on Linux,
  // <dest>/<name>/lib/app/mods). We don't choose this name, jpackage does
  public static final String deployedModsDirName= "mods";
  public static final List<String> javacArgs= List.of("-encoding", "UTF-8", "-Xlint:all,-auxiliaryclass,-missing-explicit-ctor", "-Werror");
  public static String compileTree(Path srcRoot, Path classesDir, Runnable postProcess, Path jarPath, List<Path> extraClasspathDirs){
    var srcs= javaSourcesUnder(srcRoot);
    Fs.ofV(() -> Fs.deleteIfExists(jarPath));
    Require.check(!srcs.isEmpty(), "Expected .java files under "+srcRoot);
    var args= new ArrayList<String>(List.of("-encoding", "UTF-8", "-d", abs(classesDir)));
    var cp= JavaTool.jarsCp(Push.of(jarPath.getParent(), extraClasspathDirs));
    if (!cp.isEmpty()){ args.add("-cp"); args.add(cp); }
    srcs.forEach(p -> args.add(abs(p)));
    var javacOut= runJavacArgFile(jarPath.getParent(), args);
    postProcess.run();
    jar(classesDir, jarPath);
    return javacOut;
  }
  static List<Path> javaSourcesUnder(Path root){
    return Fs.walk(root, s -> s
      .filter(p -> p.toString().endsWith(".java"))
      .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
      .toList());
  }
  public static void jar(Path classesDir, Path jarFile){
    Fs.ensureDir(jarFile.getParent());
    Fs.runTool("jar", List.of(
      "--create", "--file", jarFile.toString(),
      "-C", classesDir.toString(), "."));
  }
  private static String runJavacArgFile(Path dir, List<String> args){
    var file= dir.resolve(javacArgFile);
    Fs.writeUtf8(file, Join.of(args.stream().map(JavacTool::argToken), "", "\n", "\n"));
    return Fs.runTool("javac", List.of("@"+abs(file)));
  }
  private static String abs(Path p){ return p.toAbsolutePath().normalize().toString(); }
  private static String argToken(String s){ return "\""+s.replace("\\", "\\\\").replace("\"", "\\\"")+"\""; }
  public static Path reqAppDir(Supplier<RuntimeException> onMissing){
    var launcher= System.getProperty(launcherKey);
    var appDir= System.getProperty(appDirKey);
    if (launcher==null || appDir==null){ throw onMissing.get(); }
    var res= Path.of(appDir);
    if (!res.isAbsolute()){ throw onMissing.get(); }
    return res;
  }
  public static String reqVersionId(Supplier<RuntimeException> onMissing){
    var versionId= System.getProperty(versionIdKey);
    if (versionId == null){ throw onMissing.get(); }
    return versionId;
  }
  public static String dataDirNameFor(String versionId){ return "fearless"+versionId; }
  public static final String managedName= "fearlessManaged";
  public static Optional<Path> managerFolderOf(Path launcher){
    for (var dir= launcher.getParent(); dir != null && dir.getFileName() != null; dir = dir.getParent()){
      var name= dir.getFileName().toString();
      if (name.startsWith(managedName)){ return Optional.of(dir.resolveSibling(dataDirNameFor(name.substring(managedName.length())))); }
    }
    return Optional.empty();
  }
  public static void jpackage(Path dest, Path packaging, String appName, String versionId, String moduleMain, Path appContent){
    var slash= moduleMain.indexOf('/');
    Require.check(slash > 0, "Expected moduleMain of the form Module/pkg.Main: "+moduleMain);
    Fs.reqDir(packaging, "packaging");
    Fs.reqDir(appContent, "app content");
    var modsDir= dest.resolve(buildModsDirName);
    Fs.reqDir(modsDir, "module jars");
    var tmp= dest.resolve("_tmp_jpackage");
    Fs.cleanDir(tmp);
    var runtimeImage= jlinkRuntimeImage(modsDir, tmp);
    try{ _jpackage(dest, packaging, appName, versionId, moduleMain, appContent, modsDir, runtimeImage, tmp); }
    finally{ Fs.rmTree(tmp); }
    reqDeployedMods(dest);
  }
  private static Path jlinkRuntimeImage(Path modsDir, Path tmp){
    var javaHome= Path.of(System.getProperty("java.home"));
    var jmods= javaHome.resolve("jmods");
    Fs.reqDir(jmods, "the jmods of a full JDK (a JRE has none)");
    var appModules= ModuleFinder.of(modsDir).findAll();
    var appModuleNames= appModules.stream().map(m -> m.descriptor().name()).collect(Collectors.toUnmodifiableSet());
    var platformModules= Join.of(appModules.stream()
      .flatMap(m -> m.descriptor().requires().stream())
      .filter(r -> !r.modifiers().contains(Requires.Modifier.STATIC))
      .map(Requires::name)
      .filter(n -> !appModuleNames.contains(n))
      .distinct().sorted(), "", ",", "");
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
    var found= Fs.walk(dest, s -> s
      .filter(Fs::isDirectory)
      .filter(p -> p.getFileName().toString().equals(deployedModsDirName))
      .toList());
    Require.check(found.size() == 1, "Expected exactly one '"+deployedModsDirName+"' dir under "+dest+" after jpackage, found: "+found);
    var jars= Fs.walk(found.getFirst(), s -> s.filter(p -> p.toString().endsWith(".jar")).toList());
    Require.check(!jars.isEmpty(), "Expected jars in the '"+deployedModsDirName+"' dir made by jpackage: "+found.getFirst());
  }
  private static void _jpackage(Path dest, Path packaging, String appName, String versionId, String moduleMain, Path appContent, Path modsDir, Path runtimeImage, Path tmp){
    var wName= appName+"w";
    var icon= iconForCurrentOs(packaging);
    var wProps= tmp.resolve(wName+".properties");
    var winConsole= Fs.isWindows() ? "win-console=false\n" : "";
    Fs.writeUtf8(wProps, "module="+moduleMain+"\njava-options="+Join.of(jvmOpts(winKey, versionId), "", " ", "")+"\n"+winConsole+"icon="+icon.toString().replace("\\", "\\\\")+"\n");
    var args= new ArrayList<String>(List.of("--type", "app-image", "--dest", dest.toString(), "--name", appName,
      "--icon", icon.toString(), "--module-path", modsDir.toString(), "--module", moduleMain,
      "--runtime-image", runtimeImage.toString(), "--java-options", Join.of(jvmOpts(consoleKey, versionId), "", " ", "")));
    if (Fs.isWindows()){ args.add("--win-console"); }
    args.addAll(List.of("--add-launcher", wName+"="+wProps, "--app-content", appContent.toString()));
    Fs.runTool("jpackage", args);
  }
  private static Path iconFile(Path packaging, String osDir, String file){
    var p= packaging.resolve(osDir).resolve(file);
    Require.check(Fs.isRegularFile(p), "Expected an icon file: "+p);
    return p.toAbsolutePath().normalize();
  }
  private static Path iconForCurrentOs(Path packaging){
    var windows= iconFile(packaging, "windows", "icon.ico");
    var mac= iconFile(packaging, "macos", "icon.icns");
    var linux= iconFile(packaging, "linux", "icon.png");
    if (Fs.isWindows()){ return windows; }
    if (Fs.isMac()){ return mac; }
    return linux;
  }
  private static List<String> jvmOpts(String launcherValue, String versionId){
    return Push.<String>of(javaOptions, List.of("-D"+launcherKey+"="+launcherValue, "-D"+versionIdKey+"="+versionId));
  }
  public static void javac(List<Path> srcs, Path classesDir, Path modsDir){
    javac(srcs, classesDir, modsDir, List.of());
  }
  //extraLintDisables: for modules that must `requires` an automatic module (no module-info in
  //the jar, e.g. flexmark), since that is otherwise an unavoidable -Werror failure
  public static void javac(List<Path> srcs, Path classesDir, Path modsDir, List<String> extraLintDisables){
    srcs.forEach(src -> Fs.reqDir(src, "source root"));
    Fs.reqDir(modsDir, "module path");
    Fs.cleanDir(classesDir);
    var mi= srcs.stream()
      .map(src -> src.resolve("module-info.java"))
      .filter(Fs::exists)
      .toList();
    Require.check(mi.size() == 1, "Expected exactly one module-info.java in the source roots "+srcs+", found: "+mi);
    var args= new ArrayList<>(javacArgs);
    extraLintDisables.forEach(l -> args.add("-Xlint:"+l));
    args.addAll(List.of("-d", abs(classesDir), "--module-path", abs(modsDir)));
    srcs.forEach(src -> javaSourcesUnder(src).forEach(p -> args.add(abs(p))));
    runJavacArgFile(classesDir.getParent(), args);
  }
}
