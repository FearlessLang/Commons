package metaParser;

import java.util.Collection;
import java.util.List;

public interface ErrFactory<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>  
  >{
  // == Lexer / general ========================================================

  ///Illegal character at span.
  E illegalCharAt(Span at, int cp, Tokenizer tokenizer);

  ///Unrecognized text (no token matched).
  E unrecognizedTextAt(Span at, String what, Tokenizer tokenizer);

  // == Tree-builder ================================================
  ///Could not legally close the group started by 'open' when encountering 'stop'.
  ///Implementations can distinguish the kind of stop by inspecting stop.kind():
  /// - a wrong closer kind
  /// - a barrier token
  /// - _EOF
  E groupHalt(
    T open, T stop, Collection<TK> expectedClosers,
    LikelyCause likely,
    Tokenizer tokenizer);
  public enum LikelyCause {
    Unknown,
    StrayOpener,   ///Dropping the opener ('open') makes parsing advance significantly.
    StrayCloser,   ///Dropping the closer ('stop') makes parsing advance significantly.
    MissingCloser, ///Inserting an expected closer for 'open' just before 'stop' makes parsing advance significantly.
    MissingOpener, ///Inserting the matching opener somewhere before the closer ('stop') makes parsing advance significantly.
  }

  ///The expected closer for 'open' appears hidden inside another token
  ///(example: string or comment) between 'open' and 'stop'.
  ///hiddenContainer is the whole hidden token; hiddenFragment pinpoints the slice
  ///that contains the closer.
  E eatenCloserBetween(
    T open, T stop, Collection<TK> expectedClosers,
    T hiddenFragment, T hiddenContainer,
    Tokenizer tokenizer);
  
  ///The expected opener for 'stop' appears hidden inside another token 
  ///(example: string or comment) between 'open' and 'stop'.
  ///hiddenContainer is the whole hidden token; hiddenFragment pinpoints the slice
  ///that contains the opener.
  E eatenOpenerBetween(
    T open, T stop, Collection<TK> expectedClosers,
    T hiddenFragment, T hiddenContainer,
    Tokenizer tokenizer);

  // == Parser-time diagnostics ================================================

  ///Required element missing at span; expected tokens listed.
  E missing(Span at, String what, List<TK> expectedTokens, Parser parser);

  ///Required element missing, but another token was found instead; expected tokens listed.
  E missingButFound(Span at, String what, T found, Collection<TK> expectedTokens, Parser parser);

  ///Extra tokens remain within the current parsed group.
  E extraContent(Span from, String what, Collection<TK> expectedTerminatorTokens, Parser parser);

  ///A probing parse strategy stalled while scanning a group.
  E probeStalledIn(String groupLabel, Span at, int startIdx, int endIdx, Parser parser);

  ///A probing parse strategy returned an invalid drop value.
  E badProbeDropIn(String groupLabel, Span at, int startIdx, int endIdx, int drop, Parser parser);
}