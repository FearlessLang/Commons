package metaParser;

public final class PrettyToken{
  public static <T extends Token<T,TK>, TK extends TokenKind> String show(T t){
    if(!t.tokens().isEmpty()){ return t.kind()+"@"+t.line()+":"+t.column(); }
    var s= t.content().replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
    return t.kind()+"\""+(s.length()<=24 ? s : s.substring(0,21)+"...")+"\"@"+t.line()+":"+t.column();
  }
}