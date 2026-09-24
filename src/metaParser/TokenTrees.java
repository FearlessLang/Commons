package metaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

@SuppressWarnings("serial") class Out extends RuntimeException{}

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
  T of(ListIterator<T> it){
    var first=it.next();
    return new Builder<>(this,new ArrayList<>(List.of(first)),it).build(first);
  }
  E diagOnBadCloser(T open,T stop){ return new TreeDiagnostics<T,TK,E,Tokenizer,Parser,Err>(spec, tokenizer).onBadCloser(open, stop); }
  E diagOnBadBarrier(T open,T stop){ return new TreeDiagnostics<T,TK,E,Tokenizer,Parser,Err>(spec, tokenizer).onBadBarrier(open, stop); }
}