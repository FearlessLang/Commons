package tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.zip.ZipInputStream;

public record ReadZip(
    Function<String,RuntimeException> badNameErr,
    Function<String,RuntimeException> dupNameErr,
    Function<String,RuntimeException> tooLargeErr,
    Function<String,RuntimeException> emptyDirErr,
    ObjLongConsumer<String> reqTime){
  public Map<String,byte[]> readAll(Fs.Run<ZipInputStream> szin){ return read(szin, _ -> true); }
  public Map<String,byte[]> read(Fs.Run<ZipInputStream> szin, Predicate<String> keep){
    return cleanUp(Fs.of(() -> { try (var zin= szin.run()){ return _read(zin, keep); } }));
  }
  private static final byte[] unread= new byte[0];
  private LinkedHashMap<String,byte[]> _read(ZipInputStream zin, Predicate<String> keep){
    var out= new LinkedHashMap<String,byte[]>();
    while (true){
      var e= Fs.of(zin::getNextEntry);
      if (e == null){ return out; }
      try{
        var n= e.getName();
        reqZipNameOk(n);
        var key= keyOf(n);
        if (out.containsKey(key)){ throw dupNameErr.apply(key); }
        if (n.endsWith("/")){ out.put(key, null); continue; }
        reqTime.accept(key, e.getTime());
        out.put(key, keep.test(key) ? readEntryBytes(key, zin) : unread);
      }
      finally{ Fs.ofV(zin::closeEntry); }
    }
  }
  private static String keyOf(String n){ return n.endsWith("/") ? n.substring(0, n.length() - 1) : n; }
  private void reqZipNameOk(String n){
    var bad= n.isEmpty() || n.startsWith("/") || n.indexOf('\0')>=0;
    if (bad){ throw badNameErr.apply(n); }
    var sub= keyOf(n);
    for (var seg : sub.split("/", -1)){
      var badSeg= seg.isEmpty() || seg.equals(".") || seg.equals("..");
      if (badSeg){ throw badNameErr.apply(n); }
    }
  }
  private byte[] readEntryBytes(String name, ZipInputStream zin){
    try{ return Fs.of(zin::readAllBytes); }
    catch (OutOfMemoryError _){ throw tooLargeErr.apply(name); }
  }
  private Map<String,byte[]> cleanUp(LinkedHashMap<String,byte[]> map){
    var res= new LinkedHashMap<String,byte[]>();
    for (var e : map.entrySet()){
      var k= e.getKey();
      var emptyDir= e.getValue()==null && map.keySet().stream().noneMatch(o -> o.startsWith(k+"/"));
      var inDotDir= ("/"+k.substring(0, k.lastIndexOf('/')+1)).contains("/.");
      if (emptyDir && !inDotDir){ throw emptyDirErr.apply(k); }
      if (emptyDir){ res.put(k+"/", new byte[0]); }
      if (e.getValue() != null){ res.put(k, e.getValue() == unread ? null : e.getValue()); }
    }
    return Collections.unmodifiableMap(res);//to keep the order instead of Map.copyOf undocumented behaviour exactly here
  }
}
