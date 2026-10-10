package metaParser;

import java.net.URI;
import java.nio.file.Path;

import tools.Fs;

public final class PrettyFileName{
  public static String displayFileName(URI uri){
    var s= "file".equals(uri.getScheme()) ? relativeToCwd(Path.of(uri)) : uri.toString();
    var plain= s.chars().allMatch(c->c != ' ' && c != '\n' && Fs.allowed.indexOf(c) != -1);
    return plain ? s : uri.toASCIIString();
  }
  private static String relativeToCwd(Path p){
    var cwd= Path.of("").toAbsolutePath();
    var under= p.startsWith(cwd) && !p.equals(cwd);
    return under ? cwd.relativize(p).toString() : p.toString();
  }
  public static String sanitizeAscii(String s){
    var sb= new StringBuilder(s.length());
    s.codePoints().forEach(cp->sb.append(cp >= 0x20 && cp <= 0x7E ? (char)cp : '?'));
    return sb.toString();
  }
}
