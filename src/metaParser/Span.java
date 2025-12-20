package metaParser;

import java.net.URI;
import java.util.Objects;

public record Span(URI fileName, int startLine, int startCol, int endLine, int endCol){
  public Span{
    Objects.requireNonNull(fileName);
    assert startLine < endLine || ( startLine == endLine  && startCol <= endCol )
    :"startLine="+startLine+", endLine="+endLine+", startCol="+startCol+", endCol="+endCol; 
  }
  @Override public String toString(){
    return PrettyFileName.displayFileName(fileName)+"["+startLine+":"+startCol+".."+endLine+":"+endCol+"]";
  }
  public boolean isSingleLine(){ return startLine == endLine; }
}