// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.staticobject.*;
import org.graalvm.polyglot.Context;
import thc.Language;

/** Contract probe only. This is not the THC tuple implementation. */
public final class MixedTupleProbe {
    static final Object COMPLETE = new Object();
    static volatile boolean deoptRequested;
    static volatile long observations;
    static long materializedConsumes;
    @CompilerDirectives.TruffleBoundary static boolean observe() { observations++; return deoptRequested; }
    public static class Carrier { public Carrier() {} }
    public interface Factory { Carrier create(); }
    static final class LazyPayload { long force() { throw new AssertionError("lazy payload forced"); } }
    static final class Layout {
        final DefaultStaticProperty first = new DefaultStaticProperty("first");
        final DefaultStaticProperty second = new DefaultStaticProperty("second");
        final DefaultStaticProperty reference = new DefaultStaticProperty("reference");
        final StaticShape<Factory> shape;
        final Carrier slab;
        Layout(Language language) {
            shape = StaticShape.newBuilder(language).property(first, long.class, false)
                .property(second, long.class, false).property(reference, Object.class, false)
                .build(Carrier.class, Factory.class);
            slab = shape.getFactory().create();
        }
        static boolean inlined() { return CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot(); }
        Object finish(long a, long b, Object payload) {
            Carrier storage = inlined() ? shape.getFactory().create() : slab;
            first.setLong(storage, a + 17);
            second.setLong(storage, b ^ 0x55aaL);
            reference.setObject(storage, payload);
            if (inlined()) { CompilerDirectives.ensureVirtualized(storage); return storage; }
            return COMPLETE;
        }
        Object normalize(Object result) {
            if (inlined() || result == COMPLETE) return result;
            Carrier fresh = (Carrier) result;
            first.setLong(slab, first.getLong(fresh)); second.setLong(slab, second.getLong(fresh));
            reference.setObject(slab, reference.getObject(fresh));
            return COMPLETE;
        }
        boolean consume(Object result, long expected, Object expectedPayload) {
            if (CompilerDirectives.inInterpreter() && result != COMPLETE) materializedConsumes++;
            Carrier storage = result == COMPLETE ? slab : (Carrier) result;
            return ((first.getLong(storage) * 7) ^ (second.getLong(storage) * 11)) == expected
                && reference.getObject(storage) == expectedPayload;
        }
    }
    static final class Producer extends RootNode {
        final Layout layout;
        Producer(Language language, Layout layout) { super(language); this.layout = layout; }
        public String getName() { return "mixed-tuple-probe-producer"; }
        public Object execute(VirtualFrame frame) { Object[] a=frame.getArguments(); return layout.finish((long)a[0], (long)a[1], a[2]); }
    }
    static final class Forward extends RootNode {
        final Layout layout;
        @Child DirectCallNode call;
        Forward(Language language, Layout layout, RootCallTarget target, boolean inline) {
            super(language); this.layout=layout; call=DirectCallNode.create(target); if(inline) call.forceInlining();
        }
        public String getName() { return "mixed-tuple-probe-forward"; }
        public Object execute(VirtualFrame frame) { Object[] a=frame.getArguments(); return layout.normalize(call.call(a[0],a[1],a[2])); }
    }
    static final class Consumer extends RootNode {
        final Layout layout;
        @Child DirectCallNode call;
        Consumer(Language language, Layout layout, RootCallTarget target, boolean inline) {
            super(language); this.layout=layout; call=DirectCallNode.create(target); if(inline) call.forceInlining();
        }
        public String getName() { return "mixed-tuple-probe-consumer"; }
        public Object execute(VirtualFrame frame) {
            Object[] a=frame.getArguments(); Object result=call.call(a[0],a[1],a[2]);
            if((boolean)a[5]) CompilerDirectives.transferToInterpreterAndInvalidate();
            return layout.consume(result,(long)a[3],a[4]);
        }
    }
    static final class DeoptConsumer extends RootNode {
        final Layout layout;
        @Child DirectCallNode call;
        DeoptConsumer(Language language, Layout layout, RootCallTarget target, boolean inline) {
            super(language); this.layout=layout; call=DirectCallNode.create(target); if(inline) call.forceInlining();
        }
        public String getName() { return "mixed-tuple-probe-materialized-deopt"; }
        public Object execute(VirtualFrame frame) {
            Object[] a=frame.getArguments(); Object result=call.call(a[0],a[1],a[2]);
            if(observe()) CompilerDirectives.transferToInterpreterAndInvalidate();
            return layout.consume(result,(long)a[3],a[4]);
        }
    }
    static boolean valid(RootCallTarget t) throws Exception { return (boolean)t.getClass().getMethod("isValidLastTier").invoke(t); }
    static void compile(RootCallTarget t) throws Exception { t.getClass().getMethod("compile", boolean.class).invoke(t,true); if(!valid(t)) throw new AssertionError("not compiled"); }
    static long checksum(long a,long b) { return ((a+17)*7)^((b^0x55aaL)*11); }
    static void check(boolean expected, Object actual) { if(actual != Boolean.valueOf(expected)) throw new AssertionError(expected+" != "+actual); }
    public static void main(String[] args) throws Exception {
        boolean inline=Boolean.parseBoolean(args[0]);
        try(Context context=Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining",Boolean.toString(inline)).option("engine.BackgroundCompilation","false")
            .option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw")
            .option("engine.SingleTierCompilationThreshold","10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Layout layout=new Layout(language);
                RootCallTarget producer=new Producer(language,layout).getCallTarget();
                RootCallTarget forward=new Forward(language,layout,producer,inline).getCallTarget();
                RootCallTarget consumer=new Consumer(language,layout,forward,inline).getCallTarget();
                Object payload=new LazyPayload(), different=new LazyPayload();
                for(int i=0;i<50;i++) { long a=3000000001L+i,b=9000000043L-i*3; check(true,consumer.call(a,b,payload,checksum(a,b),payload,false)); check(false,consumer.call(a,b,payload,checksum(a,b),different,false)); }
                compile(producer); compile(forward); compile(consumer);
                for(int i=0;i<50;i++) { long a=4000000001L+i*11,b=8000000009L-i*5; check(true,consumer.call(a,b,payload,checksum(a,b),payload,false)); check(false,consumer.call(a,b,payload,checksum(a,b)+1,payload,false)); check(false,consumer.call(a,b,payload,checksum(a,b),different,false)); }
                if(!valid(consumer)) throw new AssertionError("normal call invalidated compiled consumer");
                check(true,consumer.call(17L,29L,payload,checksum(17L,29L),payload,true));
                RootCallTarget deopt=new DeoptConsumer(language,layout,forward,inline).getCallTarget();
                for(int i=0;i<40;i++) check(true,deopt.call(17L,29L,payload,checksum(17L,29L),payload));
                compile(deopt); check(true,deopt.call(17L,29L,payload,checksum(17L,29L),payload));
                if(!valid(deopt)) throw new AssertionError("barrier consumer invalidated before cold path");
                long before=materializedConsumes;
                deoptRequested=true; check(true,deopt.call(17L,29L,payload,checksum(17L,29L),payload));
                if(inline && materializedConsumes != before+1) throw new AssertionError("fresh carrier did not materialize at barrier: "+before+" -> "+materializedConsumes);
                if(!inline && materializedConsumes != before) throw new AssertionError("residual path fabricated fresh carrier");
                System.out.println("PASS barrier inline="+inline+" actualMaterializedConsumes="+(materializedConsumes-before)+" observations="+observations);
                System.out.println("PASS mixed inline="+inline+" independentLongs=true lazyReferenceIdentity=true warmCompiledValid=true deoptMaterialization=true validAfterDeopt="+valid(consumer));
            } finally { context.leave(); }
        }
    }
}
