package metaParser;

import java.net.URI;
import java.util.stream.Collectors;

public final class PrettyFileName{
  public static String displayFileName(URI uri){ return sanitizeAscii(uri.toString()); }
  public static String sanitizeAscii(String s){
    return s.codePoints().mapToObj(c -> Message.literalChar(c) && c!='{' ? Character.toString(c) : "{"+Message.uPlus(c)+"}").collect(Collectors.joining());
  }
}
