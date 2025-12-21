package utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class IoErr {
  public interface RunVoid{void run() throws IOException;}
  public interface Run<T>{T run() throws IOException;}
  public interface WalkVoid{void walk(Stream<Path> p) throws IOException;}
  public interface Walk<T>{T walk(Stream<Path> p) throws IOException;}
  public static void ofV(RunVoid f) {
    try { f.run(); }
    catch(IOException io){ throw new UncheckedIOException(io); }
  }
  public static <T> T of(Run<T> f) {
    try { return f.run(); }
    catch(IOException io){ throw new UncheckedIOException(io); }
  }
  public static void walkV(Path p, WalkVoid f){
    try (Stream<Path> s= Files.walk(p)){ f.walk(s); }
    catch(IOException io){ throw new UncheckedIOException(io); }
  }
  public static <T> T walk(Path p, Walk<T> f){
    try (Stream<Path> s= Files.walk(p)){ return f.walk(s); }
    catch(IOException io){ throw new UncheckedIOException(io); }
  }
}