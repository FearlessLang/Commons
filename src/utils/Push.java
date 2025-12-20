package utils;

import java.util.List;
import java.util.stream.Stream;

public class Push {
  public static <T> List<T> of(List<T> ts1,List<T> ts2){
    return Stream.concat(ts1.stream(),ts2.stream()).toList();
  }
  public static <T> List<? extends T> ofWC(List<? extends T> ts1,List<? extends T> ts2){
    return Stream.concat(ts1.stream(),ts2.stream()).toList();
  }
  public static <T> List<T> of(List<List<T>> ts){
    return _of(ts).toList();
  }
  private static <T> Stream<T> _of(List<List<T>> ts){
    if (ts.size() == 2) { return Stream.concat(ts.get(0).stream(), ts.get(1).stream()); }
    return Stream.concat(ts.get(0).stream(), _of(ts.subList(1, ts.size())));
  }
  public static <T> List<T> of(T t,List<T> ts){
    return Stream.concat(Stream.of(t),ts.stream()).toList();
  }
  public static <T> List<T> of(List<T> ts,T t){
    return Stream.concat(ts.stream(),Stream.of(t)).toList();
  }
}
