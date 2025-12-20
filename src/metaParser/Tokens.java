package metaParser;

import java.util.List;

public record Tokens<T extends Token<T,TK>, TK extends TokenKind>
  (Span span, List<T> tokens, List<T> hiddenTokens, List<T> tokenTree){}
