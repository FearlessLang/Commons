package metaParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import utils.Bug;

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
  T build(T open){
    while (i.hasNext()){
      T current= i.next();
      if (ctx.spec.isBarrierFor(current, open)){ throw ctx.diagOnBadBarrier(open, current); }
      TK groupKind= ctx.closesMe(open.kind(), current.kind());
      if (groupKind != null){
        output.add(current);//the group must contain the opener and the closer
        //so we can have tokens like "{ a, b, c }" but also "(2,4]", where the open/close token are relevant
        return ctx.tokenizer.make(groupKind,"",open.line(),open.column(),Collections.unmodifiableList(output));
      }
      if (ctx.spec.openClose.containsKey(current.kind())){
        output.add(new Builder<>(ctx,new ArrayList<>(List.of(current)),i).build(current));
        continue;
      }
      if (ctx.spec.closers.contains(current.kind())){ throw ctx.diagOnBadCloser(open,current); }
      output.add(current);
    }
    throw Bug.unreachable();
  }
}