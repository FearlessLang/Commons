package utils;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class OneOr{
  public static <T> Optional<T> opt(String err, Stream<T> ts){
    return ts.reduce((_,_)->{ throw new OneOrException(err); });
  }
  public static <T> T of(String err, Stream<T> ts){
    return ts
      .reduce((_,_)->{ throw new OneOrException(err); })
      .orElseThrow(()-> new OneOrException(err));
  }
  public static <T> T of(Supplier<RuntimeException> err, Stream<T> ts){
    return ts
      .reduce((_,_)->{throw err.get();})
      .orElseThrow(err);
  }
  @SuppressWarnings("serial")
  public static class OneOrException extends RuntimeException {
    public OneOrException(String err) { super(err); }
  }
}
