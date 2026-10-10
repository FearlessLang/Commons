package tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipInputStream;

public record ReadZip(
    Function<String,RuntimeException> badNameErr,
    Function<String,RuntimeException> dupNameErr,
    Function<String,RuntimeException> tooLargeErr,
    Function<String,RuntimeException> emptyDirErr){
  public Map<String,byte[]> readAll(Fs.Run<ZipInputStream> szin){
    return cleanUp(Fs.of(()->{try(var zin=szin.run()){ return _readAll(zin); }}));
  }
  private LinkedHashMap<String,byte[]> _readAll(ZipInputStream zin){
    var out= new LinkedHashMap<String,byte[]>();
    while (true){
      var e= Fs.of(zin::getNextEntry);
      if (e == null){ return out; }
      try{
        var n= e.getName();
        reqZipNameOk(n);
        var key= keyOf(n);
        if (out.containsKey(key)){ throw dupNameErr.apply(key); }
        out.put(key, n.endsWith("/") ? null : readEntryBytes(key,zin));
      }
      finally{ Fs.ofV(zin::closeEntry); }
    }
  }
  private static String keyOf(String n){ return n.endsWith("/") ? n.substring(0, n.length()-1) : n; }
  private void reqZipNameOk(String n){
    var bad= n.isEmpty() || n.startsWith("/") || n.indexOf('\0') >= 0;
    if (bad){ throw badNameErr.apply(n); }
    var sub= keyOf(n);
    for (var seg : sub.split("/", -1)){
      var badSeg= seg.isEmpty() || seg.equals(".") || seg.equals("..");
      if (badSeg){ throw badNameErr.apply(n); }
    }
  }
  private byte[] readEntryBytes(String name, ZipInputStream zin){
    try{ return Fs.of(zin::readAllBytes); }
    catch(OutOfMemoryError _){ throw tooLargeErr.apply(name); }
  }
  private Map<String,byte[]> cleanUp(LinkedHashMap<String,byte[]> map){
    for (var e : map.entrySet()){
      var emptyDir= e.getValue() == null && map.keySet().stream().noneMatch(k->k.startsWith(e.getKey()+"/"));
      if (emptyDir){ throw emptyDirErr.apply(e.getKey()); }
    }
    map.values().removeIf(v->v == null);
    return Collections.unmodifiableMap(map);//to keep the order instead of Map.copyOf undocumented behaviour exactly here
  }
}
