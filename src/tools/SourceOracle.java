package tools;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static offensiveUtils.Require.*;
import utils.Push;

/** SourceOracle
 * Could be disk-backed or fully in memory.
 * Some Source oracles may cache the content from the disk, some may not.
 * Some Source oracles may provide a view of some disk content.
 */

public interface SourceOracle{
  interface Ref extends RefParent{
    byte[] loadBytes();
    long lastModified();
    default String loadString(){ return new String(loadBytes(), StandardCharsets.UTF_8); }
    //NOTE: we also need to manually override toString=fearPath in all the implementations
  }
  interface RefParent{
    String fearPath();        // "fear:/a/b.c"
    default URI fearURI(){ return URI.create(fearPath()); }
    //NOTE: we also need to manually override toString=fearPath in all the implementations
    default RefParent parent(){//may return 'this' for root
      var fp= fearPath();
      var p= RefParents.parentFearPath(fp);
      return p.equals(fp) ? this : RefParents.dir(p);
    }
    final class RefParents{
      private static final String root="fear:/";
      private static String parentFearPath(String fp){
        assert fp.startsWith(root);
        int rootLen= root.length();
        int i= fp.lastIndexOf('/');
        assert i >= rootLen - 1;
        if (i == rootLen - 1){ return root; }
        return fp.substring(0, i);
      }
      private static SourceOracle.RefParent dir(String fp){
        return new SourceOracle.RefParent(){
          @Override public SourceOracle.RefParent parent(){
            var p= parentFearPath(fp);
            return p.equals(fp) ? this : dir(p);
          }
          @Override public String fearPath(){ return fp; }
          @Override public String toString(){ return fp; }
        };
      }
    }
  }
  List<Ref> allFiles();
  default String loadString(URI uri){
    return allFiles().stream().filter(f->f.fearURI().equals(uri)).findFirst().get().loadString();
  }  
  public static URI defaultDbgFearPath(int index){
    return URI.create("fear:/___DBG___/"+(index==0 ? "_rank_app999.fear" : "in_memory"+index+".fear"));
  }
  default SourceOracle withFallback(SourceOracle fb){
    assert nonNull(fb);
    var all= Push.of(allFiles(), fb.allFiles());
    assert all.stream().map(e->e.fearPath()).distinct().count()== all.size();
    return ()->all;
  }
  public static Builder debugBuilder(){ return new Builder(); }
  public static final class Builder{
    private record DebugRef(String fearPath, byte[] loadBytes,String loadString) implements Ref{
      DebugRef{ assert nonNull(fearPath,loadBytes); }
      @Override public long lastModified(){ return System.currentTimeMillis(); }
      @Override public String toString(){ return fearPath; }
    }
    private record Debug(List<Ref> allFiles) implements SourceOracle{//should be private inside builder?
      public Debug{
        assert unmodifiable(allFiles,"Debug.fileList");//still should be guaranteed by builder?
        assert allFiles.stream().map(e->e.fearPath()).distinct().count()== allFiles.size();
      }
    }
    ArrayList<Ref> allFiles = new ArrayList<>();
    public Builder putURI(URI uri, String content){ allFiles.add(new DebugRef(uri.normalize().toString(),content.getBytes(),content)); return this; }
    public Builder put(String pathLike, String content){
      URI u = Path.of(pathLike).toAbsolutePath().normalize().toUri();
      return putURI(u, content);
    }
    public Builder put(int index,String content){ return putURI(defaultDbgFearPath(index), content); }
    public SourceOracle build(){ return new Debug(List.copyOf(allFiles)); }
  }
}