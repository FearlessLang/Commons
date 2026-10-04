package fileAssociations;

import java.nio.file.Path;

public record Icon(String extension, Path ico, Path png){
  public boolean system(){ return !extension.matches("\\.(fearless|fapp[0-9]{3}|ffile[0-9]{3})"); }
}
