package metaParser;

import java.util.List;

class TokenTreeBulder{
  static <
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >
  List<T> of(TokenTreeSpec<T,TK> spec, Tokenizer tokenizer, List<T> tokens){
    return new TokenTrees<T,TK,E,Tokenizer,Parser,Err>(spec, tokenizer).of(tokens.listIterator()).tokens();
  } 
  static <
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >
  int ofRecovery(TokenTreeSpec<T,TK> spec, Tokenizer tokenizer, List<T> tokens){
    var li= tokens.listIterator();
    try{ new TokenTrees<T,TK,E,Tokenizer,Parser,Err>(spec, tokenizer){
      E diagOnBadCloser(T open,T stop){ throw new Out(); }
      E diagOnBadBarrier(T open,T stop){ throw new Out(); }
    }.of(li);}
    catch(Out _){/*eated*/}
    return li.nextIndex();
  } 
}