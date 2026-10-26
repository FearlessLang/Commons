package tools;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import utils.Join;
import utils.Push;

public final class JavaTool{
  public static String runMain(List<String> jvmArgs, Path classesDir, Path libs, String mainClass, String... mainArgs) throws InterruptedException{
    var l= jarsCp(List.of(libs));
    return _runMain(jvmArgs, l.isEmpty() ? classesDir.toString() : classesDir+File.pathSeparator+l, mainClass, mainArgs);
  }
  private static String _runMain(List<String> jvmArgs, String classPath, String mainClass, String... mainArgs) throws InterruptedException{
    var sb= new StringBuilder();
    var jvm= ChildJvm.start(Push.of(List.of(jvmArgs, List.of("-cp", classPath, mainClass), List.of(mainArgs))), s -> echo(sb, s));
    Fs.checkTool("java", jvm.await(), jvm.cmd(), sb.toString());
    return sb.toString();
  }
  private static void echo(StringBuilder sb, String s){
    sb.append(s);
    System.out.print(s);
  }
  static String jarsCp(List<Path> jarDirs){ return Join.of(jars(jarDirs).stream(), "", File.pathSeparator, "", ""); }
  static List<Path> jars(List<Path> jarDirs){
    return jarDirs.stream()
      .flatMap(dir -> Fs.walk(dir, s -> s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()).stream())
      .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
  }
}
