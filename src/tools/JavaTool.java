package tools;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import offensiveUtils.Require;
import utils.Join;
import utils.Push;

public final class JavaTool{
  public static void runMain(List<String> jvmArgs, Path classesDir, Path libs, String mainClass, String... mainArgs) throws InterruptedException{
    var l= jarsCp(List.of(libs));
    var classPath= l.isEmpty() ? classesDir.toString() : classesDir+File.pathSeparator+l;
    var jvm= ChildJvm.start(Push.of(List.of(jvmArgs, List.of("-cp", classPath, mainClass), List.of(mainArgs))), System.out::print);
    var rc= jvm.await();
    Require.check(rc == 0, "Tool error: java\nExit code: "+rc+"\nArgs:\n"+Join.of(jvm.cmd(), "", "\n", "", ""));
  }
  static String jarsCp(List<Path> jarDirs){ return Join.of(jars(jarDirs).stream(), "", File.pathSeparator, "", ""); }
  static List<Path> jars(List<Path> jarDirs){
    return jarDirs.stream()
      .flatMap(dir -> Fs.walk(dir, s -> s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()).stream())
      .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
  }
}
