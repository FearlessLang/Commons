package metaParser;

import java.util.*;
import java.util.stream.Collectors;
import utils.Range;

public final class NameSuggester {
  private static final int maxScopeToList= 12;
  private static final double strongSimilarity= 0.68;
  private static final double margin= 0.08;

  public interface Renderer<R>{
    R render(String target, List<String> candidates, Optional<String> best);
  }

  public static Optional<String> bestName(String name, List<String> candidates){
    if (candidates.contains(name)){ return Optional.of(name); }
    var base= stripQuotes(name);
    if (!base.equals(name) && candidates.contains(base)){ return Optional.of(base); }
    return suggest(name, candidates, (_, _, best) -> best);
  }

  public static String suggest(String name, List<String> candidates){
    return suggest(name, candidates, (_, cs, best) -> {
      StringBuilder out= new StringBuilder();
      best.ifPresent(b -> out
        .append("Did you mean ")
        .append(Message.displayString(b))
        .append(" ?\n"));
      if (cs.size() <= maxScopeToList){
        out.append("In scope: ")
          .append(cs.stream().map(Message::displayString).collect(Collectors.joining(", ")))
          .append(".\n");
      }
      return out.toString();
    });
  }

  public static <R> R suggest(String name, List<String> candidates, Renderer<R> renderer){
    assert !name.isEmpty();
    assert !candidates.isEmpty();
    assert candidates.equals(candidates.stream().distinct().sorted().toList()): candidates;
    assert candidates.stream().allMatch(s -> !s.isEmpty());
    assert !candidates.contains(name);
    var best= pickBest(name, candidates);
    return renderer.render(name, candidates, best);
  }

  private static Optional<String> pickBest(String t, List<String> candidates){
    var tBase= stripQuotes(t);
    var tScore= simpleName(tBase);
    var tKind= kindOf(tScore);
    var tToks= splitCamel(tScore);

    List<Suggestion> scored= new ArrayList<>(candidates.size());
    for (String c: candidates){
      var cBase= stripQuotes(c);
      var cScore= simpleName(cBase);
      double s= score(tScore, tKind, tToks, cScore);
      scored.add(new Suggestion(c, cScore, s));
    }

    scored.sort(Comparator
      .<Suggestion>comparingDouble(s -> -s.score)
      .thenComparingInt(s -> Math.abs(s.scoreName.length() - tScore.length()))
      .thenComparingInt(s -> s.value.length())
      .thenComparing(s -> s.value));

    var top= scored.get(0);
    double topScore= top.score;
    double runnerUp= scored.size() > 1 ? scored.get(1).score : -1;
    boolean strongEnough= topScore >= strongSimilarity;
    boolean clearMargin= (runnerUp < 0) || (topScore - runnerUp >= margin);
    if (!strongEnough || !clearMargin){ return Optional.empty(); }
    return Optional.of(top.value);
  }

  private static double score(String tScore, Kind tKind, List<String> tToks, String cScore){
    var cKind= kindOf(cScore);
    var cToks= splitCamel(cScore);

    double whole= wholeScore(tScore, cScore);
    double comp= componentScore(tToks, cToks);

    double score= 0.55 * comp + 0.45 * whole;

    if (kindsCompatible(tKind, cKind)){ score += 0.03; }
    else { score -= 0.10; }

    return Math.clamp(score, 0, 1);
  }

  private static boolean kindsCompatible(Kind a, Kind b){
    return a == Kind.OTHER || b == Kind.OTHER || a == b;
  }

  private enum Kind{ TYPE, VALUE, OTHER }
  private static Kind kindOf(String s){
    char c= s.charAt(0);
    if (isAsciiUpper(c)){ return Kind.TYPE; }
    if (isAsciiLower(c)){ return Kind.VALUE; }
    return Kind.OTHER;
  }

  private static double wholeScore(String a, String b){
    if (a.equals(b)){ return 1.0; }
    String al= lowerAscii(a);
    String bl= lowerAscii(b);
    if (al.equals(bl)){ return 0.92; }
    return normalizedLevenshtein(al, bl);
  }

