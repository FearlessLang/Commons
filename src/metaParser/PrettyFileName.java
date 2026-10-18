package metaParser;

import java.net.URI;
import java.nio.file.Path;

public final class PrettyFileName{
  public static String displayFileName(URI uri){
    var s= "file".equals(uri.getScheme()) ? relativeToCwd(Path.of(uri)) : uri.toString();
    var plain= s.chars().allMatch(c -> c != ' ' && Message.literalChar(c));
    return plain ? s : uri.toASCIIString();
  }
  private static String relativeToCwd(Path p){
    var cwd= Path.of("").toAbsolutePath();
    var under= p.startsWith(cwd) && !p.equals(cwd);
    return under ? cwd.relativize(p).toString() : p.toString();
  }
  public static String sanitizeAscii(String s){
    var sb= new StringBuilder(s.length());
    s.codePoints().forEach(cp -> sb.append(Message.literalChar(cp) ? (char)cp : '?'));
    return sb.toString();
  }
}
