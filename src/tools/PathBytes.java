package tools;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/// On Linux and macOS a file name is bytes, and a Path keeps them; Java turns a name into text only
/// with the file name encoding of the system. The file URI of a path writes every byte, so it
/// converts between a Path and its bytes with no text in between.
public final class PathBytes{
  public static byte[] of(Path p){
    assert p.isAbsolute() && !Fs.isWindows();
    var raw= p.toUri().getRawPath();
    if (raw.length() > 1 && raw.endsWith("/")){ raw = raw.substring(0, raw.length()-1); }
    var res= new ByteArrayOutputStream();
    for (int i= 0; i < raw.length(); i += 1){
      if (raw.charAt(i) != '%'){ res.write(raw.charAt(i)); continue; }
      res.write(HexFormat.fromHexDigits(raw, i+1, i+3));
      i += 2;
    }
    return res.toByteArray();
  }
  public static Path path(byte[] bytes){
    assert !Fs.isWindows();
    if (bytes.length == 0){ return Path.of(""); }
    if (bytes[0] != '/'){ return Path.of("/").relativize(path(concat(new byte[]{'/'}, bytes))); }
    var res= new StringBuilder("file://");
    for (var b : bytes){ escape(res, b); }
    return Path.of(URI.create(res.toString()));
  }
  private static void escape(StringBuilder res, byte b){
    var c= (char)(b & 0xFF);
    var plain= c == '/' || c == '-' || c == '.' || c == '_' || c == '~' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    if (plain){ res.append(c); return; }
    res.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
  }
  private static byte[] concat(byte[] a, byte[] b){
    var res= Arrays.copyOf(a, a.length+b.length);
    System.arraycopy(b, 0, res, a.length, b.length);
    return res;
  }
  /// The JVM hands main its arguments decoded as text, and bytes not valid in the file name encoding
  /// are lost. On Linux /proc/self/cmdline holds the arguments as the bytes the process received,
  /// separated by a zero byte, and the arguments of main are the last ones.
  //Platform primitives do what their specification says: /proc/self/cmdline exists on Linux.
  public static Path arg(String[] args, int i){
    if (!Fs.isLinux()){ return Path.of(args[i]); }
    var all= split(Fs.of(() -> Files.readAllBytes(Path.of("/proc/self/cmdline"))));
    var raw= all.get(all.size() - args.length + i);
    assert new String(raw, Charset.forName(System.getProperty("sun.jnu.encoding"))).equals(args[i]);
    return path(raw);
  }
  private static List<byte[]> split(byte[] cmdline){
    var res= new ArrayList<byte[]>();
    var start= 0;
    for (int i= 0; i < cmdline.length; i += 1){
      if (cmdline[i] != 0){ continue; }
      res.add(Arrays.copyOfRange(cmdline, start, i));
      start = i + 1;
    }
    return res;
  }
}
