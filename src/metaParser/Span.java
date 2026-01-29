package metaParser;

import java.net.URI;
import java.util.Objects;

public record Span(URI fileName, int startLine, int startCol, int endLine, int endCol) implements Comparable<Span>{
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
    return startLine <= other.startLine
      && endLine >= other.endLine
      && startCol <= other.startCol
      && endCol >= other.endCol;
    }
  @Override public int compareTo(Span o){
    int byName= this.fileName.toASCIIString().compareTo(o.fileName.toASCIIString());
    if (byName != 0){ return byName; }
    int byLine= Integer.compare(this.endLine,o.endLine);
    if (byLine != 0){ return byLine; }
    return Integer.compare(this.endCol,o.endCol);
  }
}