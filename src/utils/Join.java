package utils;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

///Utility used all around the code base
public final class Join{
  public static String of(Stream<?> stream, String start, String sep, String end, String empty){ return of(stream.toList(),start,sep,end,empty); }
  public static String of(List<?> list, String start, String sep, String end, String empty){
    if (list.isEmpty()){ return empty; }
    return list.stream().map(Object::toString).collect(Collectors.joining(sep, start, end));
  }
  public static String of(Stream<?> stream, String start, String sep, String end){ return of(stream.toList(),start,sep,end); }
  public static String of(List<?> list, String start, String sep, String end){
    assert !list.isEmpty();
    return list.stream().map(Object::toString).collect(Collectors.joining(sep, start, end));
  }
}