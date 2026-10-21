package metaParser;

import java.net.URI;

import utils.Join;

public final class PrettyFileName{
  public static String displayFileName(URI uri){ return sanitizeAscii(uri.toString()); }
  public static String sanitizeAscii(String s){
    return Join.of(s.codePoints().mapToObj(c -> Message.literalChar(c) && c!='{' ? Character.toString(c) : "{"+Message.uPlus(c)+"}"), "", "", "", "");
  }
}
