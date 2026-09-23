package utils;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

///Note: call this with
///   @BeforeAll static void setUp(){ Err.setUp(AssertionFailedError.class,Assertions::assertEquals,Assertions::assertTrue);}
/// We need to wire this later so we do not need Commons to depend from JUnit
public final class Err {
  private static Class<? extends AssertionError> err;
  private static BiConsumer<String,String> assertEquals;
  private static Consumer<Boolean> assertTrue;
  public static void setUp(
    Class<? extends AssertionError> err,
    BiConsumer<String,String> assertEquals,
    Consumer<Boolean> assertTrue){
    Err.err= err;
    Err.assertEquals= assertEquals;
    Err.assertTrue= assertTrue;
  }
  public static final String hole="[###]";//not contains \.[]{}()<>*+-=!?^$|
  public static boolean strCmp(String expected,String actual){
    if (expected == null || actual == null) {
      assertEquals.accept(expected,actual);
      throw Bug.of();
    }
    actual = actual.trim();
    expected = expected.trim();
    try {assertTrue.accept(strCmpAux(expected,actual));}
    catch (AssertionError e){
      if (!err.isInstance(e)){ throw e; }
      assertEquals.accept(expected,actual);
      throw Bug.of();
      }
    return true;
    }
  private static boolean strCmpAux(String expected, String actual){
    var regex= Stream.of(expected.split(Pattern.quote(hole),-1)).map(Pattern::quote).collect(Collectors.joining(".*"));
    return Pattern.compile(regex,Pattern.DOTALL).matcher(actual).matches();
  }
}
