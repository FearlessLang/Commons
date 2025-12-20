package metaParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public record Message(String msg, int priority){

  private static final int tabWidth= 4;
  private static final int minLineWidth= 3;

  private static Optional<String> optCaretLine(String[] lines, Grouping g, int width){
    if (g.singles.isEmpty()){ return Optional.empty(); }
    return Optional.of(makeCaretLine(lines, g, width));
  }
  public static String of(Function<URI,String> loader, List<Frame> frames, String msg){
    try{ return _of(loader,frames,msg); }
    catch(Throwable e){ 
      String locs = frames.stream().map(f -> f.s().toString()).collect(Collectors.joining("\n"));
      throw new Error("Exception while formatting the following error:\n" + locs + "\n" + msg, e); 
    }
  }
  private static String _of(Function<URI,String> loader, List<Frame> frames, String msg){
    if (frames == null || frames.isEmpty()) return msg;
    List<Frame> contained= ensureContainment(frames);
    List<Frame> visible= trimInvisible(loader, contained);
    Grouping g= group(visible);
    String src= Objects.requireNonNull(loader.apply(g.file()));
    String[] lines= splitLines(src);
    int width= lineNumberWidth(lines.length);
    Optional<String> caretLine= optCaretLine(lines, g, width);
    String body= (g.multiLine() != null)
      ? renderMulti(lines, g, width, caretLine)
      : renderTwo(lines, g, width, caretLine);
    String header= "In file: " + PrettyFileName.displayFileName(g.file());
    String framesLine= "While inspecting " + frames.stream()
      .filter(f->!f.name().isBlank()).map(Frame::name)
      .collect(Collectors.joining(" > "));
    if (framesLine.length() == "While inspecting ".length()){
      framesLine= "While inspecting the file";
    }
    return header + "\n\n" + body + "\n\n" + framesLine + "\n" + msg;
  }
  private static String numbered(String[] lines, int lineNum, int width){
    String raw = get(lines, lineNum);
    String display = expandTabs(raw, tabWidth);
    return padLineNum(lineNum, width) + '|' + ' ' + display;
  }
  private static List<Frame> ensureContainment(List<Frame> fs){
    ArrayList<Frame> out = new ArrayList<>(fs);
    if (out.size() <= 1) return List.copyOf(out);
    for (int i = 0; i < out.size() - 1; i++){
      Span inner = out.get(i).s();
      Span outer = out.get(i+1).s();      
      out.set(i+1, new Frame(out.get(i+1).name(), union(inner, outer)));
    }
    return List.copyOf(out);
  }
  private static Span union(Span a, Span b){
    int startLine = Math.min(a.startLine(), b.startLine());
    int endLine   = Math.max(a.endLine(),   b.endLine());
    int startCol = (a.startLine() == startLine && b.startLine() == startLine)
        ? Math.min(a.startCol(), b.startCol())
        : (a.startLine() == startLine ? a.startCol() : b.startCol());
    int endCol = (a.endLine() == endLine && b.endLine() == endLine)
        ? Math.max(a.endCol(), b.endCol())
        : (a.endLine() == endLine ? a.endCol() : b.endCol());
    return new Span(a.fileName(), startLine, startCol, endLine, endCol);
  }
  private static List<Frame> trimInvisible(Function<URI,String> loader, List<Frame> fs){
    ArrayList<Frame> out = new ArrayList<>(fs.size());
    for (Frame f : fs){
      Span v = shrinkToVisible(loader, f.s());
      out.add(new Frame(f.name(), v));
    }
    return List.copyOf(out);
  }
  private static Span shrinkToVisible(Function<URI,String> loader, Span s){
    String src= Objects.requireNonNull(loader.apply(s.fileName()));
    String[] lines= splitLines(src);
    Pos a= nextVisible(lines, new Pos(s.startLine(), s.startCol()), new Pos(s.endLine(), s.endCol()));
    Pos b= prevVisible(lines, a, new Pos(s.endLine(), s.endCol()));
    return new Span(s.fileName(), a.line, a.col, b.line, b.col);
  }
  private static Pos nextVisible(String[] lines, Pos p, Pos limit){
    int line= clamp(p.line, 1, lines.length), col = Math.max(1, p.col);
    while (beforeOrEqual(line, col, limit)){
      String ln= get(lines, line);
      if (col > ln.length()){ line++; col = 1; continue; }
      char ch= ln.charAt(col - 1);
      if (isVisible(ch)){ return new Pos(line, col); }
      col++;
    }
    return limit;
  }
  private static Pos prevVisible(String[] lines, Pos start, Pos p){
    int line= clamp(p.line, 1, lines.length);
    int col= Math.max(1, p.col);
    while (afterOrEqual(line, col, start)){
      String ln= get(lines, line);
      if (col < 1){ continue; }
      var visible= col <= ln.length() && isVisible(ln.charAt(col - 1)); 
      if (visible){ return new Pos(line, col); }
      col--;
      if (col >= 1){ continue; }
      line--;
      if (line < start.line){ return new Pos(start.line, start.col); }
      col = Math.max(1, get(lines, line).length());
    }
    return new Pos(start.line, start.col);
  }
  private static boolean isVisible(char c){ return c!=' ' && c!='\t' && c!='\n' && c!='\r'; }

  private record Grouping(URI file, List<Span> singles, Span multiLine, int caretLine){}

  private static Grouping group(List<Frame> fs){
    List<Span> spans= fs.stream().map(Frame::s).toList();
    URI file= spans.getLast().fileName();
    ArrayList<Span> leadingSingles= new ArrayList<>();
    for (Span s : spans){ if (s.isSingleLine()) leadingSingles.add(s); else break; }
    //TODO: GPT file reviewed up to here, review the rest
    if (!leadingSingles.isEmpty()){
      int targetLine = leadingSingles.getLast().startLine();
      leadingSingles.removeIf(s -> s.startLine() != targetLine || !s.isSingleLine());
    }

    // If <=3, keep all; else keep first, a middle near avg size, and last — sort outer..inner by length.
    List<Span> chosenSingles = pickUpToThree(leadingSingles);

    // First multiline after singles (if any)
    Span firstMulti = null;
    for (int i = leadingSingles.size(); i < spans.size(); i++){
      if (!spans.get(i).isSingleLine()){ firstMulti = spans.get(i); break; }
    }

    int caretLine = !chosenSingles.isEmpty() ? chosenSingles.getLast().startLine()
                   : (firstMulti != null ? firstMulti.startLine() : spans.getLast().startLine());

    return new Grouping(file, chosenSingles, firstMulti, caretLine);
  }
  private static List<Span> pickUpToThree(List<Span> singles){
    var distinct = singles.stream().distinct().limit(3).toList();
    return List.copyOf(distinct.reversed());
  }
  // ===== Phase 4: caret line construction =======================================
  
  // ===== Phase 5: final rendering ===============================================

  /** 2-line render fallback when there is no multiline span. */
  private static String renderTwo(String[] lines, Grouping g, int width, Optional<String> caretLine){
    String l1 = numberedCaret(lines, g.caretLine(), width);
    return l1 + "\n" + caretLine.orElse("");
  }

  // ----- numbered code line helpers ---------------------------------------------

  private static String elided(int width, int count){
    return repeat(' ', width) + '|' + ' ' + "... " + Math.max(0, count) + " line" + (count==1 ? " ..." : "s ...");
  }


  // ===== small helpers (split, lines, visual columns, padding) ==================

  private static String[] splitLines(String s){ return s.split("\\R", -1); }
  private static String get(String[] lines, int oneBased){
    if (oneBased < 1 || oneBased > lines.length) return "";
    return lines[oneBased-1];
  }
  private static int clamp(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }

  private static int lineNumberWidth(int totalLines){
    int digits = String.valueOf(Math.max(1, totalLines)).length();
    return Math.max(minLineWidth, digits);
  }
  private static String padLineNum(int n, int w){
    String s = Integer.toString(Math.max(0, n));
    int k = Math.max(0, w - s.length());
    return repeat('0', k) + s;
  }
  private static String repeat(char c, int n){
    if (n <= 0) return "";
    StringBuilder sb = new StringBuilder(n);
    for (int i=0;i<n;i++) sb.append(c);
    return sb.toString();
  }

  /** Expand tabs into spaces (tab stops every TAB_WIDTH columns). */
  private static String expandTabs(String s, int tabWidth){
    if (tabWidth <= 0 || s.indexOf('\t') < 0) return s;
    StringBuilder out = new StringBuilder(s.length() + 8);
    int col = 1; // 1-based
    for (int i=0;i<s.length();i++){
      char ch = s.charAt(i);
      if (ch == '\t'){
        int spaces = tabWidth - ((col - 1) % tabWidth);
        for (int k=0;k<spaces;k++){ out.append(' '); }
        col += spaces;
      }else{
        out.append(ch);
        col += 1;
      }
    }
    return out.toString();
  }

  /** Visual column (1-based) at a logical column (expands tabs). */
  private static int visualCol(String rawLine, int logicalCol, int tabWidth){
    if (logicalCol <= 1 || tabWidth <= 0) return Math.max(1, logicalCol);
    int vis = 1;
    int uptoExclusive = Math.max(0, logicalCol - 1);
    int limit = Math.min(uptoExclusive, rawLine.length());
    for (int i = 0; i < limit; i++){
      char ch = rawLine.charAt(i);
      if (ch == '\t'){
        int spaces = tabWidth - ((vis - 1) % tabWidth);
        vis += spaces;
      }else{
        vis += 1;
      }
    }
    return Math.max(1, vis);
  }

  private static int visualDelta(String rawLine, int startCol, int endCol, int tabWidth){
    int aIdx = Math.max(0, startCol - 1);
    int bIdxInclusive = Math.max(aIdx, Math.min(endCol - 1, Math.max(0, rawLine.length() - 1)));
    if (rawLine.isEmpty() || aIdx > bIdxInclusive) return 0;
    int vis = 0;
    for (int i = aIdx; i <= bIdxInclusive; i++){
      char ch = rawLine.charAt(i);
      if (ch == '\t'){
        int spaces = tabWidth - ((vis) % tabWidth); // vis is 0-based width so far
        vis += spaces;
      }else{
        vis += 1;
      }
    }
    return Math.max(0, vis);
  }

  private static boolean beforeOrEqual(int l, int c, Pos limit){
    return l < limit.line || (l == limit.line && c <= limit.col);
  }
  private static boolean afterOrEqual(int l, int c, Pos start){
    return l > start.line || (l == start.line && c >= start.col);
  }
  private record Pos(int line, int col){}

  public static String displayChar(int cp){
    if (cp < 0 || cp > Character.MAX_CODE_POINT){
      return "[Out of character range: 0x" + Integer.toHexString(cp).toUpperCase() + "]";
    }
    if (cp >= 0xD800 && cp <= 0xDFFF){
      String kind= (cp <= 0xDBFF ? "HIGH" : "LOW");
      return "["+kind + " SURROGATE " + String.format(java.util.Locale.ROOT, "\\u%04X", cp)+"]";
    }
    String named= Named.get(cp);
    if (named != null){ return "["+named+"]"; }
    if (cp >= 0x21 && cp <= 0x7E){ return "\""+String.valueOf((char)cp)+"\""; }
    return "\""+toJavaUnicodeEscape(cp)+"\"";
  }
  private static String toJavaUnicodeEscape(int cp){
    if (Character.isBmpCodePoint(cp)){
      return String.format(java.util.Locale.ROOT, "\\u%04X", cp);
    }
    char[] sur= Character.toChars(cp);
    return String.format(java.util.Locale.ROOT, "\\u%04X\\u%04X", (int)sur[0], (int)sur[1]);
  }
  private static final class Named{
    private static final HashMap<Integer,String> M= new HashMap<>();
    static{
      // C0 controls
      String[] c0= {
        "NUL","SOH","STX","ETX","EOT","ENQ","ACK","BEL","BS","TAB","LF","VT","FF","CR",
        "SO","SI","DLE","DC1","DC2","DC3","DC4","NAK","SYN","ETB","CAN","EM","SUB","ESC","FS","GS","RS","US"
      };
      for (int i= 0; i < c0.length; i++) {
        M.put(i, c0[i] + " 0x" + String.format(java.util.Locale.ROOT, "%02X", i));
      }
      // DEL and a couple C1s commonly seen
      M.put(0x7F,"DEL 0x7F");
      M.put(0x85,"NEL 0x85");
      M.put(0x9B,"CSI 0x9B");

      // Spaces and separators
      M.put(0x22, "DQUOTE (\") 0x22");
      M.put(0x27, "SQUOTE (') 0x27");
      M.put(0x5C, "BACKSLASH (\\) 0x5C");
      M.put(0x60, "BACKTICK (`) 0x60");
      M.put(0x20, "SPACE ( ) 0x20");
      M.put(0x00A0,"NBSP 0x00A0");
      M.put(0x1680,"OGHAM SPACE 0x1680");
      M.put(0x2000,"EN QUAD 0x2000");
      M.put(0x2001,"EM QUAD 0x2001");
      M.put(0x2002,"EN SPACE 0x2002");
      M.put(0x2003,"EM SPACE 0x2003");
      M.put(0x2004,"THREE-PER-EM SPACE 0x2004");
      M.put(0x2005,"FOUR-PER-EM SPACE 0x2005");
      M.put(0x2006,"SIX-PER-EM SPACE 0x2006");
      M.put(0x2007,"FIGURE SPACE 0x2007");
      M.put(0x2008,"PUNCTUATION SPACE 0x2008");
      M.put(0x2009,"THIN SPACE 0x2009");
      M.put(0x200A,"HAIR SPACE 0x200A");
      M.put(0x2028,"LS 0x2028");
      M.put(0x2029,"PS 0x2029");
      M.put(0x202F,"NARROW NBSP 0x202F");
      M.put(0x205F,"MEDIUM MATHEMATICAL SPACE 0x205F");
      M.put(0x3000,"IDEOGRAPHIC SPACE 0x3000");

      // Format and bidi controls
      M.put(0x00AD,"SHY 0x00AD");
      M.put(0x061C,"ALM 0x061C");
      M.put(0x200B,"ZWSP 0x200B");
      M.put(0x200C,"ZWNJ 0x200C");
      M.put(0x200D,"ZWJ 0x200D");
      M.put(0x200E,"LRM 0x200E");
      M.put(0x200F,"RLM 0x200F");
      M.put(0x202A,"LRE 0x202A");
      M.put(0x202B,"RLE 0x202B");
      M.put(0x202C,"PDF 0x202C");
      M.put(0x202D,"LRO 0x202D");
      M.put(0x202E,"RLO 0x202E");
      M.put(0x2060,"WJ 0x2060");
      M.put(0x2066,"LRI 0x2066");
      M.put(0x2067,"RLI 0x2067");
      M.put(0x2068,"FSI 0x2068");
      M.put(0x2069,"PDI 0x2069");
      M.put(0xFEFF,"BOM 0xFEFF");
    }
    static String get(int cp){ return M.get(cp); }
  }
  //Format arbitrary token text safely for inline diagnostics.
  public static String displayString(String s){
    // If exactly one Unicode scalar, delegate to displayChar (already bracketed).
    int n = s.codePointCount(0, s.length());
    if (n == 1) return displayChar(s.codePointAt(0));

    StringBuilder out = new StringBuilder(s.length() + 2);
    out.append('\"');
    for (int i = 0; i < s.length(); ){
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      switch (cp) {
        case '\\': out.append("\\\\"); break;
        case '"' : out.append("\\\""); break;
        case '\n': out.append("\\n");  break;
        case '\r': out.append("\\r");  break;
        case '\t': out.append("\\t");  break;
        case '\f': out.append("\\f");  break;
        case '\b': out.append("\\b");  break;
        default:
         if (cp >= 0x20 && cp <= 0x7E) {
             out.append((char)cp);          // printable ASCII
          } else {
            out.append(toJavaUnicodeEscape(cp)); // \ uXXXX (or surrogate pair)
          }
      }
    }
    out.append('\"');
    return out.toString();
  }

private static String makeCaretLine(String[] lines, Grouping g, int width){
 String raw = get(lines, g.caretLine());
 // Only the caret-bearing line is sanitized for display;
 // geometry (columns/lengths) is computed from RAW with tab math.
 String safeDisplay = sanitizeForCaret(expandTabs(raw, tabWidth));

 // decide marks so a single span uses '^'
 List<Span> sps = g.singles();
 int n = Math.min(3, sps.size());
 char[] marks = switch(n){
   case 0 -> new char[0];
   case 1 -> new char[]{'^'};
   case 2 -> new char[]{'-','^'};
   default -> new char[]{'-','~','^'};
 };

 // find rightmost visual column we will draw (1-based), clamp to caret-line length
 int rightMost = 0;
 for (int i = 0; i < n; i++){
   Span s = sps.get(i);
   int aVis = visualCol(raw, s.startCol(), tabWidth);
   int len  = visualDelta(raw, s.startCol(), s.endCol(), tabWidth);
   int bVis = aVis + Math.max(1, len) - 1; // ensure at least 1 column
   rightMost = Math.max(rightMost, bVis);
 }
 rightMost = Math.min(rightMost, Math.max(0, safeDisplay.length())); // belt-and-braces

 char[] carr = new char[Math.max(0, rightMost)];
 for (int i=0;i<carr.length;i++) carr[i] = ' ';

 for (int i = 0; i < n; i++){
   Span s = sps.get(i);
   int aVis = visualCol(raw, s.startCol(), tabWidth);
   int len  = visualDelta(raw, s.startCol(), s.endCol(), tabWidth);
   int a = Math.max(1, aVis);
   int b = Math.max(a, Math.min(aVis + Math.max(1, len) - 1, rightMost));
   for (int c = a; c <= b; c++){
     int idx = c - 1;
     if (idx < carr.length) carr[idx] = marks[i];
   }
 }

 String carets = carr.length == 0 ? "" : new String(carr);
 return repeat(' ', width) + '|' + ' ' + carets;
}

//Numbered line used specifically for the caret-bearing line:
//identical to numbered(), except we sanitize the display to be monospace-safe.
private static String numberedCaret(String[] lines, int lineNum, int width){
 String raw = get(lines, lineNum);
 String display = sanitizeForCaret(expandTabs(raw, tabWidth));
 return padLineNum(lineNum, width) + '|' + ' ' + display;
}

/**
* Make the caret-bearing source line monospace-safe without external deps:
*  - Keep printable ASCII (U+0020..U+007E) as-is
*  - Convert Unicode spaces & zero-width (NBSP/ZW* etc.) to '·' (U+00B7)
*  - Everything else (emoji, CJK, controls, surrogates) → '�' (U+FFFD)
* All replacements are single-column in typical monospace fonts.
*/
private static String sanitizeForCaret(String s){
 StringBuilder out = new StringBuilder(s.length());
 for (int i = 0; i < s.length(); ){
   int cp = s.codePointAt(i);
   i += Character.charCount(cp);
   if (cp >= 0x20 && cp <= 0x7E){ // printable ASCII
     out.append((char)cp);
     continue;
   }
   // Unicode spaces (a small explicit set) and zero-widths
   if (cp == 0x00A0 /* NBSP */ ||
       cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200A) ||
       cp == 0x202F || cp == 0x205F || cp == 0x3000 ||
       cp == 0x200B /* ZWSP */ || cp == 0x200C /* ZWNJ */ || cp == 0x200D /* ZWJ */){
     out.append('\u00B7'); // middle dot
   } else {
     out.append('\uFFFD'); // replacement char
   }
 }
 return out.toString();
}


