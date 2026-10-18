package fileAssociations;

import java.nio.file.Path;

public record WindowIcon(String windowClass, String name, Path target, Path png){}
