package offensiveUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class Require {
  private Require(){}
  //pattern: the methods return a boolean so we can use them as
  //assert check.., but the method take control of the error to report if any.
  //in this way, we can disable all those checks while disabling assertions.
  private static final List<String> jdkUmodLists = List.of(
    "java.util.ImmutableCollections$List12",
    "java.util.ImmutableCollections$ListN",
    "java.util.ImmutableCollections$SubList",
    "java.util.Collections$EmptyList",
    "java.util.Collections$SingletonList",
    "java.util.Collections$UnmodifiableList",
    "java.util.Collections$UnmodifiableRandomAccessList"
  );
  static boolean isKnownJdkUnmodifiableList(List<?> xs) {
      Module m= xs.getClass().getModule();
      if (m != null && !"java.base".equals(m.getName())){ return false; }
      return jdkUmodLists.contains(xs.getClass().getName());
  }
  public static <E> boolean unmodifiable(List<E> xs, String what){
    assert isKnownJdkUnmodifiableList(xs): what+" must be unmodifiable. Name is: "+xs.getClass().getName();
    return true;
  }
  public static <K,V> boolean unmodifiable(SequencedMap<K,V> m, String what){
    Objects.requireNonNull(m);
    //is there a trick like the above? Map.copyOf(m)==m? if now we can try with the code below
    if (m == Collections.EMPTY_MAP){ return true; }
    String cn = m.getClass().getName();
    if (cn.startsWith("java.util.ImmutableCollections$")){ return true; }
    assert cn.contains("Unmodifiable"): what+" must be an unmodifiable sequenced map";
    return true;
  }
  public static boolean nonNull(Object...os){
    for (var o:os){ Objects.requireNonNull(o); }
    return true;
  }
  public static boolean eq(Object left, Object right, String what){
    assert left.equals(right) : what+" mismatch: ["+left+"] != ["+right+"]";
    return true;
  }
  public static <T> boolean validOpt(Optional<T> o, Consumer<T> p){
    o.ifPresent(p);//the consumer itself would throw the error
    return true;
  }
  public static <T> boolean unmodifiable(List<T> l, String what, Consumer<T> p){
    unmodifiable(l, what);
    l.forEach(p);//the consumer itself would throw the error
    return true;
  }
  public static <K,E> boolean unmodifiable(SequencedMap<K,E> m, String what, BiConsumer<K,E> p){
    unmodifiable(m, what);
    m.forEach(p);//the consumer itself would throw the error
    return true;
  }
  public static <E> boolean unmodifiableDistinct(List<E> xs, String what){
    unmodifiable(xs, what);
    int n= xs.size();
    if (n <= 1){ return true; }
    if (n <= 32){
      for (int i= 0; i < n; i++){
        var xi= xs.get(i);
        for (int j= i + 1; j < n; j++){
          assert xi != xs.get(j) : what+" must have distinct elements (==)";
        }
      }
      return true;
    }
    var seen= Collections.newSetFromMap(new java.util.IdentityHashMap<E,Boolean>());
    for (var x: xs){
      assert seen.add(x): what+" must have distinct elements (==)";
    }
    return true;
  }
  public static void check(boolean ok, String msg){
    if(!ok){ 
    throw new IllegalArgumentException(msg); }
  }
}