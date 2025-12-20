package metaParser;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public interface TokenProcessor<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >{
  Stream<T> process(int index, T token, Tokenizer tokenizer);
  class Map<
      T extends Token<T,TK>,
      TK extends TokenKind,
      E extends RuntimeException & HasFrames<E>,
      Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
      Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
      Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
    >{
    public Map<T,TK,E,Tokenizer,Parser,Err> put(TK kind,TokenProcessor<T,TK,E,Tokenizer,Parser,Err> p){
      assert !map.containsKey(kind): "token kind "+kind+" already in the processMap";
      map.put(kind, p);
      return this;
    }
    public Map<T,TK,E,Tokenizer,Parser,Err> putStr(TK kind, Function<String,E> f, String str, String frame){
      return put(kind, (_,t,tz) -> {
        String head= t.content().isBlank()
          ? "Unrecognized text."
          : "Unrecognized text " + Message.displayString(t.content()) + ".";
        throw f.apply(head+"\n"+str).addFrame(frame,t.span(tz.fileName())); 
        });
    }

    private final TokenProcessor<T,TK,E,Tokenizer,Parser,Err> identity= (_,t,_)->Stream.of(t);
    public Stream<T> process(int i, T t, Tokenizer tk){
      return Objects.requireNonNull(
          map.getOrDefault(t.kind(), identity).process(i, t, tk),
          "TokenProcessors must not return null");
    }
    private final LinkedHashMap<TK,TokenProcessor<T,TK,E,Tokenizer,Parser,Err>> map= new LinkedHashMap<>();
  }
}