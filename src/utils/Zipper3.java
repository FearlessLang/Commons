package utils;

public interface Zipper3<A,B,C>{
  public interface TriConsumer<A,B,C>{ void accept(A a,B b,C c); }
  public interface TriPredicate<A,B,C>{ boolean test(A a,B b,C c); }
  void forEach(TriConsumer<A,B,C> f);
  Zipper3<A,B,C> filter(TriPredicate<A,B,C> f);
}