  private static double componentScore(List<String> a, List<String> b){
    if (a.equals(b)){ return 1.0; }
    int n= a.size(), m= b.size();
    if (n == 0 || m == 0){ return 0.0; }

    if (n <= m){
      double best= 0.0;
      for (int start= 0; start <= m - n; start++){
        double sum= 0.0;
        for (int i : Range.of(0,n)){ sum += tokenScore(a.get(i), b.get(start + i)); }
        double avg= sum / n;
        double penalty= 0.04 * start + 0.02 * (m - (start + n));
        best= Math.max(best, avg - penalty);
      }
      return Math.clamp(best, 0, 1);
    }

    double best= 0.0;
    for (int start= 0; start <= n - m; start++){
      double sum= 0.0;
      for (int i : Range.of(0,m)){ sum += tokenScore(a.get(start + i), b.get(i)); }
      double avg= sum / m;
      double penalty= 0.10 * start + 0.08 * (n - (start + m)) + 0.12 * (n - m);
      best= Math.max(best, avg - penalty);
    }
    return Math.clamp(best, 0, 1);
  }

  private static double tokenScore(String a, String b){
    if (a.equals(b)){ return 1.0; }
    String al= lowerAscii(a);
    String bl= lowerAscii(b);

    if (aliases.stream().anyMatch(g->g.contains(al) && g.contains(bl))){ return 0.96; }
    if (al.equals(bl)){ return 0.92; }
    return normalizedLevenshtein(al, bl);
  }

  private record Suggestion(String value, String scoreName, double score){}

  private static String stripQuotes(String s){
    int i= s.length();
    while (i > 0 && s.charAt(i - 1) == '\''){ i--; }
    assert i > 0: s;
    return (i == s.length()) ? s : s.substring(0, i);
  }

  private static String simpleName(String s){
    int i= s.lastIndexOf('.');
    return (i < 0) ? s : s.substring(i + 1);
  }

  /** ASCII CamelCase split with acronym-run handling:
    * HTTPServer -> [HTTP, Server]
    * ABc -> [A, Bc]
    * ABCDe -> [ABC, De]
    * Also splits on non-letters.
    */
  private static List<String> splitCamel(String s){
    int n= s.length();
    if (n == 0){ return List.of(); }

    if (s.chars().noneMatch(c->isAsciiLetter((char)c))){ return List.of(s); }

    List<String> out= new ArrayList<>();
    int start= 0;

    for (int i : Range.of(1,n)){
      char p= s.charAt(i - 1), c= s.charAt(i);

      if (!isAsciiLetter(p)){
        start= i;
        continue;
      }
      if (!isAsciiLetter(c)){
        if (start < i){ out.add(s.substring(start, i)); }
        start= i + 1;
        continue;
      }

      boolean boundary= false;
      if (isAsciiLower(p) && isAsciiUpper(c)){ boundary= true; }
      else if (isAsciiUpper(p) && isAsciiUpper(c) && i + 1 < n){
        char nx= s.charAt(i + 1);
        if (isAsciiLetter(nx) && isAsciiLower(nx)){ boundary= true; }
      }

      if (boundary){
        out.add(s.substring(start, i));
        start= i;
      }
    }

    if (start < n){
      int end= n;
      while (end > start && !isAsciiLetter(s.charAt(end - 1))){ end--; }
      if (start < end){ out.add(s.substring(start, end)); }
    }
    return List.copyOf(out);
  }

  private static double normalizedLevenshtein(String a, String b){
    if (a.equals(b)){ return 1.0; }
    int max= Math.max(a.length(), b.length());
    if (max == 0){ return 1.0; }
    int d= levenshtein(a, b);
    return 1.0 - (d / (double)max);
  }

