package utils;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public interface Zipper2<A, B> {
  void forEach(BiConsumer<A, B> f);

  <R> Stream<R> map(BiFunction<A, B, R> f);

  <R> Stream<R> parallelMap(BiFunction<A, B, R> f);

  <R> Stream<R> flatMap(BiFunction<A, B, Stream<R>> f);

  <R> Stream<R> filterMap(BiFunction<A, B, Optional<R>> f);

  Zipper2<A, B> filter(BiPredicate<A, B> f);

  <R> R fold(Streams.Acc2<R, A, B> folder, R initial);

  boolean anyMatch(BiPredicate<A, B> test);

  boolean allMatch(BiPredicate<A, B> test);

  boolean allMatchParallel(BiPredicate<A, B> test);
}
