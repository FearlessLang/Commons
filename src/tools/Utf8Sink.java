package tools;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class Utf8Sink extends OutputStream{
  private final Consumer<String> out;
  private final CharsetDecoder decoder= StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE);
  private ByteBuffer inBuf= ByteBuffer.allocate(8192);
  private final CharBuffer outBuf= CharBuffer.allocate(8192);
  public Utf8Sink(Consumer<String> out){ this.out= out; }
  @Override public void write(int b){ write(new byte[]{(byte)b}, 0, 1); }
  @Override public synchronized void write(byte[] b, int off, int len){
    if (inBuf.remaining() < len){ expandBuffer(inBuf.position() + len); }
    inBuf.put(b, off, len);
    inBuf.flip();
    decodeLoop(false);
    inBuf.compact();
  }
  @Override public synchronized void close(){
    inBuf.flip();
    decodeLoop(true);
    while(true){
      outBuf.clear();
      var r= decoder.flush(outBuf);
      drainOutput();
      if (r.isUnderflow()){ break; }
    }
    inBuf.clear();
  }
  private void decodeLoop(boolean end){
    while(true){
      outBuf.clear();
      var r= decoder.decode(inBuf, outBuf, end);
      drainOutput();
      if (r.isUnderflow()){ break; }
    }
  }
  private void drainOutput(){
    outBuf.flip();
    if (outBuf.hasRemaining()){ out.accept(outBuf.toString()); }
  }
  private void expandBuffer(int neededSize){
    int newCap= Math.max(inBuf.capacity() * 2, neededSize);
    ByteBuffer newBuf= ByteBuffer.allocate(newCap);
    inBuf.flip();
    newBuf.put(inBuf);
    inBuf= newBuf;
  }
}
