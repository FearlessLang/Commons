package metaParser;

import java.util.*;
import java.util.function.Function;

public final class TokenTreeSpec<
    T  extends Token<T, TK>,
    TK extends TokenKind
>{
  final Map<TK, Map<TK, TK>> openClose= new LinkedHashMap<>();
  final Set<TK> closers= new LinkedHashSet<>();
  final Map<TK, Set<TK>>     barriers= new LinkedHashMap<>();
  final Map<TK, Function<T, Optional<T>>> closerEaters= new LinkedHashMap<>();
  final Map<TK, Function<T, Optional<T>>> openerEaters= new LinkedHashMap<>();
  
  public TokenTreeSpec<T, TK> addOpenClose(TK opener, TK closer, TK groupKind){
    assert opener != null; assert closer != null; assert groupKind != null;
    openClose.computeIfAbsent(opener, _ -> new LinkedHashMap<>()).put(closer, groupKind);
    closers.add(closer);
    return this;
  }
  public TokenTreeSpec<T, TK> addBarrier(TK opener, TK barrier){
    assert opener != null; assert barrier != null;
    barriers.computeIfAbsent(opener, _ -> new LinkedHashSet<>()).add(barrier);
    return this;
  }
  public TokenTreeSpec<T, TK> addBarriers(TK opener, Set<TK> bs){
    assert opener != null; assert bs != null;
    if (bs.isEmpty()) return this;
    barriers.computeIfAbsent(opener, _ -> new LinkedHashSet<>()).addAll(bs);
    return this;
  }
  public TokenTreeSpec<T, TK> addCloserEater(TK closer, Function<T, Optional<T>> f){
    assert closer != null; assert f != null;
    var prev = closerEaters.put(closer, f);
    assert prev == null : "duplicate closer eater for " + closer;
    return this;
  }
  public TokenTreeSpec<T, TK> addOpenerEater(TK opener, Function<T, Optional<T>> f){
    assert opener != null; assert f != null;
    var prev = openerEaters.put(opener, f);
    assert prev == null : "duplicate opener eater for " + opener;
    return this;
  }
  boolean isBarrierFor(T current, T open){
    var bs= barriers.getOrDefault(open.kind(), Set.of());
    return bs.contains(current.kind());
  }
}