//helper: push either an elision counter or the single in-between line
private static void addElision(List<String> out, String[] lines, int width, int count, int oneLineNum){
if (count == 1){
 out.add(numbered(lines, oneLineNum, width));
} else if (count > 1){
 out.add(elided(width, count));
}
}

private static String renderMulti(String[] lines, Grouping g, int width, Optional<String> caretLine){
Span group = g.multiLine();
int start = group.startLine();
int end   = group.endLine();
int caret = g.caretLine();

int beforeCount = caret - start - 1;  // lines strictly between start..caret
int afterCount  = end   - caret - 1;  // lines strictly between caret..end

boolean caretAtStart = caret == start;
boolean caretAtEnd   = caret == end;

ArrayList<String> out = new ArrayList<>();

if (caretAtStart){
 // 4 or 3 lines (if afterCount == 0)
 out.add(numberedCaret(lines, caret, width));
 caretLine.ifPresent(out::add);
 addElision(out, lines, width, afterCount, caret + 1);
 out.add(numbered(lines, end, width));
} else if (caretAtEnd){
 // 4 or 3 lines (if beforeCount == 0)
 out.add(numbered(lines, start, width));
 addElision(out, lines, width, beforeCount, caret - 1);
 out.add(numberedCaret(lines, caret, width));
 caretLine.ifPresent(out::add);
} else {
 // caret strictly inside -> up to 6 lines (drops sides with 0 omitted)
 out.add(numbered(lines, start, width));
 addElision(out, lines, width, beforeCount, caret - 1);
 out.add(numberedCaret(lines, caret, width));
 caretLine.ifPresent(out::add);
 addElision(out, lines, width, afterCount, caret + 1);
 out.add(numbered(lines, end, width));
}
return String.join("\n", out);
}
  @SuppressWarnings("unused")
  private static void dbgSpans(String label, java.util.List<metaParser.Frame> fs){
    System.err.println("\n=== " + label + " ===");
    for (int i = 0; i < fs.size(); i++){
      var f = fs.get(i);
      var s = f.s();
      String name = (f.name() == null || f.name().isBlank()) ? "(anon)" : f.name();
      String kind = s.isSingleLine() ? "single" : "multi";
      System.err.printf("#%02d %-30s [%d:%d .. %d:%d] (%s)%n",
       i, name, s.startLine(), s.startCol(), s.endLine(), s.endCol(), kind);
    }
  }
}