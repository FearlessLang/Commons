package metaParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

class Out extends RuntimeException{ private static final long serialVersionUID= 1L; }

//package private so we do not need to make private fields or to otherwise protect from the library user
class TokenTrees<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >{
    TokenTreeSpec<T,TK> spec; Tokenizer tokenizer;
    TokenTrees(TokenTreeSpec<T,TK> spec, Tokenizer tokenizer){
      this.spec= spec; this.tokenizer= tokenizer;
    }
  TK closesMe(TK open,TK close){//null for not valid closing
    return spec.openClose.getOrDefault(open,Map.of()).get(close);
  }
  List<TK> closers(TK open){//null for not valid opener
    assert spec.openClose.containsKey(open):
      "";
    return spec.openClose.get(open).keySet()
      .stream().sorted(Comparator.comparing(TK::priority)).toList();
  }
  T of(ListIterator<T> it){
    var first=it.next();
    return new Builder<>(this,new ArrayList<>(List.of(first)),it).build(first);
  }
  Span spanOf(T first, T last){
    return Token.makeSpan(tokenizer.fileName(), first, last);
  }
  TreeDiagnostics<T,TK,E,Tokenizer,Parser,Err> diag(){ return new TreeDiagnostics<T,TK,E,Tokenizer,Parser,Err>(spec, tokenizer); }
  E diagOnBadCloser(T open,T stop){ return diag().onBadCloser(open, stop); }
  E diagOnBadBarrier(T open,T stop){ return diag().onBadBarrier(open, stop); }
}