  private static int levenshtein(String a, String b){
    int n= a.length(), m= b.length();

    int[] prev= new int[m + 1];
    int[] curr= new int[m + 1];
    for (int j= 0; j <= m; j++){ prev[j]= j; }

    for (int i= 1; i <= n; i++){
      curr[0]= i;
      char ca= a.charAt(i - 1);
      for (int j= 1; j <= m; j++){
        char cb= b.charAt(j - 1);
        int cost= (ca == cb) ? 0 : 1;
        curr[j]= Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp= prev;
      prev= curr;
      curr= tmp;
    }
    return prev[m];
  }

  private static boolean isAsciiUpper(char c){ return c >= 'A' && c <= 'Z'; }
  private static boolean isAsciiLower(char c){ return c >= 'a' && c <= 'z'; }
  private static boolean isAsciiLetter(char c){ return isAsciiUpper(c) || isAsciiLower(c); }

  /** Locale-free ASCII fold; allocates only if needed. */
  private static String lowerAscii(String s){
    int n= s.length();
    for (int i= 0; i < n; i++){
      char c= s.charAt(i);
      if (isAsciiUpper(c)){
        char[] cs= s.toCharArray();
        cs[i]= (char)(c + ('a' - 'A'));
        for (i++; i < n; i++){
          c= cs[i];
          if (isAsciiUpper(c)){ cs[i]= (char)(c + ('a' - 'A')); }
        }
        return new String(cs);
      }
    }
    return s;
  }

  private static final List<Set<String>> aliases= aliases();
  private static List<Set<String>> aliases(){
    var res= new ArrayList<Set<String>>();
    aliasGroups.lines().map(l->l.replaceFirst("#.*","").strip()).filter(l->!l.isEmpty())
      .forEach(l->connect(res, new HashSet<>(List.of(lowerAscii(l).split("\\s+")))));
    return List.copyOf(res);
  }
  private static void connect(ArrayList<Set<String>> groups, HashSet<String> group){
    var overlapping= groups.stream().filter(g->!Collections.disjoint(g, group)).toList();
    overlapping.forEach(group::addAll);
    groups.removeAll(overlapping);
    groups.add(group);
  }

  // Put this at the very end so it is easy to tweak.
  private static final String aliasGroups= """
    # One group per line; overlaps CONNECT groups. Tokens are case-insensitive.
    Id ID id
    Uuid UUID uuid
    Uri URI uri
    Url URL url
    Utf UTF utf
    Utf8 UTF8 utf8
    Utf16 UTF16 utf16
    Ascii ASCII ascii

    Http HTTP http
    Https HTTPS https
    Tcp TCP tcp
    Udp UDP udp
    Ip IP ip
    Ipv4 IPv4 ipv4
    Ipv6 IPv6 ipv6
    Dns DNS dns
    Ssl SSL ssl
    Tls TLS tls
    Ssh SSH ssh

    Json JSON json
    Xml XML xml
    Html HTML html
    Css CSS css
    Sql SQL sql
    Db DB db
    Api API api
    Ui UI ui
    Gui GUI gui
    Cli CLI cli

    Jvm JVM jvm
    Jit JIT jit
    Gc GC gc
    Cpu CPU cpu
    Gpu GPU gpu
    Os OS os
    Fs FS fs
    Io IO io

    Pdf PDF pdf
    Csv CSV csv
    Tsv TSV tsv
    Yaml YAML yaml
    Toml TOML toml
    Ini INI ini
    Zip ZIP zip
    Gzip GZIP gzip

    Png PNG png
    Jpg JPG jpg
    Jpeg JPEG jpeg
    Gif GIF gif
    Svg SVG svg
    Bmp BMP bmp
    Webp WEBP webp
    Mp3 MP3 mp3
    Mp4 MP4 mp4
    Wav WAV wav
    Flac FLAC flac

    Sha SHA sha
    Sha1 SHA1 sha1
    Sha256 SHA256 sha256
    Sha512 SHA512 sha512
    Hmac HMAC hmac
    Md5 MD5 md5
    Aes AES aes
    Rsa RSA rsa
    Ecdsa ECDSA ecdsa
    Jwt JWT jwt
    OAuth OAuth2 oauth oauth2
    """;
}
