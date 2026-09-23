import java.lang.instrument.Instrumentation;

/** Shallow size only; never follows references or transforms classes. */
public final class ObjectSizesAgent {
    private static Instrumentation instrumentation;
    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }
    public static long shallowSize(Object value) {
        if (instrumentation == null) throw new IllegalStateException("Start with -javaagent");
        return instrumentation.getObjectSize(value);
    }
}
