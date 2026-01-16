package tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PortableApp{
  public static void build(
    Path commonsSrc,Path frontendSrc,Path frontendSrcModule,
    Path coordinatorSrc,Path coordinatorSrcModule,
    Path base,Path rt,Path out
  ){
    Fs.reqDir(base, "base"); Fs.reqDir(rt, "rt");
    Fs.reqDir(commonsSrc, "Commons/src");
    Fs.reqDir(frontendSrc, "FearlessFrontend/src");
    Fs.reqDir(frontendSrcModule, "FearlessFrontend/srcModule");
    Fs.reqDir(coordinatorSrc, "Coordinator/src");
    Fs.reqDir(coordinatorSrcModule, "Coordinator/srcModule");
    Fs.cleanDir(out);
    Fs.copyFresh(base, out.resolve("stdLib").resolve("base"));
    Fs.copyFresh(rt, out.resolve("stdLib").resolve("rt"));
    var app= out.resolve("app"); Fs.ensureDir(app);
    var tmp= out.resolve("_tmp"); Fs.ensureDir(tmp);
    compileMod("Commons", commonsSrc, commonsSrc, app, tmp);
    compileMod("FearlessFrontend", frontendSrc, frontendSrcModule, app, tmp);
    compileMod("Coordinator", coordinatorSrc, coordinatorSrcModule, app, tmp);
    jlink(out.resolve("runtime"), app, "Coordinator", List.of("jdk.compiler","jdk.jartool"));
    writeLaunchers(out.resolve("bin"), "Coordinator", "mainCoordinator.Main");
    Fs.rmTree(tmp);
    Zips.zipDir(out, out.resolveSibling(out.getFileName().toString()+".zip"));
  }
  private static void compileMod(String name, Path srcRoot, Path miRoot, Path app, Path tmp){
    var mi= miRoot.resolve("module-info.java");
    Fs.req(Files.isRegularFile(mi), "Missing module-info.java: "+mi);
    var classes= tmp.resolve(name);
    Fs.cleanDir(classes);
    var jar= app.resolve(name+".jar");
    var srcs= JavacTool.javaSourcesUnder(srcRoot).stream()
      .filter(p->!p.getFileName().toString().equals("module-info.java"))
      .sorted(Comparator.comparing(Path::toString))
      .toList();
    Fs.req(!srcs.isEmpty(), name+" has no sources under "+srcRoot);
    var args= new ArrayList<String>(12+srcs.size());
    args.add("-encoding"); args.add("UTF-8");
    args.add("-d"); args.add(classes.toString());
    args.add("--module-path"); args.add(app.toString());
    srcs.forEach(p->args.add(p.toString()));
    args.add(mi.toString());
    Fs.runTool("javac", args, "javac "+name);
    JavacTool.jar(classes, jar);
  }
  private static void jlink(Path runtimeDir, Path appDir, String mainModule, List<String> extraMods){
    Fs.rmTree(runtimeDir);
    var jmods= Path.of(System.getProperty("java.home"), "jmods");
    Fs.req(Files.isDirectory(jmods), "No jmods dir at "+jmods+" (need a JDK)");
    var mp= jmods.toString()+File.pathSeparator+appDir.toString();
    var mods= mainModule+(extraMods.isEmpty() ? "" : ","+String.join(",", extraMods));
    Fs.runTool("jlink", List.of(
      "--module-path", mp,
      "--add-modules", mods,
      "--output", runtimeDir.toString()
    ), "jlink");
    Fs.req(Files.isDirectory(runtimeDir), "jlink did not create "+runtimeDir);
  }
  private static void writeLaunchers(Path binDir, String mainModule, String mainClass){
    var sh= """
#!/bin/sh
APPDIR="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="$APPDIR/runtime/bin/java"
if [ ! -x "$JAVA" ]; then JAVA="$APPDIR/runtime/bin/java.exe"; fi
exec "$JAVA" -Dfearless.appDir="$APPDIR" --module-path "$APPDIR/app" -m """
      + "\""+mainModule+"/"+mainClass+"\" \"$@\"\n";  
    var bat= """
@echo off
set "APPDIR=%~dp0.."
set "JAVA=%APPDIR%\\runtime\\bin\\javaw.exe"
start "" /b "%JAVA%" "-Dfearless.appDir=%APPDIR%" --module-path "%APPDIR%\\app" -m """
      + " " + mainModule + "/" + mainClass + " %*\n";
    Fs.writeUtf8(binDir.resolve("fearless"), sh);
    Fs.writeUtf8(binDir.resolve("fearless.bat"), bat);
  }
}