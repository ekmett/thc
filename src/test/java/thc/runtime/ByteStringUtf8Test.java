// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import thc.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.runtime.ScalarValueTestSupport.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

class ByteStringUtf8Test {
    @BeforeEach void supportedHost() { assumeTrue("Linux".equals(System.getProperty("os.name")) && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))); }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/bytestring-utf8";
    private Map<String, Object> cbd(String path) throws Exception { return thc.CoreCbdFixtures.read(root.resolve(path)); }
    private static String entryId(String name) { return "main:ByteStringUtf8Audit." + name; }
    private Object json(String path) throws Exception { return Json.parse(Files.readString(root.resolve(path))); }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : list("ByteStringUtf8Audit", "THC.InterfaceClosure")) modules.add(object(cbd(prefix + "/" + stage + "/core/" + name + ".cbd")));
        return CoreModules.merge(modules);
    }
    private List<Map<String, Object>> rows() throws Exception {
        var manifest = object(json(prefix + "/manifest.json")); assertEquals(true, manifest.get("strictAccepted")); assertEquals(800L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("inputHashes"), Set.of("t/fixtures/compiler/ByteStringUtf8Audit.hs",
            "t/fixtures/compiler/ByteStringUtf8Native.hs", "t/haskell-fixtures/ByteStringUtf8Fixtures.hs", "bin/core_original_foreign.py"), null);
        var artifacts = new HashSet<>(list(prefix + "/oracle.json"));
        for (var stage : list("pre", "post")) for (var name : list("ByteStringUtf8Audit", "THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + name + ".cbd");
        OriginalStdioChecks.hashes(root.toFile(), manifest.get("artifactHashes"), artifacts, prefix + "/");
        var rows = objects(json(prefix + "/oracle.json")); var counts = new HashMap<Object, Integer>(); for (var row : rows) counts.merge(row.get("entry"), 1, Integer::sum);
        assertEquals(map("validateUnsafe", 400, "validateSafe", 400), counts);
        for (var stage : list("pre", "post")) for (var entry : list("validateUnsafe", "validateSafe")) {
            var audit = object(json(prefix + "/" + stage + "/" + entry + ".audit.json"));
            assertEquals(true, audit.get("accepted")); assertEquals(list(), audit.get("issues")); assertEquals(list(), audit.get("missingGlobals"));
        }
        return rows;
    }
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private void inside(CheckedConsumer<Language> block) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try { block.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> source) throws Exception {
        source = ForeignExceptionFixtureSupport.nativeModules(List.of(source));
        return backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private byte[] byteValues(Object values) {
        var list = expression(values); var bytes = new byte[list.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = ((Long) list.get(i)).byteValue(); return bytes;
    }
    private ManagedAddress address(Object values, int kind) {
        var bytes = byteValues(values);
        return switch (kind) {
            case 0 -> ManagedAddress.fromByteArray(bytes);
            case 3 -> ManagedAddress.fromHex(HexFormat.of().formatHex(bytes));
            default -> {
                var result = ManagedAddress.fromAllocation(kind == 4 ? ManagedAllocation.nativeMutable(bytes.length, 8)
                    : ManagedAllocation.mutable(bytes.length, 8, kind == 2));
                for (int i = 0; i < bytes.length; i++) result.writeWord8(i, bytes[i]); yield result;
            }
        };
    }
    private long model(Object bytes, int offset, int length) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(byteValues(bytes), offset, length)); return 1L;
        } catch (CharacterCodingException ignored) { return 0L; }
    }
    @Test @Tag("foreign-exceptions-full-core") void originalSafeAndUnsafeCallsMatchNativeAndJvmOnFirstCompiledCalls() throws Exception {
        var rows = rows(); for (var row : rows) assertEquals(row.get("result"), model(row.get("bytes"), ((Long) row.get("offset")).intValue(), ((Long) row.get("count")).intValue()));
        for (var stage : list("pre", "post")) {
            var source = module(stage); var safeties = new HashSet<String>();
            for (var call : OriginalStdioChecks.foreignCalls(source)) {
                var descriptor = object(object(call.get(6)).get("foreignCall"));
                assertEquals("bytestring_is_valid_utf8", object(descriptor.get("target")).get("symbol"));
                safeties.add((String) descriptor.get("safety"));
            }
            assertEquals(Set.of("unsafe", "safe"), safeties);
            for (var backend : list("ast", "bytecode")) inside(language -> {
                var groups = new LinkedHashMap<String, List<Map<String, Object>>>(); for (var row : rows) groups.computeIfAbsent((String) row.get("entry"), ignored -> new ArrayList<>()).add(row);
                for (var group : groups.entrySet()) {
                    var entry = group.getKey(); var examples = group.getValue(); var program = load(language, backend, with(CoreModules.reachable(source, entryId(entry)), "instrument", true)); var target = program.entryTarget(entryId(entry));
                    CheckedConsumer<Boolean> exercise = compiled -> {
                        // The original Ptr ABI and withArray oracle use native addresses.
                        for (int kind : new int[]{2, 3, 4}) for (int index = 0; index < examples.size(); index++) {
                            var row = examples.get(index); var base = address(row.get("bytes"), kind); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            var result = callScalarTestTarget(target, new Object[]{0L, base.plus((Long) row.get("offset")), row.get("count")});
                            var label = stage + "/" + backend + "/" + entry + "/storage=" + kind + "/" + index + "/compiled=" + compiled;
                            assertEquals(row.get("result"), result, label); var bytes = expression(row.get("bytes"));
                            for (int i = 0; i < bytes.size(); i++) assertEquals(bytes.get(i), base.readWord8(i), label + " input is unchanged");
                            if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), label); valid(target); }
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                        }
                    };
                    exercise.accept(false); compile(target); exercise.accept(true);
                }
            });
        }
    }
    @Test void boundsNullPointerCellsShrinksAndNativeOwnersAreChecked() throws Exception {
        inside(language -> {
            var nullAddress = ManagedAddress.nullAddress(); assertEquals(1L, ManagedByteStringUtf8.validate(nullAddress, 0));
            assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(nullAddress, 1)); var base = ManagedAddress.fromByteArray(new byte[]{65, 66, 67});
            for (long count : list(-1L, 4L, Long.MAX_VALUE)) assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(base, count));
            assertEquals(1L, ManagedByteStringUtf8.validate(base.plus(3), 0)); assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(base.plus(3), 1));
            var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8)); cells.writeAddressElementIndex(1, base);
            assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(cells, 16));
            var allocation = ManagedAllocation.mutable(16, 8); var shrunk = ManagedAddress.fromAllocation(allocation).plus(9); allocation.shrink(8);
            assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(shrunk, 0)); var nativeAddress = Language.currentState().getNativeAllocations().malloc(8);
            for (int i = 0; i < 8; i++) nativeAddress.writeWord8(i, 65);
            assertEquals(1L, ManagedByteStringUtf8.validate(nativeAddress.plus(1), 7)); nativeAddress.writeWord8(7, 255);
            assertEquals(0L, ManagedByteStringUtf8.validate(nativeAddress.plus(1), 7));
            inside(ignored -> assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(nativeAddress, 0)));
            Language.currentState().getNativeAllocations().free(nativeAddress); assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(nativeAddress, 0));
        });
    }
    @Test @Tag("foreign-exceptions-full-core") void originalDescriptorRejectsMismatchedAbiAndStateCarriers() throws Exception {
        var source = module("pre");
        for (var backend : list("ast", "bytecode")) inside(language -> {
            for (var original : OriginalStdioChecks.foreignCalls(source)) {
                var raw = OriginalStdioChecks.rawModule(original, source, null);
                for (int variant : new int[]{2, 4, 5, 6, 7}) {
                    var bad = object(Json.parse(Json.stringify(raw))); var calls = OriginalStdioChecks.foreignCalls(bad); assertEquals(1, calls.size());
                    var call = calls.getFirst(); var descriptor = object(object(call.get(6)).get("foreignCall"));
                    switch (variant) {
                        case 2 -> descriptor.put("arity", 4L); case 4 -> expression(call.get(3)).set(0, true); case 5 -> expression(call.get(1)).set(1, "entry");
                        case 6 -> expression(descriptor.get("argumentReps")).set(1, OriginalStdioFixtures.scalar("IntRep", false));
                        case 7 -> expression(descriptor.get("argumentReps")).set(0, OriginalStdioFixtures.scalar("BoxedRep (Just Unlifted)", false));
                    }
                    assertThrows(RuntimeFault.class, () -> load(language, backend, bad), backend + "/" + variant);
                }
                var target = load(language, backend, raw).entryTarget("entry"); var bytes = address(list(65L), 4);
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, bytes, 1L, 17L}));
                assertEquals(1, callScalarTestTarget(target, new Object[]{0L, bytes, 1L, thc.runtime.Unit.INSTANCE}));
            }
        });
    }
    private Map<String, Object> shadowed(List<Object> original, Map<String, Object> source, boolean claimForeign) {
        var raw = object(Json.parse(Json.stringify(OriginalStdioChecks.rawModule(original, source, null))));
        var bindings = objects(raw.get("bindings")); assertEquals(1, bindings.size()); var lambda = expression(bindings.getFirst().get("expr")); var body = expression(lambda.get(2));
        var call = expression(body.get(1)); var metadata = object(call.get(6)); var tuple = object(metadata.get("rep")); var fields = objects(tuple.get("components"));
        var operands = expression(call.get(2)); var closure = OriginalStdioFixtures.closure(); var formals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < operands.size(); i++) formals.add(map("id", "join" + i, "lifted", false, "rep", Objects.requireNonNull(CoreRepresentations.metadata(expression(operands.get(i)))).get("rep")));
        var pair = list("app", list("con", "T2", 2, map("rep", closure)), list(list("var", "join2", map("rep", fields.get(0))),
            list("lit", "int32", "37", map("rep", fields.get(1)))), list(false, false), false, false, map("rep", with(tuple, "evaluated", true)));
        var join = map("id", expression(call.get(1)).get(1), "name", "shadowedUtf8", "lifted", true, "rep", closure,
            "expr", list("lam", formals, pair, map("rep", closure, "resultRep", tuple)), "joinValueArity", 3L, "joinResultRep", tuple, "info", map("joinArity", 3L));
        if (!claimForeign) call.set(6, without(metadata, "foreignCall"));
        // The same saturated, tuple-returning lexical join is valid
        // without a copied original foreign-call descriptor.
        CoreJoins.validate(list(join), call, false); body.set(1, list("let", false, list(join), call, map("rep", tuple))); return raw;
    }
    @Test @Tag("foreign-exceptions-full-core") void shadowedJoinCannotClaimOriginalUtf8ForeignAuthority() throws Exception {
        for (var stage : list("pre", "post")) {
            var source = module(stage);
            for (var backend : list("ast", "bytecode")) inside(language -> {
                for (var original : OriginalStdioChecks.foreignCalls(source)) {
                    var ordinary = load(language, backend, shadowed(original, source, false)).entryTarget("entry");
                    assertEquals(37, callScalarTestTarget(ordinary, new Object[]{0L, ManagedAddress.fromByteArray(new byte[]{-1}), 1L, thc.runtime.Unit.INSTANCE}), stage + "/" + backend + " ordinary lexical join keeps its result");
                    var failure = assertThrows(RuntimeFault.class, () -> load(language, backend, shadowed(original, source, true)));
                    assertTrue(Objects.toString(failure.getMessage(), "").contains("unresolved declared foreign head"), stage + "/" + backend + " rejects the shadowed head specifically: " + failure.getMessage());
                }
            });
        }
    }
    @Test void nativeAuthorityIsRequiredEvenForEmptyInput() {
        try (var context = Context.newBuilder("thc").allowNativeAccess(false).build()) {
            context.initialize("thc"); context.enter();
            try { assertThrows(RuntimeFault.class, () -> ManagedByteStringUtf8.validate(ManagedAddress.nullAddress(), 0)); } finally { context.leave(); }
        }
    }
    @Test void safeAstReturnSavesCompletedResultWithoutReplayingAndUnsafeDoesNotPoll() throws Exception {
        inside(language -> {
            var state = Language.currentState(); long identity = state.getThreads().enterCurrent(null, false, true, null);
            try {
                for (boolean safe : list(false, true)) {
                    var original = OriginalStdioChecks.foreignCalls(module("pre")).stream().filter(call ->
                        (safe ? "safe" : "unsafe").equals(object(object(call.get(6)).get("foreignCall")).get("safety"))).findFirst().orElseThrow();
                    var proof = CoreRepresentations.parse(object(original.get(6)).get("rep")); var layout = new FrameLayout(); int[] slots = {layout.bind("completed CInt")};
                    var shape = new TupleShape(proof, language); var bytes = ManagedAddress.fromByteArray(new byte[]{65});
                    class Effect { AsyncRequest pending; int evaluated; } var effect = new Effect();
                    var source = new Expr() { @Override public Object execute(VirtualFrame frame) { return bytes; } };
                    var length = new Expr() { @Override public Object execute(VirtualFrame frame) { return 1L; } };
                    var enqueue = new Expr() { @Override public Object execute(VirtualFrame frame) {
                        effect.evaluated++; effect.pending = state.getThreads().send(identity, "after completed validation"); return thc.runtime.Unit.INSTANCE;
                    } };
                    var body = new ByteStringUtf8Expression(safe, new Expr[]{source, length, enqueue}, proof);
                    var root = new FunctionRoot(language, layout.build(), "UTF-8 completion control", null, new int[0], new int[0], new int[0], body,
                        new Metrics(false), new CoreRepresentation[0], body.getRepresentation(), body.getCoreSourceLocation(), new boolean[0], null, shape, slots,
                        null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
                    var result = Calls.target(root.getCallTarget(), new Object[]{0L}); final Object completed;
                    if (safe) {
                        var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(result)); assertSame(effect.pending, saved.asyncRequest());
                        Objects.requireNonNull(effect.pending).acknowledge();
                        // A replay or a poll before native completion would now
                        // return zero. The saved completed result must remain one.
                        bytes.writeWord8(0, 255); completed = saved.continueWith(thc.runtime.Unit.INSTANCE);
                    } else {
                        assertEquals(AsyncRequestState.PENDING, Objects.requireNonNull(effect.pending).getState()); assertSame(effect.pending, state.getThreads().poll(root, false));
                        effect.pending.acknowledge(); completed = result;
                    }
                    assertEquals(1, shape.getLayout().getInt(TupleResults.ownedTupleResult(completed, shape), 0)); assertEquals(1, effect.evaluated);
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                }
            } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
}
