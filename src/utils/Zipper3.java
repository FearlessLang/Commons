package utils;

import java.util.Optional;
import java.util.stream.Stream;

import utils.Streams.Acc3;

public interface Zipper3<A,B,C>{
  public interface TriConsumer<A,B,C>{ void accept(A a,B b,C c); }
  public interface TriFunction<A,B,C,R>{ R apply(A a,B b,C c); }
  public interface TriPredicate<A,B,C>{ boolean test(A a,B b,C c); }
  void forEach(TriConsumer<A,B,C> f);
  <R> Stream<R> map(TriFunction<A,B,C,R> f);
  <R> Stream<R> parallelMap(TriFunction<A,B,C,R> f);
  <R> Stream<R> flatMap(TriFunction<A,B,C,Stream<R>> f);
  <R> Stream<R> filterMap(TriFunction<A,B,C,Optional<R>> f);
  Zipper3<A,B,C> filter(TriPredicate<A,B,C> f);
  <R> R fold(Acc3<R,A,B,C> folder, R initial);
  boolean anyMatch(TriPredicate<A,B,C> test);
  boolean allMatch(TriPredicate<A,B,C> test);
  boolean allMatchParallel(TriPredicate<A,B,C> test);
}