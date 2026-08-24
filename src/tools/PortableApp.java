package tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record PortableApp(
  Path packaging, Path out, Path commonsSrc,Path frontendSrc,Path frontendSrcModule,
  Path coordinatorSrc,Path coordinatorSrcModule,Path base,Path rt,
  Path depJar, String versionId, String moduleMain
){
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
    removeOtherPlatformSkijaJars(modsDir);
    compileAllMods(modsDir, tmp);
    Fs.copyFresh(modsDir.resolve("Commons.jar"),commonsSrc.getParent().resolve("Commons.jar"));
    var stdLib= prepareAppContent(tmp);
    JavacTool.jpackage(out, packaging, versionId, moduleMain, stdLib);
    if(!Fs.isLinux()){ return; }
    var mimeLoc= out.resolve(JavacTool.appNameFor(versionId)).resolve("bin").resolve("fearless-mime.xml");
    Fs.writeUtf8(mimeLoc, mime);
  }
  private static void removeOtherPlatformSkijaJars(Path modsDir){
    var currentTag= (Fs.isLinux()? "linux" : Fs.isMac()? "macos" : "windows")
      +"-"+(System.getProperty("os.arch").contains("aarch64")? "arm64" : "x64");
    Fs.walk(modsDir, s->s
      .filter(Files::isRegularFile)
      .filter(p->{
        var name= p.getFileName().toString();
        return name.startsWith("skija-") && !name.startsWith("skija-shared-") && !name.startsWith("skija-"+currentTag+"-");
      })
      .toList()
    ).forEach(Fs::rmTree);
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