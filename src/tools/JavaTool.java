package tools;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import utils.Bug;

public final class JavaTool{
  public static String runMain(List<String> jvmArgs, Path classesDir, Path libs, String mainClass, String... args) throws InterruptedException{
    return _runMain(jvmArgs, cp(classesDir.toString(),List.of(libs)), mainClass, args);
  }
  private static String cp(String main, List<Path> libDirs){
    var l= jarsCp(libDirs);
    return l.isEmpty() ? main : main+File.pathSeparator+l;
  }
  public static String runMainFromJars(List<String> jvmArgs, List<Path> jarDirs, String mainClass, String... args) throws InterruptedException{
    String cp= jarsCp(jarDirs);
    assert !cp.isEmpty() : "No jars under "+jarDirs;
    return _runMain(jvmArgs,cp,mainClass,args);
  }
  public static ChildJvm startMainFromJars(List<String> jvmArgs, List<Path> jarDirs, String mainClass, Consumer<String> out, String... mainArgs){
    return start(jvmArgs, jarsCp(jarDirs), mainClass, out, mainArgs);
  }
  private static ChildJvm start(List<String> jvmArgs,String classPath,String mainClass,Consumer<String> out,String... mainArgs){
    assert !classPath.isEmpty();
    var args= new ArrayList<>(jvmArgs);
    args.add("-cp"); args.add(classPath); args.add(mainClass);
    args.addAll(List.of(mainArgs));
    return ChildJvm.start(args, out);
  }
  private static String _runMain(List<String> jvmArgs,String classPath,String mainClass,String... mainArgs) throws InterruptedException{
    var sb= new StringBuilder();
    var jvm= start(jvmArgs, classPath, mainClass, s->{ sb.append(s); System.out.print(s); }, mainArgs);
    int ec= jvm.await();
    if (ec != 0){ throw Bug.of("java failed (ec="+ec+") cmd="+jvm.cmd()+"\n"+sb); }
    return sb.toString();
  }
  static String jarsCp(List<Path> jarDirs){
    return jarDirs.stream()
      .flatMap(dir->Fs.walk(dir,s->s.filter(p->p.toString().endsWith(".jar")).toList()).stream())
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator));
  }
}
