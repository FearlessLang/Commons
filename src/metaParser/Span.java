package metaParser;

import java.net.URI;
import java.util.Comparator;
import java.util.Objects;

public record Span(URI fileName, int startLine, int startCol, int endLine, int endCol) implements Comparable<Span>{
  private static final Comparator<Span> order= Comparator.comparing((Span s)->s.fileName().toASCIIString())
    .thenComparingInt(Span::endLine).thenComparingInt(Span::endCol)
    .thenComparingInt(Span::startLine).thenComparingInt(Span::startCol);
  public Span{
    Objects.requireNonNull(fileName);
    assert startLine < endLine || ( startLine == endLine  && startCol <= endCol )
    :"startLine="+startLine+", endLine="+endLine+", startCol="+startCol+", endCol="+endCol;
  }
  @Override public String toString(){
    return PrettyFileName.displayFileName(fileName)+"["+startLine+":"+startCol+".."+endLine+":"+endCol+"]";
  }
  public boolean isSingleLine(){ return startLine == endLine; }
  public boolean contained(Span other){
    if (!fileName.equals(other.fileName)){ return false; }
    boolean startsBefore= startLine < other.startLine || (startLine == other.startLine && startCol <= other.startCol);
    boolean endsAfter= endLine > other.endLine || (endLine == other.endLine && endCol >= other.endCol);
    return startsBefore && endsAfter;
  }
  @Override public int compareTo(Span o){ return order.compare(this,o); }
}
