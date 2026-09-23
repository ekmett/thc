import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import org.graalvm.polyglot.Context;
import thc.Language;

/** Independent contract probe; no implementation files are modified. */
public final class TupleDirectiveProbe {
    static final Object COMPLETE = new Object();
    public static class Carrier { public Carrier() {} }
    public interface Factory { Carrier create(); }
    static final class Layout {
        final DefaultStaticProperty first = new DefaultStaticProperty("first");
        final DefaultStaticProperty second = new DefaultStaticProperty("second");
        final DefaultStaticProperty scope = new DefaultStaticProperty("scope");
        final StaticShape<Factory> shape;
        final Carrier slab;
        Layout(Language language) {
            shape = StaticShape.newBuilder(language).property(first, long.class, false)
                .property(second, long.class, false).property(scope, long.class, false)
                .build(Carrier.class, Factory.class);
            slab = shape.getFactory().create();
        }
        // Java helpers preserve the enclosing guest CallTarget inlining depth.
        static long scope() {
            return (CompilerDirectives.inCompiledCode() ? 2 : 0) |
                (CompilerDirectives.inCompilationRoot() ? 1 : 0);
        }
        Object finish(long value) {
            final boolean inline = CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot();
            Carrier storage = inline ? shape.getFactory().create() : slab;
            first.setLong(storage, value);
            second.setLong(storage, value * 3);
            scope.setLong(storage, scope());
            if (inline) {
                CompilerDirectives.ensureVirtualized(storage);
                return storage;
            }
            return COMPLETE;
        }
        Object normalize(Object result) {
            if (CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot()) return result;
            if (result == COMPLETE) return result;
            Carrier fresh = (Carrier) result;
            first.setLong(slab, first.getLong(fresh));
            second.setLong(slab, second.getLong(fresh));
            scope.setLong(slab, scope.getLong(fresh));
            return COMPLETE;
        }
        long consume(Object result) {
            Carrier storage = result == COMPLETE ? slab : (Carrier) result;
            return first.getLong(storage) + second.getLong(storage) + scope.getLong(storage) * 10000000000L;
        }
    }
    static final class Producer extends RootNode {
        final Layout layout;
        Producer(Language language, Layout layout) { super(language); this.layout = layout; }
        @Override public String getName() { return "tuple-probe-producer"; }
        @Override public Object execute(VirtualFrame frame) { return layout.finish((long) frame.getArguments()[0]); }
    }
    static final class Forward extends RootNode {
        final Layout layout;
        final boolean normalize;
        @Child DirectCallNode call;
        Forward(Language language, Layout layout, RootCallTarget target, boolean normalize, boolean inline) {
            super(language); this.layout = layout; this.normalize = normalize;
            call = DirectCallNode.create(target); if (inline) call.forceInlining();
        }
        @Override public String getName() { return normalize ? "tuple-probe-forward" : "tuple-probe-escape-negative"; }
        @Override public Object execute(VirtualFrame frame) {
            Object result = call.call(frame.getArguments()[0]);
            return normalize ? layout.normalize(result) : result;
        }
    }
    static final class Consumer extends RootNode {
        final Layout layout;
        @Child DirectCallNode call;
        Consumer(Language language, Layout layout, RootCallTarget target, boolean inline) {
            super(language); this.layout = layout;
            call = DirectCallNode.create(target); if (inline) call.forceInlining();
        }
        @Override public String getName() { return "tuple-probe-consumer"; }
        @Override public Object execute(VirtualFrame frame) {
            Object result = call.call(frame.getArguments()[0]);
            if ((boolean) frame.getArguments()[1]) CompilerDirectives.transferToInterpreterAndInvalidate();
            return layout.consume(result);
        }
    }
    static boolean valid(RootCallTarget target) throws Exception {
        return (boolean) target.getClass().getMethod("isValidLastTier").invoke(target);
    }
    static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        if (!valid(target)) throw new AssertionError("did not compile: " + target);
    }
    static void equal(long expected, Object value) {
        if (!(value instanceof Long) || (long) value != expected) throw new AssertionError(expected + " != " + value);
    }
    public static void main(String[] args) throws Exception {
        boolean inline = Boolean.parseBoolean(args[0]);
        Context.Builder builder = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inline)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000");

        try (Context context = builder.build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Layout layout = new Layout(language);
                RootCallTarget producer = new Producer(language, layout).getCallTarget();
                RootCallTarget forward = new Forward(language, layout, producer, true, inline).getCallTarget();
                RootCallTarget consumer = new Consumer(language, layout, forward, inline).getCallTarget();
                long input = 3000000017L;
                for (int i=0;i<40;i++) equal(input*4, consumer.call(input,false));
                compile(producer);
                Object direct = producer.call(input);
                if (direct != COMPLETE) throw new AssertionError("standalone producer must publish pooled completion");
                equal(input*4+30000000000L, layout.consume(direct));
                compile(forward);
                Object forwarded = forward.call(input);
                if (forwarded != COMPLETE) throw new AssertionError("standalone forwarding root must normalize");
                long mode = inline ? 2 : 3;
                equal(input*4+mode*10000000000L, layout.consume(forwarded));
                compile(consumer);
                equal(input*4+mode*10000000000L, consumer.call(input,false));
                if (!valid(consumer)) throw new AssertionError("normal compiled call invalidated");
                equal(input*4+mode*10000000000L, consumer.call(input,true));
                System.out.println("PASS inline="+inline+" interpreter=0 standalone-helper=3 callee-helper="+mode+
                    " normalizedForward=true coldDeoptContinuation=true consumerValidAfterDeopt="+valid(consumer));
                if (inline) {
                    RootCallTarget bad = new Forward(language, layout, producer, false, true).getCallTarget();
                    for(int i=0;i<40;i++) bad.call(input);
                    compile(bad);
                    Object escaped = bad.call(input);
                    if (valid(bad) || escaped != COMPLETE) throw new AssertionError("missing normalization control did not invalidate/replay");
                    System.out.println("PASS unnormalized-root-invalidated=true replay-completion=true");
                }
            } finally { context.leave(); }
        }
    }
}
