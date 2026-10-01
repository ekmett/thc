// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeMetadataLookupTest {
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build();
    }

    private static Field field(Object value, String name) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { var result = type.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException absent) { /* Continue through generated node tiers. */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static BytecodeRoot root(Language language, Metrics metrics) {
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            b.emitEnterRoot(metrics);
            var retained = b.createLocal("retained", FrameSlotKind.Long);
            b.beginStaticStoreLong(retained); b.emitLoadConstant(42L); b.endStaticStoreLong();
            b.beginTryCatch(); b.beginBlock();
            for (int i = 0; i < 12; i++) {
                b.beginBlock();
                var local = b.createLocal("scoped" + i, FrameSlotKind.Object);
                b.beginStaticStoreObject(local); b.emitLoadConstant(Unit.INSTANCE); b.endStaticStoreObject();
                b.endBlock();
            }
            b.beginTryCatch();
            b.beginReturn();
            b.beginForceValue(metrics, false); b.emitLoadArgument(1); b.endForceValue();
            b.endReturn();
            b.beginReturn(); b.emitStaticLoadLong(retained); b.endReturn();
            b.endTryCatch(); b.endBlock();
            b.beginReturn(); b.emitLoadConstant(9L); b.endReturn();
            b.endTryCatch(); b.endRoot();
        }).getNode(0);
    }

    @Test void compilationPreparesBoundedSnapshotWithoutChangingLifetimeCounts() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var root = root(language, new Metrics(true));
                BytecodeNode original = root.getBytecodeNode();
                assertNull(field(original, "thcMetadata_").get(original), "unused roots allocate no index");
                var pcs = new ArrayList<Integer>(); var counts = new ArrayList<Integer>();
                for (var instruction : original.getInstructions()) {
                    pcs.add(instruction.getBytecodeIndex());
                    counts.add(original.getLocalCount(instruction.getBytecodeIndex()));
                }
                assertTrue(root.prepareForCompilation(true, 2, true));
                var prepared = root.getBytecodeNode();
                var metadata = (int[][]) field(prepared, "thcMetadata_").get(prepared);
                assertNotNull(metadata);
                int[] locals = (int[]) field(prepared, "locals").get(prepared);
                int[] handlers = (int[]) field(prepared, "handlers").get(prepared);
                assertTrue(metadata[0].length <= locals.length / 6 * 4);
                assertTrue(metadata[1].length <= Math.max(4, handlers.length / 6 * 8));
                for (int i = 0; i < pcs.size(); i++) assertEquals(counts.get(i), prepared.getLocalCount(pcs.get(i)));
                assertSame(metadata, field(prepared, "thcMetadata_").get(prepared));
                var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized");
                cloneMethod.setAccessible(true);
                var clone = (BytecodeRoot) cloneMethod.invoke(root);
                assertNull(field(clone.getBytecodeNode(), "thcMetadata_").get(clone.getBytecodeNode()));
                assertTrue(clone.prepareForCompilation(true, 2, true));
                for (int i = 0; i < pcs.size(); i++) assertEquals(counts.get(i), clone.getBytecodeNode().getLocalCount(pcs.get(i)));
                assertSame(metadata, field(prepared, "thcMetadata_").get(prepared));
            } finally { context.leave(); }
        }
    }

    @Test void orderedOverlappingHandlersAndFallbackRetainProfiling() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean capture : new boolean[]{false, true}) for (boolean delimited : new boolean[]{false, true}) {
                    var root = root(language, new Metrics(true)); root.configureAsync(capture); root.configureDelimited(delimited);
                    assertTrue(root.prepareForCompilation(true, 2, true));
                    var bytecode = root.getBytecodeNode();
                    int[] handlers = (int[]) field(bytecode, "handlers").get(bytecode);
                    boolean[] profiles = (boolean[]) field(bytecode, "exceptionProfiles_").get(bytecode);
                    assertTrue(handlers.length >= 12, "nested real handlers");
                    var resolve = bytecode.getClass().getDeclaredMethod("resolveHandler", long.class, int.class, int[].class, Throwable.class);
                    resolve.setAccessible(true);
                    int length = ((byte[]) field(bytecode, "bytecodes").get(bytecode)).length;
                    for (long pc = -1; pc <= length; pc++) {
                        for (int first = 0; first <= handlers.length; first += 6) {
                            int expected = -1;
                            for (int i = first; i < handlers.length; i += 6)
                                if (handlers[i] <= pc && pc < handlers[i + 1]) { expected = i; break; }
                            for (var table : new int[][]{handlers, handlers.clone()}) {
                                java.util.Arrays.fill(profiles, false);
                                assertEquals(expected, resolve.invoke(bytecode, pc, first, table, null), "ordered sparse/fallback lookup");
                                for (int i = 0; i < profiles.length; i++)
                                    assertEquals(expected == i * 6, profiles[i], "only the selected handler is observed");
                            }
                        }
                    }
                }
            } finally { context.leave(); }
        }
    }

    @Test void concurrentPreparationPublishesOneCompleteSnapshot() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var node = root(language, new Metrics(true)).getBytecodeNode();
                var metadata = field(node, "thcMetadata_");
                assertNull(metadata.get(node));
                var prepare = metadata.getDeclaringClass().getDeclaredMethod("prepareMetadataLookup");
                prepare.setAccessible(true);
                var together = new CyclicBarrier(2);
                java.util.function.Supplier<Object> task = () -> {
                    try { together.await(); prepare.invoke(node); return metadata.get(node); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                };
                var a = CompletableFuture.supplyAsync(task);
                var b = CompletableFuture.supplyAsync(task);
                Object published = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(published);
                assertSame(published, b.get(10, java.util.concurrent.TimeUnit.SECONDS));
                int[][] indices = (int[][]) published;
                assertEquals(2, indices.length); assertNotNull(indices[0]); assertNotNull(indices[1]);
                assertTrue(indices[0].length > 0); assertTrue(indices[1].length > 0);
            } finally { context.leave(); }
        }
    }

    @Test void osrPreparationIndexesItsOwnCachedBytecodeWithoutExecutingAgain() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var root = root(language, metrics);
                root.getBytecodeNode().setUncachedThreshold(0);
                assertEquals(7L, Calls.target(root.getCallTarget(), new Object[]{0L, 7L}));
                var bytecode = root.getBytecodeNode();
                assertNull(field(bytecode, "thcMetadata_").get(bytecode));
                assertInstanceOf(com.oracle.truffle.api.nodes.BytecodeOSRNode.class, bytecode).prepareOSR(0L);
                assertNotNull(field(bytecode, "thcMetadata_").get(bytecode));
                assertEquals(0, metrics.getCompiledEntries()); assertEquals(0, metrics.getThunkEvaluations());
            } finally { context.leave(); }
        }
    }

    @Test void firstCompiledExceptionPreservesRetainedLocalAndHandlerChoice() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var root = root(language, metrics);
                root.configureAsync(true);
                var target = (OptimizedCallTarget) root.getCallTarget();
                assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
                assertEquals(0, metrics.getThunkEvaluations());
                var thunk = new Thunk(new com.oracle.truffle.api.nodes.RootNode(language) {
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
                        throw new GuestException(Unit.INSTANCE, this);
                    }
                }.getCallTarget(), null);
                ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                assertEquals(42L, Calls.target(target, new Object[]{0L, thunk}));
                assertEquals(1, metrics.getCompiledEntries());
                assertEquals(1, metrics.getThunkEvaluations());
                assertEquals(3, thunk.getState());
            } finally { context.leave(); }
        }
    }

    @Test void alreadyObservedHandlerRetainsCompiledPathAndExactChoice() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true);
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.emitEnterRoot(metrics);
                    var retained = b.createLocal("retained", FrameSlotKind.Long);
                    b.beginStaticStoreLong(retained); b.emitLoadConstant(42L); b.endStaticStoreLong();
                    b.beginTryCatch();
                    b.beginTryCatch();
                    b.beginReturn(); b.beginRaise(false); b.emitLoadConstant(Unit.INSTANCE); b.endRaise(); b.endReturn();
                    b.beginReturn(); b.emitStaticLoadLong(retained); b.endReturn();
                    b.endTryCatch();
                    b.beginReturn(); b.emitLoadConstant(9L); b.endReturn();
                    b.endTryCatch(); b.endRoot();
                }).getNode(0);
                root.configureAsync(true);
                var target = (OptimizedCallTarget) root.getCallTarget();
                // Intentionally observe the real handler and guest raise before
                // compilation. This is a hot-handler control, not first-cut retention.
                for (int i = 0; i < 5; i++) assertEquals(42L, Calls.target(target, new Object[]{0L}));
                var bytecode = root.getBytecodeNode();
                var profiles = ((boolean[]) field(bytecode, "exceptionProfiles_").get(bytecode)).clone();
                int observed = 0; for (boolean profile : profiles) if (profile) observed++;
                assertEquals(1, observed, "only the inner handler was used");
                assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
                long before = metrics.getCompiledEntries();
                ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                assertEquals(42L, Calls.target(target, new Object[]{0L}));
                assertEquals(before + 1, metrics.getCompiledEntries());
                assertTrue(target.isValidLastTier()); assertSame(target, root.getCallTarget());
                assertArrayEquals(profiles, (boolean[]) field(bytecode, "exceptionProfiles_").get(bytecode));
            } finally { context.leave(); }
        }
    }
}
