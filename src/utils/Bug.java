package utils;

@SuppressWarnings("serial")
public class Bug extends RuntimeException{
  //assert Bug.breakOn(..,"...") || //expected usage
  //  Bug.breakHere(); //breakpoint on this line
  public static boolean breakOn(Object o, String s){
    var res= o.toString().contains(s);
    if (res){ System.out.println("Remove after debugging.\n"+o); }
    return !res;
    }
  public static boolean breakHere(){ return !Bug.class.getSimpleName().isEmpty(); }//True, but not optimized away
  public Bug() {super();}
  public Bug(Throwable cause) {super(cause);}
  public Bug(String msg) {super(msg);}
  public Bug(String msg,Throwable cause) {super(msg,cause);}
  public static Bug of(){ return new Bug(); }
  public static Bug of(Throwable cause){ return new Bug(cause); }
  public static Bug of(String msg){ return new Bug(msg); }
  public static Bug unreachable(){ return new Bug("Unreachable"); }
  public static Bug todo(){ return new Bug("Todo"); }
  public static Bug todo(String task){ return new Bug(task); }

  public static <T> T err(){ throw new Bug(); }
  public static <T> T err(String msg){ throw new Bug(msg); }
  
}
