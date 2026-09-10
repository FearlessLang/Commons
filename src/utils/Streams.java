package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Streams {
  @SafeVarargs
  public static <T> Stream<T> of(Stream<T>...ss){ return Stream.of(ss).flatMap(s->s); }
  @SafeVarargs
  public static <T> Stream<T> ofWC(Stream<T>...ss){ return Stream.of(ss).flatMap(s->s); }

  public static <A,B> Zipper2<A,B> zip(List<A> as, List<B> bs){
    assert as.size()==bs.size();
    return new ListZipper<>(as,bs);
  }
  public static <A,B> Zipper2<A,B> zip(A[] as, B[] bs){
    assert as.length==bs.length;
    return new ArrayZipper<>(as,bs);
  }

  private record ListZipper<A,B>(List<A> as, List<B> bs) implements Zipper2<A, B> {
    @Override public void forEach(BiConsumer<A, B> f){
      IntStream.range(0, as.size()).forEach(i->f.accept(as.get(i), bs.get(i)));
    }
    @Override public <R> Stream<R> map(BiFunction<A, B, R> f){
      return IntStream.range(0, as.size()).mapToObj(i->f.apply(as.get(i),bs.get(i)));
    }
    @Override public <R> Stream<R> parallelMap(BiFunction<A, B, R> f){
      return IntStream.range(0, as.size()).parallel().mapToObj(i->f.apply(as.get(i),bs.get(i)));
    }
    @Override public <R> Stream<R> flatMap(BiFunction<A, B, Stream<R>> f){
      return IntStream.range(0, as.size()).boxed().flatMap(i->f.apply(as.get(i),bs.get(i)));
    }
    @Override public <R> Stream<R> filterMap(BiFunction<A, B, Optional<R>> f){
      return IntStream.range(0, as.size())
        .mapToObj(i->f.apply(as.get(i),bs.get(i)))
        .filter(Optional::isPresent)
        .map(Optional::get);
    }
    @Override public Zipper2<A,B> filter(BiPredicate<A, B> f){
      var asi = new ArrayList<A>();
      var bsi = new ArrayList<B>();
      IntStream.range(0, as.size())
        .filter(i->f.test(as.get(i),bs.get(i)))
        .forEachOrdered(i->{
          asi.add(as.get(i));
          bsi.add(bs.get(i));
        });
      return new ListZipper<>(asi, bsi);
    }
    @Override public <R> R fold(Acc2<R, A, B> folder, R initial) {
      Box<R> acc = new Box<>(initial);
      IntStream.range(0, as.size())
        .forEach(i->acc.set(folder.apply(acc.get(), as.get(i), bs.get(i))));
      return acc.get();
    }
    @Override public boolean anyMatch(BiPredicate<A, B> test){
      return IntStream.range(0, as.size())
        .anyMatch(i->test.test(as.get(i),bs.get(i)));
    }
    @Override public boolean allMatch(BiPredicate<A, B> test){
      return IntStream.range(0, as.size())
        .allMatch(i->test.test(as.get(i),bs.get(i)));
    }
    @Override public boolean allMatchParallel(BiPredicate<A, B> test){
      return IntStream.range(0, as.size())
        .parallel()
        .allMatch(i->test.test(as.get(i),bs.get(i)));
    }
  }
  private record ArrayZipper<A,B>(A[] as, B[] bs) implements Zipper2<A,B> {
    @Override public void forEach(BiConsumer<A, B> f){
      IntStream.range(0, as.length).forEach(i->f.accept(as[i], bs[i]));
    }
    @Override public <R> Stream<R> map(BiFunction<A, B, R> f){
      return IntStream.range(0, as.length).mapToObj(i->f.apply(as[i],bs[i]));
    }
    @Override public <R> Stream<R> parallelMap(BiFunction<A, B, R> f){
      return IntStream.range(0, as.length).parallel().mapToObj(i->f.apply(as[i],bs[i]));
    }
    @Override public <R> Stream<R> flatMap(BiFunction<A, B, Stream<R>> f){
      return IntStream.range(0, as.length).boxed().flatMap(i->f.apply(as[i],bs[i]));
    }
    @Override public <R> Stream<R> filterMap(BiFunction<A, B, Optional<R>> f){
      return IntStream.range(0, as.length)
        .mapToObj(i->f.apply(as[i],bs[i]))
        .filter(Optional::isPresent)
        .map(Optional::get);
    }
    @Override public Zipper2<A,B> filter(BiPredicate<A, B> f){
      var asi = new ArrayList<A>();
      var bsi = new ArrayList<B>();
      IntStream.range(0, as.length)
        .filter(i->f.test(as[i],bs[i]))
        .forEachOrdered(i->{
          asi.add(as[i]);
          bsi.add(bs[i]);
        });
      return new ListZipper<>(asi, bsi);
    }
    @Override public <R> R fold(Acc2<R, A, B> folder, R initial) {
      Box<R> acc = new Box<>(initial);
      IntStream.range(0, as.length)
        .forEach(i->acc.set(folder.apply(acc.get(), as[i], bs[i])));
      return acc.get();
    }
    @Override public boolean anyMatch(BiPredicate<A, B> test){
      return IntStream.range(0, as.length)
        .anyMatch(i->test.test(as[i],bs[i]));
    }
    @Override public boolean allMatch(BiPredicate<A, B> test){
      return IntStream.range(0, as.length)
        .allMatch(i->test.test(as[i],bs[i]));
    }
    @Override public boolean allMatchParallel(BiPredicate<A, B> test){
      return IntStream.range(0, as.length)
        .parallel()
        .allMatch(i->test.test(as[i],bs[i]));
    }
  }
  public static <T> Optional<Integer> firstPos(List<T> xs, Predicate<Integer> p) {
    return IntStream.range(0, xs.size()).boxed()
      .filter(p)
      .findFirst();
  }
  public static <T> Optional<Integer> firstPos(int start, List<T> xs, Predicate<Integer> p) {
    assert start <= xs.size();
    return IntStream.range(start, xs.size()).boxed()
      .filter(p)
      .findFirst();
  }
  public interface Acc2<R,A,B> { R apply(R acc, A a, B b); }
  public interface Acc3<R,A,B,C>{ R apply(R acc, A a, B b, C c); }
  
  public static <A,B> Zipper3<Integer,A,B> zipI(List<A> as, List<B> bs){
    assert as.size() == bs.size();
    return new ListIndexZipper3<>(as, bs);
  }
  public static <A,B> Zipper3<Integer,A,B> zipI(A[] as, B[] bs){
    assert as.length == bs.length;
    return new ArrayIndexZipper3<>(as, bs);
  }
  private record ListIndexZipper3<A,B>(List<A> as, List<B> bs) implements Zipper3<Integer,A,B>{
    @Override public void forEach(TriConsumer<Integer,A,B> f){
      IntStream.range(0, as.size()).forEach(i->f.accept(i, as.get(i), bs.get(i)));
    }
    @Override public <R> Stream<R> map(TriFunction<Integer,A,B,R> f){
      return IntStream.range(0, as.size()).mapToObj(i->f.apply(i, as.get(i), bs.get(i)));
    }
    @Override public <R> Stream<R> parallelMap(TriFunction<Integer,A,B,R> f){
      return IntStream.range(0, as.size()).parallel().mapToObj(i->f.apply(i, as.get(i), bs.get(i)));
    }
    @Override public <R> Stream<R> flatMap(TriFunction<Integer,A,B,Stream<R>> f){
      return IntStream.range(0, as.size()).boxed().flatMap(i->f.apply(i, as.get(i), bs.get(i)));
    }
    @Override public <R> Stream<R> filterMap(TriFunction<Integer,A,B,Optional<R>> f){
      return IntStream.range(0, as.size())
        .mapToObj(i->f.apply(i, as.get(i), bs.get(i)))
        .filter(Optional::isPresent)
        .map(Optional::get);
    }
    @Override public Zipper3<Integer,A,B> filter(TriPredicate<Integer,A,B> f){
      var is= new ArrayList<Integer>();
      var asi= new ArrayList<A>();
      var bsi= new ArrayList<B>();
      IntStream.range(0, as.size())
        .filter(i->f.test(i, as.get(i), bs.get(i)))
        .forEachOrdered(i->{ is.add(i); asi.add(as.get(i)); bsi.add(bs.get(i)); });
      return new ListZipper3<>(is, asi, bsi);
    }
    @Override public <R> R fold(Acc3<R,Integer,A,B> folder, R initial){
      Box<R> acc= new Box<>(initial);
      IntStream.range(0, as.size()).forEach(i->acc.set(folder.apply(acc.get(), i, as.get(i), bs.get(i))));
      return acc.get();
    }
    @Override public boolean anyMatch(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.size()).anyMatch(i->test.test(i, as.get(i), bs.get(i)));
    }
    @Override public boolean allMatch(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.size()).allMatch(i->test.test(i, as.get(i), bs.get(i)));
    }
    @Override public boolean allMatchParallel(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.size()).parallel().allMatch(i->test.test(i, as.get(i), bs.get(i)));
    }
  }
  private record ArrayIndexZipper3<A,B>(A[] as, B[] bs) implements Zipper3<Integer,A,B>{
    @Override public void forEach(TriConsumer<Integer,A,B> f){
      IntStream.range(0, as.length).forEach(i->f.accept(i, as[i], bs[i]));
    }
    @Override public <R> Stream<R> map(TriFunction<Integer,A,B,R> f){
      return IntStream.range(0, as.length).mapToObj(i->f.apply(i, as[i], bs[i]));
    }
    @Override public <R> Stream<R> parallelMap(TriFunction<Integer,A,B,R> f){
      return IntStream.range(0, as.length).parallel().mapToObj(i->f.apply(i, as[i], bs[i]));
    }
    @Override public <R> Stream<R> flatMap(TriFunction<Integer,A,B,Stream<R>> f){
      return IntStream.range(0, as.length).boxed().flatMap(i->f.apply(i, as[i], bs[i]));
    }
    @Override public <R> Stream<R> filterMap(TriFunction<Integer,A,B,Optional<R>> f){
      return IntStream.range(0, as.length)
        .mapToObj(i->f.apply(i, as[i], bs[i]))
        .filter(Optional::isPresent)
        .map(Optional::get);
    }
    @Override public Zipper3<Integer,A,B> filter(TriPredicate<Integer,A,B> f){
      var is= new ArrayList<Integer>();
      var asi= new ArrayList<A>();
      var bsi= new ArrayList<B>();
      IntStream.range(0, as.length)
        .filter(i->f.test(i, as[i], bs[i]))
        .forEachOrdered(i->{ is.add(i); asi.add(as[i]); bsi.add(bs[i]); });
      return new ListZipper3<>(is, asi, bsi);
    }
    @Override public <R> R fold(Acc3<R,Integer,A,B> folder, R initial){
      Box<R> acc= new Box<>(initial);
      IntStream.range(0, as.length).forEach(i->acc.set(folder.apply(acc.get(), i, as[i], bs[i])));
      return acc.get();
    }
    @Override public boolean anyMatch(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.length).anyMatch(i->test.test(i, as[i], bs[i]));
    }
    @Override public boolean allMatch(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.length).allMatch(i->test.test(i, as[i], bs[i]));
    }
    @Override public boolean allMatchParallel(TriPredicate<Integer,A,B> test){
      return IntStream.range(0, as.length).parallel().allMatch(i->test.test(i, as[i], bs[i]));
    }
  }
  private record ListZipper3<A,B,C>(List<A> as, List<B> bs, List<C> cs) implements Zipper3<A,B,C>{
    @Override public void forEach(TriConsumer<A,B,C> f){
      IntStream.range(0, as.size()).forEach(i->f.accept(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public <R> Stream<R> map(TriFunction<A,B,C,R> f){
      return IntStream.range(0, as.size()).mapToObj(i->f.apply(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public <R> Stream<R> parallelMap(TriFunction<A,B,C,R> f){
      return IntStream.range(0, as.size()).parallel().mapToObj(i->f.apply(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public <R> Stream<R> flatMap(TriFunction<A,B,C,Stream<R>> f){
      return IntStream.range(0, as.size()).boxed().flatMap(i->f.apply(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public <R> Stream<R> filterMap(TriFunction<A,B,C,Optional<R>> f){
      return IntStream.range(0, as.size())
        .mapToObj(i->f.apply(as.get(i), bs.get(i), cs.get(i)))
        .filter(Optional::isPresent)
        .map(Optional::get);
    }
    @Override public Zipper3<A,B,C> filter(TriPredicate<A,B,C> f){
      var asi= new ArrayList<A>();
      var bsi= new ArrayList<B>();
      var csi= new ArrayList<C>();
      IntStream.range(0, as.size())
        .filter(i->f.test(as.get(i), bs.get(i), cs.get(i)))
        .forEachOrdered(i->{ asi.add(as.get(i)); bsi.add(bs.get(i)); csi.add(cs.get(i)); });
      return new ListZipper3<>(asi, bsi, csi);
    }
    @Override public <R> R fold(Acc3<R,A,B,C> folder, R initial){
      Box<R> acc= new Box<>(initial);
      IntStream.range(0, as.size()).forEach(i->acc.set(folder.apply(acc.get(), as.get(i), bs.get(i), cs.get(i))));
      return acc.get();
    }
    @Override public boolean anyMatch(TriPredicate<A,B,C> test){
      return IntStream.range(0, as.size()).anyMatch(i->test.test(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public boolean allMatch(TriPredicate<A,B,C> test){
      return IntStream.range(0, as.size()).allMatch(i->test.test(as.get(i), bs.get(i), cs.get(i)));
    }
    @Override public boolean allMatchParallel(TriPredicate<A,B,C> test){
      return IntStream.range(0, as.size()).parallel().allMatch(i->test.test(as.get(i), bs.get(i), cs.get(i)));
    }
  }
}