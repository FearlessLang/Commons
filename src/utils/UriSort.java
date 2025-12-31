package utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class UriSort{
  private UriSort(){}

  public static <X,U> List<X> byFolderThenFile(List<X> xs, Function<X,U> toUri){
    var res= new ArrayList<>(xs);
    res.sort(Comparator.comparing((X x)->folder(toUri.apply(x)))
      .thenComparing(x->file(toUri.apply(x)))
      .thenComparing(x->toUri.apply(x).toString()));
    return Collections.unmodifiableList(res);
  }

  private static String folder(Object uri){
    String s= uri.toString();
    int i= s.lastIndexOf('/');
    return i < 0 ? "" : s.substring(0,i+1);
  }
  private static String file(Object uri){
    String s= uri.toString();
    int i= s.lastIndexOf('/');
    return i < 0 ? s : s.substring(i+1);
  }
}