package utils;

import java.util.function.BiFunction;
import java.util.stream.Stream;

public interface Zipper2<A, B> {
  <R> Stream<R> map(BiFunction<A, B, R> f);
}
