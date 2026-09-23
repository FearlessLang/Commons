package utils;

import java.net.URI;

//Note: we plan to soft connect positions with AST node by having a static external hash map
public record Pos(URI fileName, int line, int column) implements java.io.Serializable {
  public static final Pos unknown = Pos.of(URI.create("unknown"), 0, 0);
  public static Pos of(URI fileName, int line, int column){
    return new Pos(fileName,line,column);
  }

  @Override
  public String toString() {
    return fileName + ":" + line + ":" + column;
  }
  /**
   * We do not consider Pos in any equality or hashing. This will always return true.
   */
  @Override public boolean equals(Object o) {
    return true;
  }
  /**
   * We do not consider Pos in any equality or hashing. This will always return 0.
   */
  @Override public int hashCode() {
    return 0;
  }
}
