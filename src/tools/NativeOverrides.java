package tools;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

public record NativeOverrides(Set<String> pairs, Set<String> fileNames){
  public boolean has(String iface, String mangledName){ return pairs.contains(iface+"#"+mangledName); }
  public boolean hasFile(String simpleName){ return fileNames.contains(simpleName); }
  public static NativeOverrides scan(Path rtPath){
    var files= Fs.walk(rtPath, s->s.filter(p->p.toString().endsWith(".java")).toList());
    var compiler= ToolProvider.getSystemJavaCompiler();
    var pairs= new HashSet<String>();
    Fs.ofV(()->{
      try (StandardJavaFileManager fm= compiler.getStandardFileManager(null,null,null)){
        var units= fm.getJavaFileObjectsFromPaths(files);
        var task= (JavacTask)compiler.getTask(null,fm,null,null,null,units);
        for (var cu: task.parse()){ scanUnit(cu, pairs); }
      }
    });
    var fileNames= new HashSet<String>();
    for (var p: files){
      var name= p.getFileName().toString();
      fileNames.add(name.substring(0, name.length()-".java".length()));
    }
    return new NativeOverrides(Set.copyOf(pairs), Set.copyOf(fileNames));
  }
  private static void scanUnit(CompilationUnitTree cu, Set<String> pairs){
    new TreeScanner<Void,Void>(){
      @Override public Void visitClass(ClassTree c, Void p){
        for (var i: c.getImplementsClause()){
          for (var mem: c.getMembers()){
            if (mem instanceof MethodTree mt){ pairs.add(i+"#"+mt.getName()); }
          }
        }
        return super.visitClass(c,p);
      }
    }.scan(cu, null);
  }
}
