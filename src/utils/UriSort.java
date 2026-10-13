package utils;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import tools.Fs;

public final class UriSort{
  public static <X> List<X> byFolderThenFile(List<X> xs, Function<X,URI> toUri){
    return xs.stream().sorted(Comparator.comparing((X x)->Fs.removeFileNameAllowTop(toUri.apply(x).toString()))
      .thenComparing(x->Fs.fileNameWithExtension(toUri.apply(x).toString()))
      .thenComparing(x->toUri.apply(x).toString())).toList();
  }
}