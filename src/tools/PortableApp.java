package tools;

import java.nio.file.Path;
import java.util.List;

/// One self contained Fearless app image. The two deploys differ only in the name of
/// the produced folder and in which main the launchers start:
/// - portable: named after the packaging folder, launchers start the Coordinator, so a
///   double click on a project runs it;
/// - managed: named FearlessId.binFolder, launchers start the manager, which is then the
///   single process every other Fearless process reports to.
/// Everything else - the three modules, the external jars, the standard library, the
/// icon, the runtime image - is the same app, so it is built here once.
public record PortableApp(
  Path packaging, Path out, Path commonsSrc,Path frontendSrc,Path frontendSrcModule,
  Path coordinatorSrc,Path coordinatorSrcModule,Path base,Path rt,
  Path depJar, String appName, String moduleMain
){
  public static final String coordinatorMain= "Coordinator/mainCoordinator.Main";
  public static final String managerMain= "Coordinator/manager.ManagerMain";
  //The portable app: the packaging folder names it and the Coordinator is its main.
  public PortableApp(
    Path packaging, Path out, Path commonsSrc,Path frontendSrc,Path frontendSrcModule,
    Path coordinatorSrc,Path coordinatorSrcModule,Path base,Path rt,
    Path depJar
  ){
    this(packaging,out,commonsSrc,frontendSrc,frontendSrcModule,
      coordinatorSrc,coordinatorSrcModule,base,rt,depJar,
      JavacTool.getName(packaging),coordinatorMain);
  }
  public void build(){
    reqInputs();
    Fs.cleanDir(out); Fs.ensureDir(out);
    var tmp= out.resolve("_tmp"); Fs.ensureDir(tmp);
    var modsDir= out.resolve(JavacTool.buildModsDirName);
    try{ build0(tmp, modsDir); }
    finally{ Fs.rmTree(tmp); Fs.rmTree(modsDir); }
  }
  private void build0(Path tmp, Path modsDir){
    Fs.cleanDir(modsDir);
    Fs.copyTreeFlat(depJar, modsDir);
    compileAllMods(modsDir, tmp);
    Fs.copyFresh(modsDir.resolve("Commons.jar"),commonsSrc.getParent().resolve("Commons.jar"));
    var stdLib= prepareAppContent(tmp);
    JavacTool.jpackage(out, packaging, appName, moduleMain, stdLib);
    if(!Fs.isLinux()){ return; }
    var mimeLoc= out.resolve(appName).resolve("bin").resolve("fearless-mime.xml");
    Fs.writeUtf8(mimeLoc, mime);
  }
  private void reqInputs(){
    Fs.reqDir(base, "base"); Fs.reqDir(rt, "rt");
    Fs.reqDir(commonsSrc, "Commons/src");
    Fs.reqDir(frontendSrc, "FearlessFrontend/src");
    Fs.reqDir(frontendSrcModule, "FearlessFrontend/srcModule");
    Fs.reqDir(coordinatorSrc, "Coordinator/src");
    Fs.reqDir(coordinatorSrcModule, "Coordinator/srcModule");
  }
  private void compileAllMods(Path modsDir, Path tmp){
    compileMod("Commons", List.of(commonsSrc), modsDir, tmp);
    compileMod("FearlessFrontend", List.of(frontendSrc, frontendSrcModule), modsDir, tmp);
    compileMod("Coordinator", List.of(coordinatorSrc, coordinatorSrcModule), modsDir, tmp);
  }
  private static void compileMod(String name, List<Path> srcRoots, Path modsDir, Path tmp){
    var classes= tmp.resolve("classes").resolve(name);
    JavacTool.javac(srcRoots, classes, modsDir);
    JavacTool.jar(classes, modsDir.resolve(name+".jar"));
  }
  private Path prepareAppContent(Path tmp){
    var app= tmp.resolve("app");
    Fs.cleanDir(app);
    var stdLib= app.resolve("stdLib");
    Fs.copyFresh(base, stdLib.resolve("base"));
    Fs.copyFresh(rt, stdLib.resolve("rt"));
    Fs.copyFresh(packaging.resolve("linux").resolve("icon.png"), app.resolve("icon.png"));
    return app;
  }
  //need to be saved in fearless-mime.xml near fearless and fearlessw
  private static final String mime="""
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-fearless">
    <comment>Fearless project</comment>
    <glob pattern="*.fearless"/>
  </mime-type>
</mime-info>
""";
}