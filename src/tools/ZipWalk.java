package tools;

import java.util.HashMap;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import utils.IoErr;
//TODO: probably there is no need to ever use this one.
public final class ZipWalk{
  private final Function<String,RuntimeException> badNameErr;
  private final Function<String,RuntimeException> dupNameErr;
  private final HashMap<String,String> seen= new HashMap<>();
  public ZipWalk(Function<String,RuntimeException> badNameErr, Function<String,RuntimeException> dupNameErr){
    this.badNameErr= badNameErr;
    this.dupNameErr= dupNameErr;
  }
  public void walkV(ZipInputStream zin, Consumer<Stream<ZipEntry>> f){ walk(zin, es->{ f.accept(es); return null; }); }
  public <T> T walk(ZipInputStream zin, Function<Stream<ZipEntry>,T> f){
    seen.clear();
    try(Stream<ZipEntry> es= zipEntriesRaw(zin).map(this::check)){ return f.apply(es); }
  }
  private ZipEntry check(ZipEntry e){
    var n= e.getName();
    reqZipNameOk(n);
    var key= n.endsWith("/") ? n.substring(0, n.length()-1) : n;
    var prev= seen.putIfAbsent(key, n);
    if (prev != null){ throw dupNameErr.apply(prev); }
    return e;
  }
  private void reqZipNameOk(String n){
    var bad= n.isEmpty() || n.startsWith("/") || n.indexOf('\0') >= 0;
    if(bad){ throw badNameErr.apply(n); }
    var sub= n.endsWith("/") ? n.substring(0, n.length()-1) : n;
    for(var seg: sub.split("/", -1)){
      if(seg.isEmpty()||seg.equals(".")||seg.equals("..")){ throw badNameErr.apply(n); }
    }
  }
  private static Stream<ZipEntry> zipEntriesRaw(ZipInputStream zin){
    var sp= new Spliterators.AbstractSpliterator<ZipEntry>(Long.MAX_VALUE, Spliterator.ORDERED|Spliterator.NONNULL){
      boolean open= false;
      void closeAny(){
        if(!open){ return; }
        IoErr.ofV(zin::closeEntry);
        open= false;
      }
      @Override public boolean tryAdvance(Consumer<? super ZipEntry> action){
        closeAny();
        var e= IoErr.of(zin::getNextEntry);
        if(e==null){ return false; }
        open= true;
        action.accept(e);
        return true;
      }
    };
    return StreamSupport.stream(sp, false).onClose(sp::closeAny);
  }
}
