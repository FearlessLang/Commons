package utils;

import java.util.List;
import java.util.Optional;

public class GetO {
  public static <T> Optional<T> of(List<T> list, int i) {
    if (i >= list.size()) { return Optional.empty(); }
    return Optional.of(list.get(i));
  }
}
