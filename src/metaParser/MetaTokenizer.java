package metaParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public abstract class MetaTokenizer<
    T extends Token<T,TK>,
    TK extends TokenKind,
    E extends RuntimeException & HasFrames<E>,
    Tokenizer extends MetaTokenizer<T,TK,E,Tokenizer,Parser,Err>,
    Parser extends MetaParser<T,TK,E,Tokenizer,Parser,Err>,
    Err extends ErrFactory<T,TK,E,Tokenizer,Parser,Err>
  >{
  private List<TK> kinds;
  private TK sof;
  private TK eof;
  private URI fileName;
  private String input;
  private int pos= 0;
  private int line= 1;
  private int col= 1;
  private List<T> postTokens;
  private List<T> allTokens;
  private List<T> tree;
  private Span base;
  private Err errFactory;
  private boolean frozen= false;
  
  public abstract Tokenizer self();
  public abstract T make(TK kind, String text, int line, int col, List<T> tokens);
  
  private <R> R withFrozen(Supplier<R> body){
    boolean prev = frozen;
    frozen = true;
    try { return body.get(); }
    finally { frozen = prev; }
  }
  private final RuntimeException error(){ 
    return withFrozen(()->errFactory().unrecognizedTextAt(new Span(fileName,line,col,line,col),"",self()));
  }
  private void advanceSingle(int cp){
    if (cp == '\n'){ line++; col = 1; } else { col++; }
  }
  private void advance(String matched){
    matched.codePoints().forEach(this::advanceSingle);
    pos += matched.length();
  }
  private Optional<T> current(TK kind){
    return kind
      .matcher().apply(input, pos)
      .map(text -> make(kind, text, line, col,List.of()));
  }
  private Optional<T> findNext(){
    return kinds.stream()
      .flatMap(k -> current(k).stream())
      .max(Comparator
        .<T>comparingInt(t -> t.content().length())
        .thenComparingInt(t -> -t.kind().priority()));
  }
  private static String normalizeSource(String s){
    if (s == null || s.isEmpty()){ return s; }
    if (s.charAt(0) == '\uFEFF'){ s = s.substring(1); } // Drop BOM if present at start
    s = s.replace("\r\n", "\n").replace('\r', '\n'); // Normalize common newline variants to '\n' CRLF -> LF, lone CR -> LF
    s = s.replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n'); // Unicode separators to LF (LS, PS, NEL)
    return s;
  }
  public Tokenizer input(URI fileName){
    return input(fileName, StandardCharsets.UTF_8);
  }
  public Tokenizer input(URI fileName, Charset charset){
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(charset, "charset");
    if (!"file".equalsIgnoreCase(fileName.getScheme())){
      throw new IllegalArgumentException("Only \"file: URIs\" are supported by input(URI,Charset): " + fileName);
    }
    return input(Path.of(fileName),charset);
    
  }
  public Tokenizer input(Path path){ return input(path,StandardCharsets.UTF_8); }
  public Tokenizer input(Path path, Charset charset){
    Objects.requireNonNull(path, "path");
    String raw; try{raw= Files.readString(path, charset); }
    catch (IOException ex){ throw new UncheckedIOException(ex); }
    return input(path.toUri(), normalizeSource(raw));
  }
  public Tokenizer input(URI fileName, String input){
    assert !frozen : "cannot call .input during .tokenize, .postTokenize, .buildTokenTree";
    this.fileName= Objects.requireNonNull(fileName);
    this.input= Objects.requireNonNull(normalizeSource(input));
    return self();
  }
  public Tokenizer tokenKinds(List<TK> tks, TK sof, TK eof){
    assert !frozen : "cannot call .tokenKinds during .tokenize, .postTokenize, .buildTokenTree";
    assert tks != null && !tks.isEmpty() : "kinds list cannot be empty";
    assert !tks.contains(sof) && !tks.contains(eof) : "do not include SOF/EOF in kinds";
    this.sof= Objects.requireNonNull(sof);
    this.eof= Objects.requireNonNull(eof);
    this.kinds= List.copyOf(tks);
    return self();
  }
  public Tokenizer startingPosition(int line, int col){
    assert !frozen : "cannot call .startingPosition during .tokenize, .postTokenize, .buildTokenTree";
    this.line= line;
    this.col= col;
    return self();
  }
  public Tokenizer setErrFactory(Err errFactory){
    assert !frozen : "cannot call .setErrFactory during .tokenize, .postTokenize, .buildTokenTree";
    this.errFactory= Objects.requireNonNull(errFactory);
    return self();
  }  
  public Tokenizer whiteList(String whiteList){
    assert !frozen : "cannot call .whiteList during .tokenize, .postTokenize, .buildTokenTree";
    assert errFactory!=null: "call method .errFactory before tokenize";
    assert input!=null:      "call method .input before tokenize";
    new Validate(Objects.requireNonNull(whiteList)).of(input);
    return self();
  }
  public Tokenizer tokenize(){
    assert !frozen : "cannot call .tokenize during .tokenize, .postTokenize, .buildTokenTree";
    var tmp= new ArrayList<T>();
    assert input != null:      "call method .input before .tokenize";
    assert kinds != null:      "call method .tokenKinds before .tokenize";
    assert errFactory != null: "call method .errFactory before .tokenize";
    int preLine= line;
    int preCol= col;
    tmp.add(make(sof,"", line, col,List.of()));
    withFrozen(()->{
      while (pos < input.length()){
        var best = findNext().orElseThrow(this::error);
        assert best.content().length() > 0 : "lexer produced a zero-length token for " + best.kind();
        tmp.add(best);
        advance(best.content());
      }return null;});
    tmp.add(make(eof,"", line, col,List.of()));
    allTokens= List.copyOf(tmp);
    base= new Span(fileName,preLine,preCol,line,col);
    assertMonotonic(allTokens);
    return self();
  }
  public Tokenizer postTokenize(TokenProcessor.Map<T,TK,E,Tokenizer,Parser,Err> map){
    assert !allTokens.isEmpty(): "call method .postTokenizer after .tokenize";
    postTokens= withFrozen(()->IntStream.range(0,allTokens.size()).boxed()
      .flatMap(i->map.process(i,allTokens.get(i),self())).toList());
    assertMonotonic(postTokens);
    return self();
  } 
  List<T> tokensForTree(){ return postTokens == null ? allTokens : postTokens; }
  public Tokenizer buildTokenTree(TokenTreeSpec<T,TK> spec){
    assert !frozen : "cannot call .buildTokenTree during .tokenize, .postTokenize, .buildTokenTree";
    assert !allTokens.isEmpty(): "call method .buildTokenTree after .tokenize";
    var tmp= tokensForTree();
    assert tmp.get(0).kind() == sof : "first token must be SOF";
    assert tmp.get(tmp.size()-1).kind() == eof : "last token must be EOF";
    tree = withFrozen(() -> TokenTreeBulder.of(spec, self(),tmp));
    return self();
  }
  public URI fileName(){
    assert fileName != null : "call .input before .fileName";
    return fileName;
  }
  public TK sof(){
    assert sof != null : "call .tokenKinds before .sof";
    return sof;
  }

  public TK eof(){
    assert eof != null : "call .tokenKinds before .eof";
    return eof;
  }

  public Span span(){
    assert base != null : "call .tokenize before .span";
    return base;
  }

  public Err errFactory(){
    assert errFactory != null : "call .setErrFactory before .errFactory";
    return errFactory;
  }

  public List<T> tokenTree(){
    assert tree!=null: "call method .tokenTree after .buildTokenTree";    
    return tree;
  }
  public List<T> postTokens(){
    assert postTokens!=null: "call method .postTokens after .postTokenize";    
    return postTokens;
  }
  public List<T> allTokens(){
    assert allTokens!=null: "call method .allTokens after .tokenize";    
    return allTokens; 
  }
  private void assertMonotonic(List<T> toks){
    int prevLine = -1, prevCol = -1;
    for (var tok : toks){
      int l = tok.line(), c = tok.column();
      assert (l > prevLine) || (l == prevLine && c >= prevCol)
        : "tokens out of order at " + l + ":" + c;
      prevLine = l; prevCol = c;
    }
  }
  class Validate{
    String whiteList; Validate(String whiteList){ this.whiteList= whiteList; }
    int line = 1; int col = 1;
    void of(String src){ src.codePoints().forEach(this::ofSingle); }
    void ofSingle(int cp){
      boolean ok= whiteList.indexOf(cp) >= 0;
      if (!ok){
        var at = new Span(fileName, line, col, line, col);
        throw withFrozen(()->errFactory().illegalCharAt(at, cp,self()));
      }
      if (cp == '\n'){ line++; col = 1; } else { col++; }
    }
  }
}