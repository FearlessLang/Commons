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
import utils.IoErr;

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
    var javacOut= Fs.runTool("javac", args, "javac failed srcRoot="+srcRoot+" classesDir="+classesDir);
    jar(classesDir, jarPath);
    return javacOut;
  }
  static List<Path> javaSourcesUnder(Path root){
    return IoErr.walk(root,pi->pi
      .filter(p->p.toString().endsWith(".java"))
      .sorted(Comparator.comparing(p->root.relativize(p).toString()))
      .toList());
  }
  static void jar(Path classesDir, Path jarFile){
    Fs.ensureDir(jarFile.getParent());
    Fs.runTool("jar", List.of(
      "--create","--file",jarFile.toString(),
      "-C",classesDir.toString(),"."
    ), "jar failed classesDir="+classesDir+" jarFile="+jarFile);
  }
  static String jarsCp(Path jarFile){
    return IoErr.<String>walk(jarFile.getParent(),s->s
      .filter(p->p.toString().endsWith(".jar"))
      .filter(p->!p.equals(jarFile))
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator)));
  }
}