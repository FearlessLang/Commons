package metaParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

//package private so we do not need to make private fields or to otherwise protect from the library user
record Builder<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >(
  TokenTrees<T,TK,E,Tokenizer,Parser,Err> ctx,
  ArrayList<T> output,
  ListIterator<T> i
  ){
  Token<T,TK> myOpen(){ return output.getFirst(); } 
  boolean commit(T open, T t){
    TK end= ctx.closesMe(open.kind(),t.kind());
    if (end != null){ return output.add(t); }//the group must contain the opener and the closer
    //so we can have tokens like "{ a, b, c }" but also "(2,4]", where the open/close token are relevant
    boolean opener= ctx.spec.openClose.keySet().contains(t.kind());
    if (opener){
      T tree= new Builder<>(ctx,new ArrayList<>(List.of(t)),i).build(t);
      return !output.add(tree);
    }
    boolean regular= !ctx.spec.closers.contains(t.kind());
    if(regular){ return !output.add(t); }
    throw ctx.diagOnBadCloser(open,t);
  }
  T build(T open){
    T current= open;
    while (i.hasNext()){
      current= i.next();
      var barrier= ctx.spec.isBarrierFor(current, open);
      if (barrier){ throw ctx.diagOnBadBarrier(open, current); }
      if (!commit(open,current)){ continue; }
      TK groupKind= ctx.closesMe(open.kind(), current.kind());
      return ctx.tokenizer.make(groupKind,"",
        myOpen().line(),
        myOpen().column(),
        Collections.unmodifiableList(output));
      }
    throw new Error("Unreachable");
  }
}