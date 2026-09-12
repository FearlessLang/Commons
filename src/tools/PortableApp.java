package tools;

import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import utils.OneOr;

public record PortableApp(
  Path packaging, Path out, List<List<Path>> modules, Path base, Path rt,
  Path depJar, String appName, String versionId, String moduleMain
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
    modules.forEach(m->compileMod(m, modsDir, tmp));
    var stdLib= prepareAppContent(tmp);
    JavacTool.jpackage(out, packaging, appName, versionId, moduleMain, stdLib);
    if(!Fs.isLinux()){ return; }
    var mimeLoc= out.resolve(appName).resolve("bin").resolve("fearless-mime.xml");
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
    modules.stream().flatMap(List::stream).forEach(p->Fs.reqDir(p, "module source root"));
  }
  private static void compileMod(List<Path> srcRoots, Path modsDir, Path tmp){
    var classes= tmp.resolve("classes");
    JavacTool.javac(srcRoots, classes, modsDir);
    var name= OneOr.of("Expected one module in "+classes, ModuleFinder.of(classes).findAll().stream()).descriptor().name();
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