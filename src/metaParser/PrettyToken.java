package metaParser;

public class PrettyToken{
  private PrettyToken(){}
  public static String showText(String s){
    s = s.replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
    return s.length()<=24 ? s : s.substring(0,21)+"...";
  }
  public static <T extends Token<T,TK>, TK extends TokenKind> String show(T t){
    if(!t.tokens().isEmpty()){ return t.kind()+"@"+t.line()+":"+t.column(); }
    return t.kind()+"\""+showText(t.content())+"\"@"+t.line()+":"+t.column();
  }
}