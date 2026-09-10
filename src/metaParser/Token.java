package metaParser;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

public interface Token<T extends Token<T,TK>, TK extends TokenKind> {
  TK kind();
  String content();
  int line();
  int column();
  List<T> tokens();
  @SuppressWarnings("unchecked")
  default boolean is(TK...kinds){
    assert kinds.length > 0;
    return Stream.of(kinds).anyMatch(this::is);
  }
  default boolean is(TK k){ return kind().equals(k); }
  default Span span(URI fileName){ return Token.makeSpan(fileName,this,this); }
  static Span makeSpan(URI fileName, Token<?,?> first, Token<?,?> last){
    int l = last.line();
    int c = last.column();
    for (int i = 0; i < last.content().length();) {
      int cp= last.content().codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '\n'){ l += 1; c = 1; } else { c += 1; }
    }
    if (l == last.line()){ return new Span(fileName,first.line(),first.column(),l,Math.max(first.column(), c - 1)); }
    return new Span(fileName, first.line(),first.column(),l,Math.max(1,c - 1));
  }
}