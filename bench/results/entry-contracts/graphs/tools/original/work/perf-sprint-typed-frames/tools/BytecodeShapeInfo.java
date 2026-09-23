import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.staticobject.StaticShape;
import org.graalvm.polyglot.*;
import thc.*;
import thc.runtime.*;

/** Load-only shape/option inspection; never runs the Map workload or requests compilation. */
public final class BytecodeShapeInfo {
  static Object field(Object object,String name)throws Exception {
    for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try {Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}
    throw new NoSuchFieldException(name);
  }
  @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
    try(Engine engine=Engine.newBuilder().allowExperimentalOptions(true).build()) {
      for(String name:List.of("compiler.InliningRecursionDepth","compiler.InliningExpansionBudget","compiler.InliningInliningBudget")) {
        var option=engine.getOptions().get(name);if(option==null)throw new AssertionError("Unsupported option "+name);
        System.out.println("OPTION\t"+name+"\t"+option.getKey().getDefaultValue()+"\t"+option.getHelp());
      }
      System.out.println("ENGINE_ALIAS\t"+(engine.getOptions().get("engine.InliningRecursionDepth")!=null));
    }
    if(args.length==0)return;
    var modules=new ArrayList<Map<String,Object>>();for(String p:Files.readAllLines(Path.of(args[0])))if(!p.isBlank())modules.add((Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(p))));
    var linked=new LinkedHashMap<String,Object>(CoreModules.INSTANCE.reachable(CoreModules.INSTANCE.merge(modules),"mapAggregate"));
    linked.put("instrument",false);linked.put("diagnosticUnsupported",true);linked.put("sourceNotesEnabled",true);
    try(Context context=MainKt.executionContext()) {
      context.initialize("thc");context.enter();
      try {
        thc.Language language=TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
        BytecodeProgram program=new BytecodeProgram(language,linked);
        Map<String,DataLayout> layouts=(Map<String,DataLayout>)field(program,"dataLayouts");
        for(var e:layouts.entrySet()) {
          DataLayout layout=e.getValue();StaticShape<DataValueFactory> shape=(StaticShape<DataValueFactory>)field(layout,"shape");
          Object instance=shape.getFactory().create(layout);
          System.out.println("DATA\t"+instance.getClass().getName()+"\t"+e.getKey()+"\t"+layout.getName()+"\t"+Arrays.toString(instance.getClass().getDeclaredFields()));
        }
        var pending=new ArrayDeque<Object>();pending.add(program);var seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        while(!pending.isEmpty()) {
          Object obj=pending.removeFirst();if(!seen.add(obj))continue;
          if(obj instanceof RootCallTarget target){pending.add(target.getRootNode());continue;}
          if(obj instanceof Iterable<?> list){for(Object value:list)if(value!=null)pending.add(value);continue;}
          if(obj instanceof Object[] array){for(Object value:array)if(value!=null)pending.add(value);continue;}
          if(obj instanceof Map<?,?> map){for(Object value:map.values())if(value!=null)pending.add(value);continue;}
          if(obj instanceof Node node)for(Node child:node.getChildren())pending.add(child);
          if(!obj.getClass().getName().startsWith("thc."))continue;
          if(obj instanceof CaptureLayout layout) {
            StaticShape<CapturedFrameFactory> shape=(StaticShape<CapturedFrameFactory>)field(layout,"shape");
            Object instance=shape.getFactory().create(layout);
            System.out.println("CAPTURE\t"+instance.getClass().getName()+"\t"+Arrays.toString(instance.getClass().getDeclaredFields()));
            continue;
          }
          for(Class<?> cls=obj.getClass();cls!=null&&cls.getName().startsWith("thc.");cls=cls.getSuperclass())for(Field f:cls.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object value=f.get(obj);if(value==null)continue;
            if(value instanceof RootCallTarget||value instanceof Node||value instanceof CaptureLayout||value instanceof Iterable<?>||value instanceof Object[]||value instanceof Map<?,?>||value.getClass().getName().equals("thc.runtime.GlobalBinding")||value.getClass().getName().equals("thc.runtime.Closure")||value.getClass().getName().equals("thc.runtime.Thunk"))pending.add(value);
          }
        }
      } finally {context.leave();}
    }
  }
}
