package metaParser;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import metaParser.ErrFactory.LikelyCause;

record TreeDiagnostics<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >(TokenTreeSpec<T,TK> spec, Tokenizer tz){

  E onBadCloser(T open, T badCloser){
    return tryEatenBetween(open, badCloser, false)
      .or(()->tryEatenBetween(open, badCloser, true))
      .orElseGet(()->onStray(open, badCloser));
  }
  E onBadBarrier(T open, T barrier){
    return tryEatenBetween(open, barrier, false).orElseGet(()->onStray(open, barrier));
  }
  private E onStray(T open, T stop){
    int res= ofRecovery(tz.tokensForTree());
    int closer= ofRemoval(stop);
    int opener= ofRemoval(open);
    int best= Math.max(closer, opener);
    var progress= best >= res + 5 || best >= tz.tokensForTree().size() - 2;
    if (!progress){ return error(open, stop, LikelyCause.Unknown); }
    return error(open, stop, opener > closer ? LikelyCause.StrayOpener : LikelyCause.StrayCloser);
  }
  private E error(T open, T stop, LikelyCause l){
    return tz.errFactory().groupHalt(open, stop, closersForOpener(open.kind()), l, tz.self());
  }
  private List<TK> openerForCloser(TK closer){
    return spec.openClose.entrySet().stream()
      .filter(e->e.getValue().containsKey(closer))
      .map(e->e.getKey()).toList();
  }
  private List<TK> closersForOpener(TK opener){
    return spec.openClose.get(opener).keySet().stream().sorted(Comparator.comparing(TK::priority)).toList();
  }
  private Optional<E> tryEatenBetween(T open, T stop, boolean onOpen){
    var expect= !onOpen ? closersForOpener(open.kind()) : openerForCloser(stop.kind());
    for (var e : expect){
      var eater= (onOpen ? spec.openerEaters : spec.closerEaters).get(e);
      if (eater == null){ continue; }
      var ts= betweenExclusive(open, stop, tz.allTokens());
      for (var tok : onOpen ? ts.reversed() : ts){
        Optional<T> frag= eater.apply(tok);
        if (frag.isPresent()){ return Optional.of(onOpen
          ? tz.errFactory().eatenOpenerBetween(open, stop, expect, frag.get(), tok, tz.self())
          : tz.errFactory().eatenCloserBetween(open, stop, expect, frag.get(), tok, tz.self()));
        }
      }
    }
    return Optional.empty();
  }
  @SuppressWarnings("unchecked")
  private int ofRemoval(T remove){
    if (remove.is(tz.sof(), tz.eof())){ return -1; }
    return ofRecovery(tz.tokensForTree().stream().filter(t->t!=remove).toList());
  }
  private int ofRecovery(List<T> tokens){
    var li= tokens.listIterator();
    try{ new TokenTrees<T,TK,E,Tokenizer,Parser,Err>(spec, tz){
      @Override E diagOnBadCloser(T open, T stop){ throw new Out(); }
      @Override E diagOnBadBarrier(T open, T stop){ throw new Out(); }
    }.of(li);}
    catch(Out _){/*eated*/ return li.previousIndex(); }
    return li.nextIndex();
  }
  private List<T> betweenExclusive(T a, T b, List<T> tokens){
    int start= tokens.indexOf(a);
    int end= tokens.indexOf(b);
    assert start < end;
    return tokens.subList(start + 1, end);
  }
  @SuppressWarnings("serial") private static final class Out extends RuntimeException{}
}
