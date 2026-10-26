package metaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import utils.Bug;
import utils.Join;
import utils.Range;

public abstract class MetaParser<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >{
  private final Span span;
  private final List<T> ts;
  private int index= 0;
  private int limit;
  public abstract Parser self();
  public abstract boolean skip(T token);
  public abstract Parser make(Span span, List<T> tokens);
  public abstract Err errFactory();
  public MetaParser(Span span, List<T> ts){ this.span= span; this.ts= ts; limit = ts.size(); }
  public <R> R parseAll(String frameName, Rule<Parser,R> r){
    R res; try{ res= r.parse(self()); }
    catch (RuntimeException|Error e){
      if (!frameName.isEmpty() && e instanceof HasFrames<?> f){ f.addFrame(new Frame(frameName, span())); }
      throw e;
    }
    if (index != limit){ throw errFactory().extraContent(remainingSpan(), "", List.of(), self()); }
    return res;
  }
  public boolean end(){ return limit == index; }
  public int index(){ return index; }
  public int limit(){ return limit; }
  public Optional<T> peek(){ return peek(0); }
  public Optional<T> peekAbs(int index){
    if (index<0 || index>=limit){ return Optional.empty(); }
    return Optional.of(ts.get(index));
  }
  public Optional<T> peek(int la){ return peekAbs(index + la); }
  public Optional<T> peekLast(){ return peekAbs(limit - 1); }
  @SafeVarargs @SuppressWarnings("varargs")
  public final boolean peek(TK... kinds){
    assert kinds.length > 0;
    return peekIf(t -> t.is(kinds));
  }
  public T expectAny(String what){
    var t= peek();
    if (t.isEmpty()){ throw errFactory().missing(spanLast(), what, List.of(), self()); }
    index += 1;
    return t.get();
  }
  public T expectAnyLast(String what){
    var t= peekLast();
    if (t.isEmpty()){ throw errFactory().missing(spanLast(), what, List.of(), self()); }
    limit -= 1;
    return t.get();
  }
  @SafeVarargs @SuppressWarnings("varargs")
  public final T expect(String what, TK... kinds){
    var t= peek();
    if (t.isEmpty()){ throw errFactory()
      .missing(spanLast(), what, List.of(kinds), self()); }
    var tt= t.get();
    var allowed= tt.is(kinds);
    if (!allowed){ throw errFactory()
      .missingButFound(leaf(tt, true).flatMap(this::span).orElse(remainingSpan()), what, tt, List.of(kinds), self()); }
    index += 1;
    return tt;
  }
  @SafeVarargs @SuppressWarnings("varargs")
  public final T expectLast(String what, TK... kinds){
    var last= peekLast();
    if (last.isEmpty()){ throw errFactory().missing(span(), what, List.of(kinds), self()); }
    var t= last.get();
    var allowed= t.is(kinds);
    if (!allowed){ throw errFactory().missingButFound(span(), what, t, List.of(kinds), self()); }
    limit -= 1;
    return t;
  }
  @SafeVarargs @SuppressWarnings("varargs")
  public final void expectEnd(String what, TK... kinds){
    if (end()){ return; }
    throw errFactory().extraContent(remainingSpan(), what, List.of(kinds), self());
  }
  public boolean peekIf(Predicate<T> p){
    return peek().map(p::test).orElse(false);
  }
  @SafeVarargs
  public final boolean peekOrder(Predicate<T>... ps){
    return IntStream.range(0, ps.length)
      .mapToObj(i -> peek(i).filter(t -> ps[i].test(t)))
      .allMatch(Optional::isPresent);
  }
  public <R> R back(R v){
    if (index == 0){ throw Bug.of("Cannot go back since already at start"); }
    index -= 1;
    return v;
  }
  public <R> R fwd(R v){
    if (index == limit){ throw Bug.of("Cannot go fwd since already at end"); }
    index += 1;
    return v;
  }
  public boolean fwdIf(boolean v){ return v && fwd(true); }
  //ParseSplitters
  public <R> R parseGroup(String frameName, Rule<Parser,R> r){
    var tsIn= ts.get(index).tokens();
    if (tsIn.isEmpty()){ throw Bug.of("Expected a grouped token (with children), got "+show(ts.get(index))+"."); }
    var nested= make(spanAround(index, index), tsIn);
    var res= nested.parseAll(frameName, r);
    index += 1;
    return res;
  }
  public Span spanAround(int low, int high){
    if (ts.isEmpty()){ return span; }
    if (low == ts.size()){ low -= 1; }
    if (high==ts.size() || high<0){ high = ts.size() - 1; }
    if (low > high){ low = high; }
    assert low>=0 && low<ts.size();
    assert high>=0 && high<ts.size();
    var here= span(ts.get(low), ts.get(high));
    if (here.isPresent()){ return here.get(); }
    var startLine= span.startLine();
    var startCol= span.startCol();
    var endLine= span.endLine();
    var endCol= span.endCol();
    for (int i= low; i >= 0; i -= 1){//starts with low in case high was the failure point
      var s= span(ts.get(i));
      if (s.isPresent()){
        startLine = s.get().endLine();
        startCol = s.get().endCol();
        break;
      }
    }
    for (int i : Range.of(high, ts.size())){//starts with high in case low was the failure point
      var s= span(ts.get(i));
      if (s.isPresent()){
        endLine = s.get().startLine();
        endCol = s.get().startCol();
        break;
      }
    }
    return new Span(span.fileName(), startLine, startCol, endLine, endCol);
  }
  private Parser rest(){ return make(spanAround(index, limit - 1), ts.subList(index, limit)); }
  public <R> R parseRemaining(String frameName, Rule<Parser,R> r){
    if (end()){ throw Bug.of("Expected at least one remaining token."); }
    var res= rest().parseAll(frameName, r);
    index = limit;
    return res;
  }
  ///Must advance p.index() by >= 1; will be called until p.end() holds
  ///Returns the amount of last consumed token to drop as separators
  public interface NextCut<Parser>{ int cutAt(Parser p); }
  public interface Rule<Parser,R>{ R parse(Parser p); }
  public <R> List<R> splitBy(String frameName, NextCut<Parser> probe, Rule<Parser,R> elem){
    var slice= ts.subList(index, limit);
    var splitterParser= rest();
    var parts= new ArrayList<Parser>();
    while (!splitterParser.end()){
      var start= splitterParser.index();
      var drop= probe.cutAt(splitterParser);
      var end= splitterParser.index();
      ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(false, start, end, drop, frameName);
      var tsi= List.copyOf(slice.subList(start, end - drop));
      var si= splitterParser.spanAround(start, Math.max(0, (end - 1) - drop));
      parts.add(make(si, tsi));
    }
    var res= parts.stream().map(p -> p.parseAll(frameName, elem)).toList();
    index = limit;//only update outer parser if no failures
    return res;
  }
  public <R> List<R> parseGroupSep(String frameNameOut, String frameNameIn, Rule<Parser,R> r, TK open, TK close, NextCut<Parser> probe){
    var label= frameNameOut.isEmpty() ? frameNameIn : frameNameOut;
    return parseGroup(frameNameOut, p -> splitInside(p, label, open, close, frameNameIn, probe, r));
  }
  private <R> List<R> splitInside(Parser p, String label, TK open, TK close, String frameNameIn, NextCut<Parser> probe, Rule<Parser,R> r){
    p.expect(label, open);
    p.expectLast(label, close);
    return p.splitBy(frameNameIn, probe, r);
  }
  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division cannot be easily located by looking at a few tokens forward.
  ///Parses a (possibly empty) list of tokens from the start of this parser.
  ///If the probe eats all the tokens, all the tokens are parsed.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public <R> R parseFrontOrAll(String frameName, NextCut<Parser> probe, Rule<Parser,R> first){
    var res= parseFront(frameName, true, probe, first);
    if (res.isPresent()){ return res.get(); }
    return parseAll(frameName, first);
  }
  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division cannot be easily located by looking at a few tokens forward.
  ///Parses a (non empty) list of tokens from the start of this parser.
  ///(set parameter emptyAllowed=true to allow a non progressing probe)
  ///Must be a proper division, that is, if the probe eats all the tokens we get an Optional.empty() result.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public <R> Optional<R> parseFront(String frameName, boolean emptyAllowed, NextCut<Parser> probe, Rule<Parser,R> first){
    var slice= ts.subList(index, limit);
    var splitterParser= rest();
    var start= splitterParser.index();
    var drop= probe.cutAt(splitterParser);
    var end= splitterParser.index();
    ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(emptyAllowed, start, end, drop, frameName);
    if (splitterParser.end()){ return Optional.empty(); }//split not found
    var firstS= splitterParser.spanAround(0, (end - 1) - drop);
    var firstParser= make(firstS, List.copyOf(slice.subList(0, end - drop)));
    var res= firstParser.parseAll(frameName, first);
    index += end;//mark the tokens as eaten
    return Optional.of(res);
  }
  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division cannot be easily located by looking at a few tokens backwards.
  ///Parses a (non empty) list of tokens from the end of this parser.
  ///(set parameter emptyAllowed=true to allow a non progressing probe)
  ///Must be a proper division, that is, if the probe eats all the tokens we get an Optional.empty() result.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public <R> Optional<R> parseBack(String frameName, boolean emptyAllowed, NextCut<Parser> probe, Rule<Parser,R> first){
    var slice= ts.subList(index, limit);
    var splitterParser= rest();
    var start= splitterParser.limit();
    var drop= probe.cutAt(splitterParser);
    var end= splitterParser.limit();
    ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(emptyAllowed, end, start, drop, frameName);
    //TODO: test if the errors make sense by just swapping start/end    
    if (splitterParser.end()){ return Optional.empty(); }//split not found
    var lastS= splitterParser.spanAround(end + drop, start - 1);
    var firstParser= make(lastS, List.copyOf(slice.subList(end + drop, start)));
    var res= firstParser.parseAll(frameName, first);
    limit = index + end;//mark the tokens as eaten
    return Optional.of(res);
  }
  /** Runs a well-formedness check on the remaining tokens.
   *  - Runs on a separate parser to protect advance/trim on the outer parser.
   *  - The check need NOT consume everything.
   *  - Represent a failed check by throwing E
   */
  public void guard(Consumer<Parser> check){
    check.accept(rest());
  }
  private void checkProbeErrorFront(boolean emptyAllowed, int start, int end, int drop, String frameName){
    if (drop<0 || start>end-drop){ throw errFactory().badProbeDropIn(frameName, spanAround(start, start), start, end, drop, self()); }
    if (!emptyAllowed && start>=end){ throw errFactory().probeStalledIn(frameName, spanAround(start, start), start, end, self()); }
  }
  public enum SplitMode{ Skipped, Left, Right }
  public int splitOn(SplitMode split, TK k){ return splitOn(split, t -> t.is(k)); }
  public int splitOn(SplitMode split, Predicate<T> p){
    fwdIf(index()!=0 && !end() && split==SplitMode.Right);
    while (!end()){
      var t= expectAny("");
      if (!p.test(t)){ continue; }
      return switch (split){
        case Skipped -> 1;
        case Left -> 0;
        case Right -> back(0);
      };
    }
    return 0;
  }
  @Override public String toString(){
    // [0..index)
    return range(0, index)+"("+index+")"
    // [index..limit)
      +range(index, limit)+"("+limit+")"
    // [limit..ts.size())
      +range(limit, ts.size());
  }
  private String range(int from, int to){
    return Join.of(ts.subList(from, to).stream().map(this::show), "[", ", ", "]", "[]");
  }
  private Optional<T> leaf(T t, boolean first){
    if (skip(t)){ return Optional.empty(); } 
    if (t.tokens().isEmpty()){ return Optional.of(t); }
    var ts= first ? t.tokens() : t.tokens().reversed();
    return ts.stream().flatMap(c -> leaf(c, first).stream()).findFirst();
  }
  private String show(T t){
    if (!t.tokens().isEmpty()){ return t.kind()+"@"+t.line()+":"+t.column(); }
    var s= t.content().replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    return t.kind()+"\""+(s.length()<=24 ? s : s.substring(0, 21)+"...")+"\"@"+t.line()+":"+t.column();
  }
  public Span span(){ return span; }
  public Span spanLast(){
    if (limit-1 < 0){ return span; }
    return span(ts.get(limit - 1)).orElse(span);
  }
  public Span remainingSpan(){
    if (end()){ return spanLast(); }
    return span(ts.get(index), ts.get(limit - 1)).orElse(span);
  }
  public Optional<Span> span(T low, T high){//not equal to span(List.of(low,high))
    return leaf(low, true)
      .flatMap(first -> leaf(high, false)
        .map(last -> Token.makeSpan(span.fileName(), first, last)));
  }
  public Optional<Span> span(T t){ return span(t, t); }
}
