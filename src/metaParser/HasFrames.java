package metaParser;

public interface HasFrames<S>{
  S addFrame(Frame f);
  default S addSpan(Span s){ return addFrame(new Frame("", s)); }
  default S addFrame(String msg, Span s){ return addFrame(new Frame(msg, s)); }
}
