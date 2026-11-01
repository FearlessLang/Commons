package metaParser;

public interface Layout{
  int line(int line);
  int column(int line, int column);
  Layout same= new Layout(){
    @Override public int line(int line){ return line; }
    @Override public int column(int line, int column){ return column; }
  };
}
