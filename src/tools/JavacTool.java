package tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import utils.IoErr;

public final class JavacTool{
  public static String compileTree(Path srcRoot, Path classesDir, Path jarPath) throws IOException{
    var srcs= javaSourcesUnder(srcRoot);
    IoErr.of(()->Files.deleteIfExists(jarPath));
    assert !srcs.isEmpty();
    var tp= ToolProvider.findFirst("javac").orElseThrow();
    var baos= new ByteArrayOutputStream();
    var ps= new PrintStream(baos, true, StandardCharsets.UTF_8);
    // Build CLI args javac -encoding UTF-8 -d <classesDir> -cp <cp> <allSources...>
    var args= new ArrayList<String>(10+srcs.size());
    args.add("-encoding"); args.add("UTF-8");
    args.add("-d"); args.add(classesDir.toString());
    var cp= jarsCp(jarPath);
    if (!cp.isEmpty()){ args.add("-cp"); args.add(cp); }
    srcs.forEach(p->args.add(p.toString()));
    int rc= tp.run(ps, ps, args.toArray(String[]::new));
    String res=baos.toString(StandardCharsets.UTF_8);
    System.out.println(res);
    assert rc == 0 : res;
    jar(classesDir, jarPath, ps);
    return res;
  }
  static List<Path> javaSourcesUnder(Path root){
    return IoErr.walk(root,pi->pi
      .filter(p->p.toString().endsWith(".java"))
      .sorted(Comparator.comparing(p->root.relativize(p).toString()))
      .toList());
  } 
  static void jar(Path classesDir, Path jarFile, PrintStream ps){
    var jar= ToolProvider.findFirst("jar").orElseThrow();
    int rc= jar.run(ps, ps,
      "--create","--file",jarFile.toString(),
      "-C",classesDir.toString(),"."
    );
    assert rc == 0;
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