package tools;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import static offensiveUtils.Require.*;
import utils.Push;

/** SourceOracle
 * Could be disk-backed or fully in memory.
 * Some Source oracles may cache the content from the disk, some may not.
 * Some Source oracles may provide a view of some disk content.
 */
public interface SourceOracle {
  CharSequence load(URI uri);
  boolean exists(URI uri);
  List<URI> allFiles();
  static URI defaultDbgUri(int index){
    var name= "___DBG___/in_memory"+index+".fear";
    if (index == 0){ name = "___DBG___/_rank_app999.fear"; }
    return Path.of(name).toAbsolutePath().normalize().toUri();
  }
  default String loadString(URI uri){ return load(uri).toString(); }
  default SourceOracle withFallback(SourceOracle fb){
    var p= this;
    assert nonNull(fb);
    return new SourceOracle(){
      public CharSequence load(URI u){ return p.exists(u) ? p.load(u) : fb.load(u); }
      public boolean exists(URI u){ return p.exists(u) || fb.exists(u); }
      public List<URI> allFiles(){ return Push.of(p.allFiles(),fb.allFiles()); }
    };
  }
  static boolean isFile(URI k){ return "file".equalsIgnoreCase(k.getScheme()); }

  static Debug.Builder debugBuilder(){ return new Debug.Builder(); }

  final class Debug implements SourceOracle{
    private final ConcurrentHashMap<URI,String> map;
    @Override public List<URI> allFiles(){ return map.entrySet().stream().map(e->e.getKey()).toList(); }
    private Debug(Map<URI,String> seed){ this.map= new ConcurrentHashMap<>(seed); }

    @Override public CharSequence load(URI uri){
      URI k= uri.normalize();
      String s= map.get(k);
      if (s == null){ 
      throw new IllegalArgumentException("No debug content for "+k); }
      return s;
    }
    @Override public boolean exists(URI uri){ return map.containsKey(uri.normalize()); }

    public static final class Builder{
      private final ConcurrentHashMap<URI,String> seed= new ConcurrentHashMap<>();
      public Builder putURI(URI uri, String content){ seed.put(uri.normalize(), Objects.requireNonNull(content)); return this; }
      public Builder put(String pathLike, String content){
        URI u = Path.of(pathLike).toAbsolutePath().normalize().toUri();
        return putURI(u, content);
      }
      public Builder put(int index,String content){ return putURI(defaultDbgUri(index), content); }
      public Debug build(){ return new Debug(Map.copyOf(seed)); }
    }
  }
}