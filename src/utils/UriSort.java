package utils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import tools.Fs;

public final class UriSort{
  private UriSort(){}

  public static <X> List<X> byFolderThenFile(List<X> xs, Function<X,URI> toUri){
    var res= new ArrayList<>(xs);
    res.sort(Comparator.comparing((X x)->Fs.removeFileNameAllowTop(toUri.apply(x)))
      .thenComparing(x->Fs.fileNameWithExtension(toUri.apply(x)))
      .thenComparing(x->toUri.apply(x).toString()));
    return Collections.unmodifiableList(res);
  }
}