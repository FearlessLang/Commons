package utils;

import java.util.List;

public class Pop {
  public static <T> List<T> left(List<T>ts){
    //return ts.stream().skip(1).toList();
    return ts.subList(1,ts.size());
  }
  public static <T> List<T> right(List<T>ts){
    return ts.subList(0,ts.size()-1);
  }
}
