package metaParser;

import java.util.*;
import java.util.function.Function;

import offensiveUtils.Require;

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
    assert Require.nonNull(opener, closer, groupKind);
    openClose.computeIfAbsent(opener, _ -> new LinkedHashMap<>()).put(closer, groupKind);
    closers.add(closer);
    return this;
  }
  public TokenTreeSpec<T, TK> addBarriers(TK opener, Set<TK> bs){
    assert Require.nonNull(opener, bs);
    if (bs.isEmpty()){ return this; }
    barriers.computeIfAbsent(opener, _ -> new LinkedHashSet<>()).addAll(bs);
    return this;
  }
  public TokenTreeSpec<T, TK> addCloserEater(TK closer, Function<T, Optional<T>> f){
    assert Require.nonNull(closer, f);
    assert !closerEaters.containsKey(closer): "duplicate closer eater for " + closer;
    closerEaters.put(closer, f);
    return this;
  }
  public TokenTreeSpec<T, TK> addOpenerEater(TK opener, Function<T, Optional<T>> f){
    assert Require.nonNull(opener, f);
    assert !openerEaters.containsKey(opener): "duplicate opener eater for " + opener;
    openerEaters.put(opener, f);
    return this;
  }
  boolean isBarrierFor(T current, T open){
    return barriers.getOrDefault(open.kind(), Set.of()).contains(current.kind());
  }
}