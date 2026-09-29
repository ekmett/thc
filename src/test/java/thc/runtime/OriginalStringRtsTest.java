// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import thc.Json;
import thc.ForeignExceptionFixtureSupport;
import thc.Language;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Descriptors copied unchanged from original GHC.Internal.System.Posix.Internals
 * core/235.json, sha256 9e77ab9a3dbb5c507f9ec6a8c16e37b6b9f29eaf677520f1c532e6657ac30ce9. */
@SuppressWarnings("unchecked")
class OriginalStringRtsTest {
    private final Map<String,Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private Object resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) { return Json.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)); }
    }
    private Map<String,Map<String,Object>> descriptors() throws Exception { return (Map<String,Map<String,Object>>) resource("/core/original-string-rts-descriptors.json"); }
    private Map<String,Object> module(String symbol) throws Exception { var declaration = descriptors().get(symbol); return module(symbol, declaration, declaration); }
    private Map<String,Object> module(String symbol, Map<String,Object> declaration) throws Exception { return module(symbol, declaration, descriptors().get(symbol)); }
    private Map<String,Object> module(String symbol, Map<String,Object> declaration, Map<String,Object> canonical) {
        var result = (Map<String,Object>) canonical.get("resultRep"); var fields = (List<Map<String,Object>>) result.get("components");
        var formals = new ArrayList<Map<String,Object>>(); var reps = (List<Map<String,Object>>) canonical.get("argumentReps");
        for (int i = 0; i < reps.size(); i++) formals.add(map("id", "arg" + i, "lifted", false, "rep", with(reps.get(i), "evaluated", true)));
        var args = new ArrayList<Object>(); for (var formal : formals) args.add(list("var", formal.get("id"), map("rep", formal.get("rep"))));
        var call = list("app", list("var", "original-" + symbol, map("rep", closure)), args, Collections.nCopies(formals.size(), false), false, false, map("rep", result, "foreignCall", declaration));
        var resultLong = fields.getLast(); var binders = new ArrayList<Object>();
        for (int i = 0; i < fields.size(); i++) binders.add(map("id", i == 0 ? "result-state" : "result-long", "lifted", false, "rep", fields.get(i)));
        var body = list("case", call, "result-tuple", list(list("data", "tuple2", list("result-state", "result-long"), list("var", "result-long", map("rep", resultLong)), map("binders", binders))),
            map("rep", resultLong, "binder", map("id", "result-tuple", "lifted", false, "rep", with(result, "evaluated", true))));
        var binding = map("id", symbol, "name", symbol, "arity", formals.size(), "lifted", true, "rep", closure, "expr", list("lam", formals, body, map("rep", closure, "resultRep", resultLong)));
        return map("schema", 1, "module", "SyntheticOriginalStringRts", "unit", "test", "ghc", "9.14.1", "instrument", true, "bindings", list(binding),
            "constructors", list(map("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private ExecutableProgram program(Language language, String backend, Map<String,Object> source) { return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source); }
    private static long length(RootCallTarget target, ManagedAddress address) { return (Long) callScalarTestTarget(target, new Object[]{0L, address, thc.runtime.Unit.INSTANCE}); }
    private static Expr observed(List<String> events, String event, Object value) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) {
                events.add(event);
                if (value instanceof RuntimeException failure) throw failure;
                return value;
            }
        };
    }
    @Test void everyStringRtsOperationPreservesAddressThenStateSelection() throws Exception {
        for (var operation : StringRtsOp.values()) {
            var events = new ArrayList<String>();
            boolean readsAddress = operation == StringRtsOp.STRLEN || operation == StringRtsOp.STRLEN_CSIZE;
            var address = observed(events, "address", ManagedAddress.fromByteArray(new byte[]{65, 66, 0}));
            var state = observed(events, "state", thc.runtime.Unit.INSTANCE);
            var expression = new StringRtsExpression(operation, readsAddress ? new Expr[]{address, state} : new Expr[]{state}, CoreRepresentation.UNKNOWN, 73L);
            var descriptor = FrameDescriptor.newBuilder(); int slot = descriptor.addSlot(FrameSlotKind.Long, null, null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build());
            assertNull(expression.executeTuple(frame, new int[]{slot}, 0));
            assertEquals(readsAddress ? 2L : 73L, frame.getLong(slot), operation.name());
            assertEquals(readsAddress ? List.of("address", "state") : List.of("state"), events, operation.name());
        }
    }
    @Test void stringRtsOperandFailuresRetainOrderAndLeaveDestinationUnchanged() throws Exception {
        var nullEvents = new ArrayList<String>();
        var nullOperation = new StringRtsExpression(null, new Expr[]{observed(nullEvents, "state", thc.runtime.Unit.INSTANCE)}, CoreRepresentation.UNKNOWN, 73L);
        assertThrows(NullPointerException.class, () -> nullOperation.executeTuple(null, new int[]{0}, 0));
        assertEquals(List.of(), nullEvents, "A null operation must fail before evaluating operands");
        for (var operation : List.of(StringRtsOp.STRLEN, StringRtsOp.STRLEN_CSIZE)) {
            var events = new ArrayList<String>(); var failure = new IllegalStateException("operand failure");
            var descriptor = FrameDescriptor.newBuilder(); int slot = descriptor.addSlot(FrameSlotKind.Long, null, null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build()); frame.setLong(slot, 73L);
            var addressFailure = new StringRtsExpression(operation, new Expr[]{observed(events, "address", failure), observed(events, "state", thc.runtime.Unit.INSTANCE)}, CoreRepresentation.UNKNOWN, 73L);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> addressFailure.executeTuple(frame, new int[]{slot}, 0)));
            assertEquals(List.of("address"), events); assertEquals(73L, frame.getLong(slot));
            events.clear();
            var stateFailure = new StringRtsExpression(operation, new Expr[]{observed(events, "address", ManagedAddress.nullAddress()), observed(events, "state", failure)}, CoreRepresentation.UNKNOWN, 73L);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> stateFailure.executeTuple(frame, new int[]{slot}, 0)));
            assertEquals(List.of("address", "state"), events); assertEquals(73L, frame.getLong(slot));
        }
        for (var operation : StringRtsOp.values()) {
            var events = new ArrayList<String>();
            boolean readsAddress = operation == StringRtsOp.STRLEN || operation == StringRtsOp.STRLEN_CSIZE;
            var state = observed(events, "state", 0L);
            var address = observed(events, "address", ManagedAddress.nullAddress());
            var expression = new StringRtsExpression(operation, readsAddress ? new Expr[]{address, state} : new Expr[]{state}, CoreRepresentation.UNKNOWN, 73L);
            var descriptor = FrameDescriptor.newBuilder(); int slot = descriptor.addSlot(FrameSlotKind.Long, null, null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build()); frame.setLong(slot, 91L);
            assertEquals("Invalid zero-width scalar carrier", assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[]{slot}, 0)).getMessage());
            assertEquals(readsAddress ? List.of("address", "state") : List.of("state"), events); assertEquals(91L, frame.getLong(slot));
        }
    }
    private StringRtsOp validateDeclaration(Map<String,Object> declaration, Map<String,Object> canonical) {
        var arguments = (List<?>) canonical.get("argumentReps"); var result = canonical.get("resultRep");
        return CoreStringRtsForeign.validate(map("foreignCall", declaration, "rep", result), arguments,
            Collections.nCopies(arguments.size(), false), result);
    }
    @Test void originalByteStringSizeTDescriptorRequiresItsExactInstalledUnitAndResult() throws Exception {
        var original = (Map<String,Object>) resource("/core/original-bytestring-strlen-descriptor.json");
        var originalTarget = (Map<String,Object>) original.get("target");
        for (var unit : List.of("bytestring-0.12.2.0-inplace", "bytestring-0.12.2.0", "bytestring-0.12.2.0-319833abde312f")) {
            var declaration = with(original, "target", with(originalTarget, "unit", unit));
            assertEquals(StringRtsOp.STRLEN_CSIZE, validateDeclaration(declaration, original));
            var wrong = with(declaration, "resultRep", descriptors().get("strlen").get("resultRep"));
            assertThrows(RuntimeFault.class, () -> validateDeclaration(wrong, original));
        }
        for (var unit : list(null, list("bytestring-0.12.2.0"), "ghc-internal", "foreign", "bytestring-0.12.1.0", "bytestring-0.12.2.0-", "bytestring-0.12.2.0-hash-extra", "bytestring-0.12.2.0 hash", "bytestring-0.12.2.0:hash", "bytestring-0.12.2.0\n")) {
            var wrong = with(original, "target", with(originalTarget, "unit", unit));
            assertThrows(RuntimeFault.class, () -> validateDeclaration(wrong, original));
        }
    }
    @Test void managedCStringStorageRetainsBoundsAndLifetimeChecks() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var managed = ManagedAddress.fromAllocation(ManagedAllocation.mutable(12, 8));
                long[] bytes = {65, 0xce, 0xbb, 0, 80}; for (int i = 0; i < bytes.length; i++) managed.writeWord8(i, bytes[i]);
                assertEquals(3L, managed.cStringLength()); assertEquals(2L, managed.plus(1).cStringLength()); assertEquals(0L, managed.plus(3).cStringLength());
                assertEquals(2L, ManagedAddress.fromByteArray(new byte[]{1,2,0,4}).cStringLength());
                if (System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
                    var nativeAddress = Language.currentState().getNativeAllocations().malloc(8);
                    long[] values = {5,6,0}; for (int i = 0; i < values.length; i++) nativeAddress.writeWord8(i, values[i]);
                    var alias = nativeAddress.plus(1); assertEquals(1L, alias.cStringLength());
                    Language.currentState().getNativeAllocations().free(nativeAddress); assertThrows(RuntimeFault.class, alias::cStringLength);
                }
                assertThrows(RuntimeFault.class, () -> ManagedAddress.fromByteArray(new byte[]{1,2}).cStringLength());
                assertThrows(RuntimeFault.class, () -> managed.plus(12).cStringLength()); assertThrows(RuntimeFault.class, () -> ManagedAddress.nullAddress().cStringLength());
                assertEquals(0L, ManagedAddress.fromHex("0000000000000000").cStringLength());
                assertThrows(RuntimeFault.class, () -> ManagedAddress.unownedNumeric(0x1234L).cStringLength());
                var pointers = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16,8)); pointers.writeAddressElementIndex(0, managed);
                assertThrows(RuntimeFault.class, pointers::cStringLength);
            } finally { context.leave(); }
        }
    }
    @Test @Tag("foreign-exceptions-full-core")
    void originalStrlenDeclarationsUseOrdinaryNativeLinkageOnCompiledEntries() throws Exception {
        var byteString = (Map<String,Object>) resource("/core/original-bytestring-strlen-descriptor.json");
        for (var declaration : List.of(descriptors().get("strlen"), byteString))
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = ForeignExceptionFixtureSupport.nativeModules(List.of(module("strlen", declaration, declaration)));
                var guest = program(language, backend, source); var target = guest.entryTarget("strlen");
                var text = ManagedAddress.fromAllocation(ManagedAllocation.nativeMutable(5, 8));
                long[] bytes = {65, 0xce, 0xbb, 0, 80}; for (int i = 0; i < bytes.length; i++) text.writeWord8(i, bytes[i]);
                assertEquals(3L, length(target, text)); assertEquals(0L, length(target, text.plus(3)));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                assertEquals(2L, length(target, text.plus(1)));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                assertEquals(0L, guest.diagnostics().get("unsupportedTraps"));
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    // Unchanged foreign-call descriptors from original GHC.Platform.Ways.
    private Map<String,Map<String,Object>> hostWayDescriptors() throws Exception {
        return (Map<String,Map<String,Object>>) resource("/core/original-ghc-host-ways-descriptors.json");
    }
    private TargetLayout hostWayLayout() {
        var document = new LinkedHashMap<>(StackInfoTestLayout.document());
        if (System.getProperty("os.name").startsWith("Windows")) {
            var compiler = new LinkedHashMap<>((Map<String,Object>) document.get("compiler"));
            String platform = compiler.get("platform").toString().replace("-linux", "-windows");
            compiler.put("platform", platform); compiler.put("way", "vanilla-nonprofiling");
            document.put("compiler", compiler);
            document.put("layout", with((Map<?,?>) document.get("layout"), "targetPlatform", platform));
        }
        return TargetLayout.fromDocument(document);
    }
    @Test void originalCompilerHostWaysUseAdmittedAbiAndManagedRtsPolicy() throws Exception {
        var declarations = hostWayDescriptors();
        assertEquals(Set.of("rts_isDynamic", "rts_isProfiled", "rts_isThreaded", "rts_isDebugged", "rts_isTracing"), declarations.keySet());
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : declarations.entrySet()) {
                    String symbol = entry.getKey(); var declaration = entry.getValue();
                    var source = module(symbol, declaration, declaration);
                    source.put("targetLayout", hostWayLayout());
                    var guest = program(language, backend, source); var target = guest.entryTarget(symbol);
                    long expected = symbol.equals("rts_isDynamic") && !System.getProperty("os.name").startsWith("Windows") ? 1L : 0L;
                    assertEquals(expected, Calls.target(target, new Object[]{0L, thc.runtime.Unit.INSTANCE}), symbol);
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                    long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected, Calls.target(target, new Object[]{0L, thc.runtime.Unit.INSTANCE}), symbol);
                    assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, 0L}));
                }
            } finally { context.leave(); }
        }
    }
    @Test void compilerHostWaysRequireLayoutAndExactOriginalDeclarations() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : hostWayDescriptors().entrySet()) {
                    String symbol = entry.getKey(); var declaration = entry.getValue();
                    assertThrows(RuntimeFault.class, () -> program(language, backend, module(symbol, declaration, declaration)).entryTarget(symbol));
                    var target = (Map<String,Object>) declaration.get("target");
                    for (var wrong : List.of(with(declaration, "safety", "safe"), with(declaration, "arity", 2L),
                            with(declaration, "argumentReps", List.of()), with(declaration, "resultRep", map("kind", "long", "primReps", list("IntRep"), "evaluated", false)),
                            with(declaration, "target", with(target, "unit", "other-compiler")),
                            with(declaration, "target", with(target, "isFunction", false)))) {
                        var source = module(symbol, wrong, declaration); source.put("targetLayout", hostWayLayout());
                        assertThrows(RuntimeFault.class, () -> Calls.target(program(language, backend, source).entryTarget(symbol),
                            new Object[]{0L, thc.runtime.Unit.INSTANCE}), backend + " " + symbol + " " + wrong);
                    }
                }
                assertThrows(IllegalArgumentException.class, () -> StackInfoTestLayout.layout(Map.of("profiled", true)));
            } finally { context.leave(); }
        }
    }
    @Test void originalRtsWayQueryUsesNonthreadedFdContractOnCompiledEntry() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var guest = program(language, backend, module("rts_isThreaded")); var target = guest.entryTarget("rts_isThreaded");
                assertEquals(0L, Calls.target(target, new Object[]{0L, thc.runtime.Unit.INSTANCE}));
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, Calls.target(target, new Object[]{0L, thc.runtime.Unit.INSTANCE}));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target);
            } finally { context.leave(); }
        }
    }
    @Test void originalStringRtsDescriptorValidatorRejectsAlteredAbi() throws Exception {
        for (var symbol : List.of("strlen", "rts_isThreaded")) {
            assertThrows(RuntimeFault.class, () -> CoreStringRtsForeign.validateHead(list("var", "forged", map("rep", closure)), true));
            var canonical = descriptors().get(symbol);
            class Control { void reject(Consumer<Map<String,Object>> change) {
                var malformed = new LinkedHashMap<>(canonical); change.accept(malformed);
                assertThrows(RuntimeFault.class, () -> validateDeclaration(malformed, canonical));
            }}
            var control = new Control(); control.reject(it -> it.put("safety", "safe")); control.reject(it -> it.put("arity", 3L));
            control.reject(it -> it.put("argumentReps", List.of()));
            control.reject(it -> it.put("resultRep", map("kind", "long", "primReps", list("IntRep"), "evaluated", false)));
            control.reject(it -> it.put("target", with((Map<?,?>) it.get("target"), "unit", "foreign")));
        }
    }
}
