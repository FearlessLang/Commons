package tools;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import utils.Bug;

/// The JVM decodes the names of the folders on its class path, and its working directory, as text
/// with the file name encoding of the system, and loads classes from the decoded text: a folder
/// whose name is not valid text in that encoding holds classes the JVM cannot load. A Path keeps
/// the bytes of a name, so this loader reads the jars of a Fearless program through Paths, and the
/// folders holding them reach it as the properties fearlessCode.<n>, written by argOf(Path).
public final class PathClassLoader extends ClassLoader{
  static{ registerAsParallelCapable(); }
  public static final String codeKey= "fearlessCode.";
  private final List<Path> jars;
  private PathClassLoader(List<Path> jars){ super(ClassLoader.getPlatformClassLoader()); this.jars= jars; }
  /// A path as an argument that pathOf(String) reads back as the same path. On Windows it is the path
  /// itself: a name is 16 bit units, and a Java String holds any of them. Elsewhere a name is bytes, and
  /// the argument is the file URI, which writes every byte that is not a plain ASCII character as an escape.
  public static String argOf(Path p){ return Fs.isWindows() ? p.toString() : p.toUri().toString(); }
  public static Path pathOf(String arg){ return Fs.isWindows() ? Path.of(arg) : Path.of(URI.create(arg)); }
  public static List<String> codeArgs(List<Path> dirs){
    return IntStream.range(0, dirs.size()).mapToObj(i -> "-D"+codeKey+i+"="+argOf(dirs.get(i))).toList();
  }
  /// The location of a module is the file URI of its jar, with every byte of its name; a URL of the
  /// same jar loses the third slash of "file:///", and Path.of then reads it as text in UTF-8.
  public static Path jar(){
    var m= PathClassLoader.class.getModule();
    if (m.isNamed()){ return Path.of(m.getLayer().configuration().findModule(m.getName()).orElseThrow().reference().location().orElseThrow()); }
    try{ return Path.of(PathClassLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    catch (URISyntaxException e){ throw Bug.of(e); }
  }
  public static void main(String[] args) throws Throwable{
    var dirs= System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith(codeKey)).map(k -> pathOf(System.getProperty(k))).toList();
    var jars= JavaTool.jars(dirs).stream().map(PathClassLoader::root).toList();
    var loader= new PathClassLoader(jars);
    Thread.currentThread().setContextClassLoader(loader);
    var main= MethodHandles.publicLookup().findStatic(loader.loadClass(args[0]), "main", MethodType.methodType(void.class, String[].class));
    main.invokeExact(Arrays.copyOfRange(args, 1, args.length));
  }
  private static Path root(Path jar){
    return Fs.of(() -> FileSystems.newFileSystem(jar, Map.of("releaseVersion", "runtime"))).getPath("/");
  }
  private Optional<Path> find(String name){ return jars.stream().map(j -> j.resolve(name)).filter(Files::isRegularFile).findFirst(); }
  @Override protected Class<?> findClass(String name) throws ClassNotFoundException{
    var file= find(name.replace('.', '/')+".class").orElseThrow(() -> new ClassNotFoundException(name));
    var bytes= Fs.of(() -> Files.readAllBytes(file));
    return defineClass(name, bytes, 0, bytes.length);
  }
  @Override protected URL findResource(String name){ return find(name).map(PathClassLoader::url).orElse(null); }
  @Override protected Enumeration<URL> findResources(String name){
    return Collections.enumeration(jars.stream().map(j -> j.resolve(name)).filter(Files::isRegularFile).map(PathClassLoader::url).toList());
  }
  private static URL url(Path entry){
    try{ return URL.of(new URI("fearless", entry.toString(), null), opener(entry)); }
    catch (URISyntaxException|MalformedURLException e){ throw Bug.of(e); }
  }
  private static URLStreamHandler opener(Path entry){
    return new URLStreamHandler(){
      @Override protected URLConnection openConnection(URL u){
        return new URLConnection(u){
          @Override public void connect(){}
          @Override public InputStream getInputStream() throws IOException{ return Files.newInputStream(entry); }
        };
      }
    };
  }
}
