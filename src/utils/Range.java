package utils;

import java.util.List;
import java.util.stream.IntStream;

public class Range {
  public static Iterable<Integer> of(int from, int to){
    return IntStream.range(from,to)::iterator;
  }
  public static Iterable<Integer> of(List<?>es){
    return IntStream.range(0,es.size())::iterator;
  }
}
