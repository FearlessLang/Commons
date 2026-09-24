package metaParser;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import metaParser.ErrFactory.LikelyCause;
import static metaParser.ErrFactory.LikelyCause.*;

record TreeDiagnostics<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >(TokenTreeSpec<T,TK> spec, Tokenizer tz){

  public E onBadCloser(T open, T badCloser){
    return Optional.<E>empty()
    .or(()->tryEatenBetween(open, badCloser, false))
    .or(()->tryEatenBetween(open, badCloser, true))
    .or(()->tryRemove(open, badCloser, StrayCloser, badCloser))
    .or(()->tryRemove(open, badCloser, StrayOpener, open))
    .orElseGet(()->error(open, badCloser, Unknown));
  }
  E onBadBarrier(T open, T barrier){
    return Optional.<E>empty()
      .or(()->tryEatenBetween(open, barrier, false))
      .or(()->tryRemove(open, barrier, StrayCloser, barrier))
      .or(()->tryRemove(open, barrier, StrayOpener, open))
      .orElseGet(()->error(open, barrier, Unknown));
  }

  private E error(T open, T stop, LikelyCause l){
    return tz.errFactory().groupHalt(open, stop, closersForOpener(open.kind()),l, tz.self());
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
    var expect= !onOpen?closersForOpener(open.kind()):openerForCloser(stop.kind());
    for (var e : expect){
      var eater= (onOpen?spec.openerEaters:spec.closerEaters).get(e);
      if (eater == null){ continue; }
      var ts= betweenExclusive(open, stop,tz.allTokens());
      for (var tok : onOpen?ts.reversed():ts){
        Optional<T> frag= eater.apply(tok);
        if (frag.isPresent()){ return Optional.of(onOpen
          ?tz.errFactory().eatenOpenerBetween(open, stop, expect, frag.get(), tok, tz.self())
          :tz.errFactory().eatenCloserBetween(open, stop, expect, frag.get(), tok, tz.self()));
        }
      }
    }
    return Optional.empty();    
  }
  @SuppressWarnings("unchecked")
  private Optional<E> tryRemove(T open, T stop, LikelyCause l, T remove){
    if(remove.is(tz.sof(),tz.eof())){ return Optional.empty(); }
    List<T> ts= tz.tokensForTree().stream().filter(t->t!=remove).toList();
    int res1= ofRecovery(tz.tokensForTree());
    int res2= ofRecovery(ts);
    var progress= ts.size() == res2 || res2 >= res1 + 5;
    if (!progress){ return Optional.empty(); }
    return Optional.of(error(open,stop,l));    
  }
  private int ofRecovery(List<T> tokens){
    var li= tokens.listIterator();
    try{ new TokenTrees<T,TK,E,Tokenizer,Parser,Err>(spec, tz){
      E diagOnBadCloser(T open,T stop){ throw new Out(); }
      E diagOnBadBarrier(T open,T stop){ throw new Out(); }
    }.of(li);}
    catch(Out _){/*eated*/}
    return li.nextIndex();
  }
  private List<T> betweenExclusive(T a, T b, List<T> tokens){
    int start= tokens.indexOf(a);
    int end= tokens.indexOf(b);
    assert start < end;
    return tokens.subList(start + 1, end);
  }
}