package tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import utils.Bug;

public final class JavaTool{
  public static String runMain(List<String> jvmArgs, Path classesDir, Path libs, String mainClass) throws InterruptedException{
    try{ return _runMain(jvmArgs, cp(classesDir.toString(),libs), mainClass); }
    catch(IOException e){ throw Bug.of(e.toString()); }
  }
  private static String cp(String main, Path libs) throws IOException{
    var l= jarsCp(libs);
    return l.isEmpty() ? main : main+File.pathSeparator+l;
  }
  public static String runMainFromJars(List<String> jvmArgs, Path jarDir, String mainClass, String... args) throws InterruptedException{
    try{
      String cp= jarsCp(jarDir);
      assert !cp.isEmpty() : "No jars under "+jarDir;
      return _runMain(jvmArgs,cp,mainClass,args);
    }
    catch(IOException e){ throw Bug.of(e.toString()); }
  }
  public static ChildJvm startMainFromJars(List<String> jvmArgs, Path jarDir, String mainClass, Consumer<String> out, String... mainArgs){
    try{ return start(jvmArgs, jarsCp(jarDir), mainClass, out, mainArgs); }
    catch(IOException e){ throw Bug.of(e.toString()); }
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
  static String jarsCp(Path jarDir) throws IOException{
    return Fs.walk(jarDir,s->s
      .filter(p->p.toString().endsWith(".jar"))
      .sorted(Comparator.comparing(p->p.getFileName().toString()))
      .map(Path::toString)
      .collect(Collectors.joining(File.pathSeparator)));
  }
}
