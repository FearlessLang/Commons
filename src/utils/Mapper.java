package utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class Mapper {
  public static <K,V> Map<K,V> of(Consumer<HashMap<K,V>> c){
    return Collections.unmodifiableMap(ofMut(c));
  }
  public static <K,V> HashMap<K,V> ofMut(Consumer<HashMap<K,V>> c){
    HashMap<K,V> hm=new HashMap<>();
    c.accept(hm);
    return hm;
  }
}
