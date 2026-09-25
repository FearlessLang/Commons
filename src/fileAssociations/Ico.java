package fileAssociations;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import offensiveUtils.Require;
import tools.Fs;
import utils.Range;

public final class Ico{
  public static final List<Integer> sizes= List.of(16,20,24,32,40,48,64,256);
  public static void fromPng(Path png, Path ico){
    var src= Objects.requireNonNull(Fs.of(()->ImageIO.read(png.toFile())));
    Require.check(src.getWidth() == src.getHeight(), "The icon \""+png+"\" is "+src.getWidth()+"x"+src.getHeight()
      +" pixels, but an icon must be square: its width and its height must be equal, for example 256x256.");
    var frames= sizes.stream().map(s->frame(scaled(src,s))).toList();
    var header= ByteBuffer.allocate(6+16*sizes.size()).order(ByteOrder.LITTLE_ENDIAN);
    header.putShort((short)0).putShort((short)1).putShort((short)sizes.size());
    var offset= header.capacity();
    for (int i : Range.of(sizes)){
      var s= sizes.get(i);
      header.put((byte)s.intValue()).put((byte)s.intValue()).put((byte)0).put((byte)0)
        .putShort((short)1).putShort((short)32).putInt(frames.get(i).length).putInt(offset);
      offset+= frames.get(i).length;
    }
    var out= new ByteArrayOutputStream();
    out.writeBytes(header.array());
    frames.forEach(out::writeBytes);
    Fs.ofV(()->Files.write(ico,out.toByteArray()));
  }
  static BufferedImage scaled(BufferedImage img, int size){
    var res= img;
    while (res.getWidth() > 2*size){ res= draw(res,res.getWidth()/2); }
    return draw(res,size);
  }
  private static BufferedImage draw(BufferedImage img, int size){
    var res= new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
    var g= res.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(img,0,0,size,size,null);
    g.dispose();
    return res;
  }
  private static byte[] frame(BufferedImage img){
    if (img.getWidth() == 256){ return png(img); }
    var s= img.getWidth();
    var maskRow= (s+31)/32*4;
    var b= ByteBuffer.allocate(40+s*s*4+maskRow*s).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(40).putInt(s).putInt(2*s).putShort((short)1).putShort((short)32)
      .putInt(0).putInt(s*s*4+maskRow*s).putInt(0).putInt(0).putInt(0).putInt(0);
    for (int y : Range.of(0,s)){
      for (int x : Range.of(0,s)){ b.putInt(img.getRGB(x,s-1-y)); }
    }
    for (int y : Range.of(0,s)){
      var row= new byte[maskRow];
      for (int x : Range.of(0,s)){
        if (img.getRGB(x,s-1-y)>>>24 == 0){ row[x/8]|= (byte)(0x80>>(x%8)); }
      }
      b.put(row);
    }
    return b.array();
  }
  static byte[] png(BufferedImage img){
    var out= new ByteArrayOutputStream();
    var written= Fs.of(()->ImageIO.write(img,"png",out));
    assert written;
    return out.toByteArray();
  }
}
