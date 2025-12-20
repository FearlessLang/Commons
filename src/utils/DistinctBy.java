package utils;

import java.util.HashSet;
import java.util.stream.Gatherer;

public interface DistinctBy<T,K> extends Gatherer.Integrator<HashSet<K>,T,T> {
  static <T,K> Gatherer<T,HashSet<K>,T> of(DistinctBy<T,K> keyFn) { return Gatherer.ofSequential(HashSet::new, keyFn); }

  K by(T t);

  @Override default boolean integrate(HashSet<K> state, T t, Gatherer.Downstream<? super T> downstream) {
    if (downstream.isRejecting()) { return false; }
    if (state.add(by(t))) { downstream.push(t); }
    return true;
  }
}
