import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import thc.*;
import thc.runtime.*;

/** Load-only adaptation of RuntimeIntrospection: no Map execution or guest compilation. */
public final class ObjectSizes {
    private record Visit(Object value, String origin) {}

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static String fields(Class<?> type) {
        var fields = new ArrayList<String>();
        for (; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()))
                    fields.add(type.getSimpleName() + "." + field.getName() + ":" + field.getType().getTypeName());
            }
        }
        Collections.sort(fields);
        return String.join(",", fields);
    }

    private static void report(String kind, String identity, Object value, String detail) {
        System.out.println(String.join("\t", "SIZE", kind, identity, value.getClass().getName(),
                Long.toString(ObjectSizesAgent.shallowSize(value)), fields(value.getClass()), detail));
    }

    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("ast", "bytecode").contains(args[1]))
            throw new IllegalArgumentException("ObjectSizes MODULES_MANIFEST ast|bytecode");
        System.out.println("NOTE\tShallow bytes include headers/alignment; referenced storage is not included. No Map workload executed.");
        System.out.println("VM\t" + System.getProperty("java.vm.name") + "\t" + System.getProperty("java.runtime.version"));
        var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        for (String name : List.of("UseCompressedOops", "UseCompressedClassPointers", "ObjectAlignmentInBytes", "UseCompactObjectHeaders")) {
            try { System.out.println("VM_OPTION\t" + name + "\t" + bean.getVMOption(name).getValue()); }
            catch (IllegalArgumentException missing) { System.out.println("VM_OPTION\t" + name + "\tunavailable"); }
        }
        report("JVM", "boxed Long.MAX_VALUE", Long.valueOf(Long.MAX_VALUE), "");
        for (int length : new int[] {3, 4, 5}) report("PACKET", "Object[" + length + "]", new Object[length], "payload references only");

        Path manifest = Path.of(args[0]).toAbsolutePath();
        var modules = new ArrayList<Map<String, Object>>();
        for (String line : Files.readAllLines(manifest)) if (!line.isBlank()) {
            Path path = manifest.getParent().resolve(line.strip()).normalize();
            modules.add((Map<String, Object>) Json.INSTANCE.parse(Files.readString(path)));
        }
        var linked = new LinkedHashMap<String, Object>(CoreModules.INSTANCE.reachable(CoreModules.INSTANCE.merge(modules), "mapAggregate"));
        linked.put("instrument", false);
        linked.put("diagnosticUnsupported", true);
        linked.put("sourceNotesEnabled", true);
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.Compilation", "false").build()) {
            context.initialize("thc");
            context.enter();
            try {
                thc.Language language = TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
                ExecutableProgram program = args[1].equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                Map<String, DataLayout> layouts = (Map<String, DataLayout>) field(program, "dataLayouts");
                for (var entry : layouts.entrySet()) {
                    DataLayout layout = entry.getValue();
                    Method allocator = Arrays.stream(layout.getClass().getDeclaredMethods())
                            .filter(method -> method.getName().startsWith("allocate") && method.getParameterCount() == 0
                                    && DataValue.class.isAssignableFrom(method.getReturnType())).findFirst().orElseThrow();
                    allocator.setAccessible(true);
                    Object instance = allocator.invoke(layout);
                    report("DATA", entry.getKey(), instance, layout.getName() + ";arity=" + layout.getArity());
                }

                var pending = new ArrayDeque<Visit>();
                pending.add(new Visit(program, "program"));
                var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                int captures = 0;
                while (!pending.isEmpty()) {
                    Visit visit = pending.removeFirst();
                    Object object = visit.value();
                    if (!seen.add(object)) continue;
                    String origin = visit.origin();
                    if (object instanceof RootCallTarget target) {
                        pending.add(new Visit(target.getRootNode(), "root:" + target.getRootNode().getName()));
                        continue;
                    }
                    if (object instanceof Iterable<?> iterable) {
                        int index = 0;
                        for (Object value : iterable) {
                            if (value != null) pending.add(new Visit(value, origin + "[" + index + "]"));
                            index++;
                        }
                        continue;
                    }
                    if (object instanceof Object[] array) {
                        for (int i = 0; i < array.length; i++) if (array[i] != null)
                            pending.add(new Visit(array[i], origin + "[" + i + "]"));
                        continue;
                    }
                    if (object instanceof Map<?, ?> map) {
                        for (var entry : map.entrySet()) if (entry.getValue() != null)
                            pending.add(new Visit(entry.getValue(), origin + "[" + entry.getKey() + "]"));
                        continue;
                    }
                    if (object instanceof RootNode root) origin = "root:" + root.getName();
                    if (object instanceof Node node) for (Node child : node.getChildren())
                        pending.add(new Visit(child, origin + "/" + child.getClass().getSimpleName()));
                    if (!object.getClass().getName().startsWith("thc.")) continue;
                    if (object instanceof CaptureLayout layout) {
                        Object[] layoutFields = (Object[]) field(layout, "fields");
                        Object[] values = new Object[layoutFields.length];
                        for (int i = 0; i < layoutFields.length; i++)
                            if (Boolean.TRUE.equals(field(layoutFields[i], "exactLong"))) values[i] = 0L;
                        report("CAPTURE", "capture-" + (++captures), layout.captureValues(values), origin);
                        continue;
                    }
                    for (Class<?> type = object.getClass(); type != null && type.getName().startsWith("thc."); type = type.getSuperclass()) {
                        for (Field member : type.getDeclaredFields()) {
                            // The allocator owns the key; never read, retain, or print it here.
                            if (Modifier.isStatic(member.getModifiers()) || member.getName().equals("allocationKey")) continue;
                            member.setAccessible(true);
                            Object value = member.get(object);
                            if (value == null) continue;
                            if (value instanceof RootCallTarget || value instanceof Node || value instanceof CaptureLayout
                                    || value instanceof Iterable<?> || value instanceof Object[] || value instanceof Map<?, ?>
                                    || value instanceof Closure || value instanceof Thunk
                                    || value.getClass().getName().equals("thc.runtime.GlobalBinding"))
                                pending.add(new Visit(value, origin + "." + member.getName()));
                        }
                    }
                }
                if (layouts.isEmpty() || captures == 0) throw new AssertionError("Expected Map constructor and capture layouts");
                System.out.println("COUNT\tdataLayouts=" + layouts.size() + "\tcaptureLayouts=" + captures);
            } finally { context.leave(); }
        }
    }
}
