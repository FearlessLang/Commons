package metaParser;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PrettyFileName{//This is all gpt unchecked heuristic
  public static String displayFileName(URI uri) {
    if (uri == null) return "(unknown)";
    try {
      uri = uri.normalize();
      String scheme = uri.getScheme();

      // Handle jar:file:/...!/entry
      if ("jar".equalsIgnoreCase(scheme)) {
        String ssp = uri.getSchemeSpecificPart();
        int bang = ssp.indexOf("!/");
        if (bang > 0) {
          URI inner = URI.create(ssp.substring(0, bang));
          String entry = ssp.substring(bang + 2);
          String innerDisp = displayFileName(inner); // recurse
          return shorten(innerDisp + "!" + entry, 80);
        }
        return shorten(uri.toString(), 80);
      }

      // Treat file: (or no scheme) as a filesystem path
      if (scheme == null || "file".equalsIgnoreCase(scheme)) {
        Path p = Paths.get(uri).toAbsolutePath().normalize();

        // Prefer path relative to CWD if it shortens
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path rel = p.startsWith(cwd) ? cwd.relativize(p) : p;

        // Or collapse to ~/... when under home
        String homeProp = System.getProperty("user.home");
        if (homeProp != null && !homeProp.isBlank()) {
          Path home = Paths.get(homeProp).toAbsolutePath().normalize();
          if (p.startsWith(home)) {
            Path tail = home.relativize(p);
            return shorten("~/" + toUnix(tail), 80);
          }
        }
        return shorten(toUnix(rel), 80);
      }

      // Fallback: other schemes as-is (http:, mem:, dbg:, etc.)
      return shorten(uri.toString(), 80);

    } catch (Exception e) {
      // Never let filename rendering break error reporting
      return "(unknown)";
    }
  }

  // --- helpers ---

  private static String toUnix(Path path) {
    return path.toString().replace('\\', '/');
  }

  /** Elide the middle of long paths, preserving basename (maxLen includes ellipsis). */
  private static String shorten(String s, int maxLen) {
    if (s == null) return "(unknown)";
    if (s.length() <= maxLen) return s;

    // Split on '/', keep leading '/' if present
    boolean abs = s.startsWith("/");
    String[] parts = s.split("/");
    int n = parts.length;

    // Handle edge-y cases
    if (n <= 2) return "..." + tail(s, maxLen - 1);

    String first = parts[abs ? 1 : 0];      // skip empty segment for absolute paths
    String last = parts[n - 1];
    String penult = parts[n - 2];

    String candidate = (abs ? "/" : "") + first + "/.../" + penult + "/" + last;
    if (candidate.length() <= maxLen) return candidate;

    // Try keeping just the tail
    String tail2 = penult + "/" + last;
    String t2 = (abs ? "/.../" : ".../") + tail2;
    if (t2.length() <= maxLen) return t2;

    // Last resort: ensure basename is visible
    String onlyLast = (abs ? "/.../" : ".../") + last;
    if (onlyLast.length() <= maxLen) return onlyLast;
    // Trim basename from the left if still too long
    String base = last;
    if (base.length() > maxLen - 1) {
      base = base.substring(base.length() - (maxLen - 1));
    }
    return "..." + base;
  }

  private static String tail(String s, int maxLen) {
    if (s.length() <= maxLen) return s;
    return s.substring(s.length() - maxLen);
  }
}