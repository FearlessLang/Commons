package metaParser;

import java.util.*;
import java.util.stream.Collectors;

/** Given a target name and a set of candidate names, compute a "best" match
 * (if any) and let a Renderer decide how to show it.
 *
 * The default suggest(..) builds a small multi-line suggestion block, but
 * you can plug your own Renderer to format things differently while reusing
 * the same scoring / policy logic.
 *
 * Policy:
 *  - If candidates are few (<= maxScopeToList), list them deterministically.
 *  - Compute similarity (normalized Levenshtein) for each candidate.
 *  - If exactly one candidate clears a strong threshold AND is clearly
 *    better than the runner-up (by margin), that becomes "best".
 *  - If suggestions are ambiguous or weak, best is empty.
 *  - The default suggest(..) returns "" when there is nothing worth showing.
 */
public final class NameSuggester {
  /** If there are this many or fewer names, show the "In scope:" line. */
  private static final int maxScopeToList= 12;
  /** Strong similarity threshold (normalized 0..1). */
  private static final double strongSimilarity= 0.68;
  /** Extra bonus for same first letter (helps stabilize top-1). */
  private static final double sameFirstBonus= 0.03;
  /** Require top-1 to beat top-2 by at least this margin to avoid ambiguity. */
  private static final double margin= 0.08;

  public interface Renderer<R>{
    R render(String target, List<String> candidates, Optional<String> best);
  }  
  private NameSuggester() {}

  /** Just the best name, or empty if nothing confident enough. */
  public static Optional<String> bestName(String name, List<String> candidates){
    return candidates.contains(name)
      ? Optional.of(name)
      : suggest(name, candidates, (_, _, best) -> best);
  }
  /**
   * Default textual rendering:
   *  """
   *  Did you mean "Foo" ?
   *  In scope: "A", "B", "C".
   *  """
   *  Can be the empty string if there is no confident guess and there are too many names in scope.
   */
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
  /**Generic continuation-style API: reuse scoring + policy, customize rendering.
  * @param name       user specified name (that is not found in candidate names) 
  * @param candidates non empty, distinct and sorted candidate names
  * @param renderer   receives (target, candidates, best)
  * @return the result of calling renderer.render
  */
  public static <R> R suggest(String name, List<String> candidates, Renderer<R> renderer){
    assert !name.isEmpty();
    assert !candidates.isEmpty();
    assert candidates.equals(candidates.stream().distinct().sorted().toList()): candidates;
    assert candidates.stream().allMatch(s->!s.isEmpty());
    assert !candidates.contains(name);
    var best= pickBest(name, candidates);
    return renderer.render(name, candidates, best);
  }
  private static Optional<String> pickBest(String t, List<String> candidates){
    boolean tLower= isAllLower(t);
    boolean tUpper= isAllUpper(t);
    char first= Character.toLowerCase(t.charAt(0));
    List<Suggestion> scored= new ArrayList<>(candidates.size());
    for (String c: candidates){
      double base= normalizedLevenshtein(t, c);
      if (first == Character.toLowerCase(c.charAt(0))){ base += sameFirstBonus; }
      var isSimilarCase= (tLower && isAllLower(c)) || (tUpper && isAllUpper(c));
      if (isSimilarCase){ base += 0.01; }
      scored.add(new Suggestion(c, clamp01(base)));
    }
    scored.sort(Comparator
      .<Suggestion>comparingDouble(s -> -s.score)
      .thenComparingInt(s -> Math.abs(s.value.length() - t.length()))
      .thenComparing(s -> s.value));
    var top= scored.get(0);
    double topScore= top.score;
    double runnerUp= scored.size() > 1 ? scored.get(1).score : -1;
    boolean strongEnough= topScore >= strongSimilarity;
    boolean clearMargin= (runnerUp < 0) || (topScore - runnerUp >= margin);
    if (!strongEnough || !clearMargin){ return Optional.empty(); }
    return Optional.of(top.value);
  }
  private record Suggestion(String value,double score){}  
  /** Normalized similarity in [0,1] via Levenshtein edit distance. */
  private static double normalizedLevenshtein(String a, String b) {
    if (a.equals(b)){ return 1.0; }
    int max= Math.max(a.length(), b.length());
    if (max == 0){ return 1.0; }
    int d= levenshtein(a, b);
    return 1.0 - (d / (double)max);
  }
  /** Classic O(n*m) Levenshtein; small and dependency-free. */
  private static int levenshtein(String a, String b) {
    int n= a.length();
    int m= b.length();
    if (n == 0){ return m; }
    if (m == 0){ return n; }
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
  private static boolean isAllLower(String s){
    return s.chars().allMatch(c->!Character.isLetter(c) || Character.isLowerCase(c));
  }
  private static boolean isAllUpper(String s){
    return s.chars().allMatch(c->!Character.isLetter(c) || Character.isUpperCase(c));
  }
  private static double clamp01(double x){ return (x < 0) ? 0 : (x > 1 ? 1 : x); }
}