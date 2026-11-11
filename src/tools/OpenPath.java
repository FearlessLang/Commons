package tools;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.ProcessBuilder.Redirect;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import utils.Bug;

//This file only exists because
//Desktop.getDesktop().open(path.toFile())
//has a bug connected with JPackage (shell poisoning)
public final class OpenPath{
  private static final int ignoreUnknown= 0x400;
  private static final int progId= 20;
  private static final int sized= 1;
  private static final int noAssociation= 0x80070483;
  public static Process open(Path path){
    return Fs.of(Fs.processBuilder(command(path)).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD)::start);
  }
  private static List<String> command(Path path){
    var p= path.toAbsolutePath().toString();
    if (Fs.isWindows()){ return List.of("explorer.exe", p); }
    if (Fs.isMac()){ return List.of("open", p); }
    return List.of("xdg-open", p);
  }
  //ShellExecuteExW, even with SEE_MASK_FLAG_NO_UI, opens a file of an extension with no program through the class Unknown, whose Open verb shows the Open with dialog.
  @SuppressWarnings("restricted")
  public static boolean noProgramOpens(Path path){
    assert Fs.isWindows();
    if (Files.isDirectory(path)){ return false; }
    var name= path.getFileName().toString();
    var extension= name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".";
    try (var arena= Arena.ofConfined()){
      var query= Linker.nativeLinker().downcallHandle(SymbolLookup.libraryLookup("shlwapi.dll", arena).findOrThrow("AssocQueryStringW"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      var res= (int)query.invokeExact(ignoreUnknown, progId, arena.allocateFrom(extension, StandardCharsets.UTF_16LE), MemorySegment.NULL, MemorySegment.NULL, arena.allocate(JAVA_INT));
      assert res == sized || res == noAssociation;
      return res == noAssociation;
    }
    catch (Throwable e){ throw Bug.of(e); }
  }
}
