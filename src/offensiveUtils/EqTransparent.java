package offensiveUtils;

/** Never as Map/Set key (nondiscriminating equals/hashCode). Build-time checker rejects it. */
@NeverAsKey
public class EqTransparent<T>{
  public final T inner;
  public EqTransparent(T inner){ this.inner= inner; }
  @Override final public boolean equals(Object o){ return o instanceof EqTransparent<?>; }
  @Override final public int hashCode(){ return 0; }
  @Override final public String toString(){ return inner.toString(); }
}
