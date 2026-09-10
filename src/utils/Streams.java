package utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Streams {
  public static <A,B> Zipper2<A,B> zip(List<A> as, List<B> bs){
    assert as.size()==bs.size();
    return new ListZipper<>(as,bs);
  }
  private record ListZipper<A,B>(List<A> as, List<B> bs) implements Zipper2<A, B> {
    @Override public <R> Stream<R> map(BiFunction<A, B, R> f){
      return IntStream.range(0, as.size()).mapToObj(i->f.apply(as.get(i),bs.get(i)));
    }
  }
  public static <A,B> Zipper3<Integer,A,B> zipI(List<A> as, List<B> bs){
    assert as.size() == bs.size();
    return new ListIndexZipper3<>(as, bs);
  }
  private record ListIndexZipper3<A,B>(List<A> as, List<B> bs) implements Zipper3<Integer,A,B>{
    @Override public void forEach(TriConsumer<Integer,A,B> f){
      IntStream.range(0, as.size()).forEach(i->f.accept(i, as.get(i), bs.get(i)));
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
  }
  private record ListZipper3<A,B,C>(List<A> as, List<B> bs, List<C> cs) implements Zipper3<A,B,C>{
    @Override public void forEach(TriConsumer<A,B,C> f){
      IntStream.range(0, as.size()).forEach(i->f.accept(as.get(i), bs.get(i), cs.get(i)));
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
  }
}
