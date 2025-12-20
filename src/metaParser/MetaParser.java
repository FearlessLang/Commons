package metaParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

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
  public abstract Parser make(Span span,List<T> tokens);
  public abstract Err errFactory();
  public MetaParser(Span span, List<T> ts){ this.span= span; this.ts= ts; this.limit= ts.size(); }
  public static <R> R computeInFrame(String frameName, Span s, Supplier<R> r){
    try{ return r.get(); }
    catch(RuntimeException|Error t){ 
      if (!frameName.isEmpty() && t instanceof HasFrames f){ f.addFrame(new Frame(frameName,s)); }
      throw t;
    }
  }  
  public <R> R parseAll(String frameName, Rule<T,TK,E,Tokenizer,Parser,Err,R> r){
    R res; try{ res= r.parse(this.self()); }
    catch(RuntimeException|Error t){ 
      if (!frameName.isEmpty() && t instanceof HasFrames f){ f.addFrame(new Frame(frameName,span())); }
      throw t;
    }
    if(index != limit){ throw errFactory().extraContent(remainingSpan(), "", List.of(),self()); }
    return res;
  }
  public boolean end(){ return limit == index; }
  public int remaining(){ return limit - index; }
  public int index(){ return index; }
  public int limit(){ return limit; }
  public void setIndex(int index){ this.index = index; }

  public Optional<T> peek(){ return peek(0); }
  public Optional<T> peekAbs(int index){
    if (index < this.index || index >= limit){ return Optional.empty(); }
    return Optional.of(ts.get(index));
  }
  private T currentT(){ return index<ts.size()? ts.get(index) : ts.get(index-1); }
  public int currentLine(){ return currentT().line(); }
  public int currentCol(){ return  currentT().column(); }
  public Optional<T> peek(int la){ return peekAbs(index + la); }
  public Optional<T> peekLast(){ return peekLast(0); }
  public Optional<T> peekLast(int la){ return peekAbs((limit-la)-1); }
  
  @SafeVarargs
  public final boolean peek(TK... kinds){
    assert kinds.length > 0;
    var t= peek();
    return t.map(tt->tt.is(kinds)).orElse(false);
  }
  public final T expectAny(String what){
    var t= peek();
    if (t.isEmpty()){ throw errFactory().missing(spanLast(), what, List.of(),self()); }
    var tt= t.get();
    index++;
    return tt;
  }
  public final T expectAnyLast(String what){
    var t= peekLast();
    if (t.isEmpty()){ throw errFactory().missing(spanLast(), what, List.of(),self()); }
    var tt= t.get();
    limit--;
    return tt;
  }
  @SafeVarargs
  public final T expect(String what,TK... kinds){
    var t= peek();
    if (t.isEmpty()){ throw errFactory()
      .missing(spanLast(),what,List.of(kinds),self()); }
    var tt= t.get();
    var allowed= tt.is(kinds);
    if (!allowed){ throw errFactory()
      .missingButFound(firstLeaf(tt).flatMap(this::span).orElse(remainingSpan()),what,tt,List.of(kinds),self()); }
    index++;
    return tt;
  }
  @SafeVarargs
  public final T expectLast(String what, TK... kinds){
    var last= peekLast();
    if (last.isEmpty()){ throw errFactory().missing(span(),what,List.of(kinds),self()); }
    var t= last.get();
    var allowed= t.is(kinds);
    if (!allowed){ throw errFactory().missingButFound(span(),what,t,List.of(kinds),self()); }
    limit--;
    return t;
  }
  @SafeVarargs
  public final void expectEnd(String what,TK... kinds){
    if (end()){ return; }
    throw errFactory().extraContent(remainingSpan(),what,List.of(kinds),self());
  }
  public boolean peekIf(Predicate<T> p){
    return peek().map(p::test).orElse(false);
  }
  @SafeVarargs
  public final boolean peekOrder(Predicate<T>... ps){
    return IntStream.range(0, ps.length)
      .mapToObj(i->peek(i).filter(t->ps[i].test(t)))
      .allMatch(Optional::isPresent);
  }
  public <R> R back(R v){
    if (index == 0) { throw new IllegalStateException("Can not go back since already at start"); }
    index--;
    return v;
    }
  public <R> R fwd(R v){
    if (index == limit){ throw new IllegalStateException("Can not go fwd since already at end"); }
    index++;
    return v;
  }
  public boolean fwdIf(boolean v){
    if(v){ return fwd(true); }
    return false;
  } 
  public <R> R trim(R v){
    if (index == limit){ throw new IllegalStateException("Can not go trim since already empty"); }
    limit--;
    return v;
  }
  public boolean trimIf(boolean v){
    if(v){ return trim(true); }
    return false;
  }
  
  //ParseSplitters
  public <R> R parseGroup(String frameName, Rule<T,TK,E,Tokenizer,Parser,Err,R> r){
    var tsIn= ts.get(index).tokens();
    if(tsIn.isEmpty()){ throw new IllegalStateException("Expected a grouped token (with children), got "+PrettyToken.show(ts.get(index))+"."); }
    var nested= make(spanAround(index,index),tsIn);
    var res= nested.parseAll(frameName,r);
    index++;
    return res;
  }
  public Span spanAround(int low, int high){
    if (ts.isEmpty()){ return span; }
    if (low == ts.size()){ low -= 1; }
    if (high == ts.size() || high < 0){ high = ts.size() - 1; }
    if (low > high){ low = high; }
    assert low >= 0 && low < ts.size():
      "";
    assert high >= 0 && high < ts.size():
      "";
    var here = span(ts.get(low),ts.get(high));
    if (here.isPresent()) { return here.get(); }
    int startLine= this.span.startLine();
    int  startCol= this.span.startCol();
    int   endLine= this.span.endLine();
    int    endCol= this.span.endCol();
    for (int i= low ; i >= 0; i--){//starts with low in case high was the failure point
      var s= span(ts.get(i));
      if (s.isPresent()){
        startLine = s.get().endLine();
        startCol  = s.get().endCol();
        break;
      }
    }
    for (int i= high; i < ts.size(); i++){//starts with high in case low was the failure point
      var s= span(ts.get(i));
      if (s.isPresent()){ 
        endLine = s.get().startLine();
        endCol  = s.get().startCol();
        break;
      }
    }
    return new Span(span.fileName(), startLine, startCol, endLine, endCol);   
  }
  public <R> R parseRemaining(String frameName, Rule<T,TK,E,Tokenizer,Parser,Err,R> r){
    var tsIn= ts.subList(index, limit);
    var s= spanAround(index,limit-1);
    if(tsIn.isEmpty()){ throw new IllegalStateException("Expected a grouped token (with children), got "+PrettyToken.show(ts.get(index))+"."); }
    var nested= make(s,tsIn);
    var res= nested.parseAll(frameName, r);
    index = limit;
    return res;
  }
  
  ///Must advance p.index() by >= 1; will be called until p.end() holds
  ///Returns the amount of last consumed token to drop as separators
  public interface NextCut<
      T extends Token<T,TK>,
      TK extends TokenKind,
      E extends RuntimeException & HasFrames<E>,
      Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
      Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
      Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
    >{ int cutAt(Parser p); }
  public interface Rule<
      T extends Token<T,TK>,
      TK extends TokenKind,
      E extends RuntimeException & HasFrames<E>,
      Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
      Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
      Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>,
      R> { R parse(Parser p); }
  
  public <R> List<R> splitBy(String frameName, NextCut<T,TK,E,Tokenizer,Parser,Err> probe, Rule<T,TK,E,Tokenizer,Parser,Err,R> elem){
    var parts= _splitBy(frameName, probe);
    var res= parts.stream().map(p -> p.parseAll(frameName, elem)).toList();
    index = limit;//only update outer parser if no failures
    return res;
  }
  private final List<Parser> _splitBy(String frameName, NextCut<T,TK,E,Tokenizer,Parser,Err> probe){
    var slice= ts.subList(index, limit);
    var s= spanAround(index,Math.max(index,limit-1));
    Parser splitterParser= make(s,slice);
    var parts= new ArrayList<Parser>();
    while (!splitterParser.end()){
      int start= splitterParser.index();
      int drop= probe.cutAt(splitterParser);
      int end= splitterParser.index();
      ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(false,start, end, drop, frameName);
      var tsi= List.copyOf(slice.subList(start, end-drop));
      var si= splitterParser.spanAround(start,(end-1)-drop);
      parts.add(make(si,tsi));
    }
    return parts;
  }
  public <R> List<R> parseGroupSep(String frameNameOut, String frameNameIn, Rule<T,TK,E,Tokenizer,Parser,Err,R> r,TK open, TK close, NextCut<T,TK,E,Tokenizer,Parser,Err> probe){
    String label= frameNameOut.isEmpty()?frameNameIn:frameNameOut;
    return parseGroup(frameNameOut,p->{
      p.expect(label,open);
      p.expectLast(label,close);
      return p.splitBy(frameNameIn,probe,r);
    });
  }

  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division can not be easily located by looking at a few tokens forward.
  ///Parses a (possibly empty) list of tokens from the start of this parser.
  ///If the probe eats all the tokens, all the tokens are parsed.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public final <R> R parseFrontOrAll(String frameName, NextCut<T,TK,E,Tokenizer,Parser,Err> probe, Rule<T,TK,E,Tokenizer,Parser,Err,R> first){
    var res= parseFront(frameName, true, probe, first);
    if (res.isPresent()){ return res.get(); }
    return parseAll(frameName,first);
  }  
  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division can not be easily located by looking at a few tokens forward.
  ///Parses a (non empty) list of tokens from the start of this parser.
  ///(set parameter emptyAllowed=true to allow a non progressing probe)
  ///Must be a proper division, that is, if the probe eats all the tokens we get an Optional.empty() result.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public final <R> Optional<R> parseFront(String frameName, boolean emptyAllowed, NextCut<T,TK,E,Tokenizer,Parser,Err> probe, Rule<T,TK,E,Tokenizer,Parser,Err,R> first){
    var slice= ts.subList(index, limit);
    var s= spanAround(index, limit-1);
    Parser splitterParser= make(s,slice);
    int start= splitterParser.index();
    int drop= probe.cutAt(splitterParser);
    int end= splitterParser.index();
    ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(emptyAllowed,start, end, drop, frameName);
    if(splitterParser.end()){ return Optional.empty(); }//split not found
    var firstS= splitterParser.spanAround(0,(end-1)-drop);
    Parser firstParser= make(firstS,List.copyOf(slice.subList(0, end-drop)));
    var res= firstParser.parseAll(frameName, first);
    this.index+= end;//mark the tokens as eaten
    return Optional.of(res);
  }
  ///Suitable to divide two non empty lists of tokens, especially if
  ///the division can not be easily located by looking at a few tokens backwards.
  ///Parses a (non empty) list of tokens from the start of this parser.
  ///(set parameter emptyAllowed=true to allow a non progressing probe)
  ///Must be a proper division, that is, if the probe eats all the tokens we get an Optional.empty() result.
  ///The probe accepting all the token signals no prefix.
  ///The probe returns the number of tokens to drop. This number must be between 0 and the number of consumed tokens.  
  public final <R> Optional<R> parseBack(String frameName, boolean emptyAllowed, NextCut<T,TK,E,Tokenizer,Parser,Err> probe, Rule<T,TK,E,Tokenizer,Parser,Err,R> first){
    var slice= ts.subList(index, limit);
    var s= spanAround(index, limit-1);
    Parser splitterParser= make(s,slice);
    int start= splitterParser.limit();
    int drop= probe.cutAt(splitterParser);
    int end= splitterParser.limit();
    ((MetaParser<T,TK,E,Tokenizer,Parser,Err>)splitterParser).checkProbeErrorFront(emptyAllowed,end, start, drop, frameName);
    //TODO: test if the errors make sense by just swapping start/end    
    if(splitterParser.end()){ return Optional.empty(); }//split not found
    var lastS= splitterParser.spanAround(end + drop, limit - 1);
    Parser firstParser= make(lastS,List.copyOf(slice.subList(end + drop, limit)));
    var res= firstParser.parseAll(frameName, first);
    this.limit += end;//mark the tokens as eaten
    return Optional.of(res);
  }
  /** Runs a well-formedness check on the remaining tokens.
   *  - Runs on a separate parser to protect advance/trim on the outer parser.
   *  - The check need NOT consume everything.
   *  - Represent a failed check by throwing E
   */
  public void guard(Consumer<Parser> check){
    var slice= ts.subList(index, limit);
    var s= spanAround(index, limit-1);
    Parser shadow= make(s, slice);
    check.accept(shadow);
  }
  private void checkProbeErrorFront(boolean emptyAllowed, int start, int end, int drop, String frameName){
    if (drop < 0 || start > end - drop){
      var at= spanAround(start, start);
      throw errFactory().badProbeDropIn(frameName, at, start, end, drop,self()); 
      }
    if (!emptyAllowed && start >= end){
      var at = spanAround(start, start);
      throw errFactory().probeStalledIn(frameName, at, start, end,self());
    }
  }
  public enum SplitMode{Skipped,Left,Right}
  public int splitOn(SplitMode split, TK k){ return splitOn(split,t->t.is(k)); }
  public int splitOn(SplitMode split, Predicate<T> p){
    fwdIf(index() != 0 && !end() && split == SplitMode.Right);
    while(!end()){
      var t= expectAny("");
      if (!p.test(t)){ continue; }
      return switch(split){
        case Skipped-> 1;
        case Left-> 0;
        case Right-> back(0);
      };
    }
    return 0;
  }
  @Override
  public String toString(){
    StringBuilder sb = new StringBuilder(128);
    // [0..index)
    appendRange(sb, 0, index);
    sb.append('(').append(index).append(')');
    // [index..limit)
    appendRange(sb, index, limit);
    sb.append('(').append(limit).append(')');
    // [limit..ts.size())
    appendRange(sb, limit, ts.size());
    return sb.toString();
  }

  private void appendRange(StringBuilder sb, int from, int to){
    sb.append('[');
    for(int i=from;i<to;i++){
      if(i>from) sb.append(", ");
      sb.append(PrettyToken.show(ts.get(i)));
    }
    sb.append(']');
  }
  private Optional<T> firstLeaf(List<T> ts){
    for (var t:ts){
      Optional<T> leaf= firstLeaf(t);
      if (leaf.isPresent()){ return leaf; }
    }
    return Optional.empty();
  }
  private Optional<T> firstLeaf(T t){
    if (skip(t)){ return Optional.empty(); } 
    if (t.tokens().isEmpty()){ return Optional.of(t); }
    return firstLeaf(t.tokens());
  }
  private Optional<T> lastLeaf(List<T> ts){
    for (var t:ts.reversed()){
      Optional<T> leaf= lastLeaf(t);
      if (leaf.isPresent()){ return leaf; }
    }
    return Optional.empty();
  }
  private Optional<T> lastLeaf(T t){
    if (skip(t)){ return Optional.empty(); } 
    if (t.tokens().isEmpty()){ return Optional.of(t); }
    return lastLeaf(t.tokens());
  }
  public Span span(){ return span; }
  public Span spanLast(){
    if (limit - 1 < 0){ return span; }
    return span(ts.get(limit - 1)).orElse(span); 
  }
  public Span remainingSpan(){
    if(end()){ return spanLast(); }
    return span(ts.get(index),ts.get(limit-1)).orElse(span); 
  }
  public Optional<Span> span(T low, T high){//not equal to span(List.of(low,high))
    return firstLeaf(low)
      .flatMap(first->lastLeaf(high)
        .map(last->makeSpan(first,last)));
  }
  public Optional<Span> span(List<T> ts){
    return firstLeaf(ts)
      .flatMap(first->lastLeaf(ts)
        .map(last->makeSpan(first,last)));
  }
  public Optional<Span> span(T t){
    return firstLeaf(t)
      .flatMap(first->lastLeaf(t)
        .map(last->makeSpan(first,last)));
  }
  private Span makeSpan(T first, T last){ return Token.makeSpan(span.fileName(), first, last); }
}