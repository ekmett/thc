// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.CoreForeignArtifacts;
import thc.CoreModules;
import thc.CoreCbdFixtures;
import thc.Json;
import thc.Language;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class InterfaceCoreNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/interface-core");
    private final List<String> entries = List.of("opaqueEntry", "inlineEntry", "recursiveEntry", "coercionEntry", "wrapperEntry");
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(directory.resolve(path))); }
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    private static Map<String, Object> select(List<Map<String, Object>> values, String key, String expected) {
        var selected = new ArrayList<Map<String, Object>>();
        for (var value : values) if (expected.equals(value.get(key))) selected.add(value);
        if (selected.isEmpty()) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        if (selected.size() != 1) throw new IllegalArgumentException("Collection contains more than one matching element.");
        return selected.getFirst();
    }
    private Map<String, Object> source(String entry) throws Exception {
        var modules = new StringBuilder("[");
        var layout = CoreCbdFixtures.appendModules(modules, directory.resolve("packages.json").toAbsolutePath().toString());
        assertNotNull(layout, "selected installed Core carries the target-derived layout"); assertEquals("fixture", Objects.requireNonNull(layout).getCompilerAbi());
        modules.append(']'); var name = entry.equals("coercionEntry") ? "CBVCoercionAudit" : "InterfaceLibrary";
        return select((List<Map<String, Object>>) Json.parse(modules.toString()), "module", name);
    }
    private List<List<Long>> oracle() throws Exception {
        var manifest = read("manifest.json"); assertEquals(entries, manifest.get("entries")); assertEquals("thc-interface-fixture-0.1", manifest.get("unit"));
        var controls = read("driver-controls.json");
        assertEquals(true, controls.get("installedArtifactsHashed")); assertEquals(false, controls.get("compilerBinariesHashed")); assertEquals("ghc-retained-binary-v1", controls.get("interfaceFingerprint"));
        for (var name : List.of("sourceDeleted", "unchangedReuse", "thinMissing", "identityFailure", "wrongWayFailure", "foreignArtifactsArchived", "failedRefreshPreservedBundle")) assertEquals(true, controls.get(name), name);
        assertEquals(List.of("opaque-body", "private-worker", "recursive-groups", "thin-unavailable", "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-archived",
            "private-flags", "repeat-load", "helper-protocol", "installed-cbv-worker", "installed-wired-unit", "foreign-association-absence", "foreign-linked-clock", "typed-foreign-export-associations",
            "retained-export-registration", "managed-export-original", "installed-payload-cache"), manifest.get("controls"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var file = root.resolve(entry.getKey()).toFile(); assertTrue(file.getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()));
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))); assertEquals(entry.getValue(), digest, kind + "/" + entry.getKey());
        }
        var rows = new ArrayList<List<Long>>();
        for (var line : Files.readAllLines(directory.resolve("logs/native-oracle.stdout"))) {
            var row = new ArrayList<Long>(); for (var value : line.split(" ", -1)) row.add(Long.parseLong(value)); rows.add(row);
        }
        var expectedInputs = new ArrayList<Long>(); for (long i = -10; i <= 10; i++) expectedInputs.add(i);
        var actualInputs = new ArrayList<Long>(); for (var row : rows) actualInputs.add(row.get(0)); assertEquals(expectedInputs, actualInputs);
        for (var row : rows) assertEquals(List.of(row.get(0), row.get(0) * 7 + 11, row.get(0) + 3, Math.max(0L, row.get(0)) * 7 + 11, row.get(0) + 7, row.get(0)), row);
        return rows;
    }
    @Test void realForeignArtifactsSurviveCheckedArchiveButCannotExecute() throws Exception {
        oracle(); var direct = read("InterfaceForeign.json"); var modules = new StringBuilder("[");
        var layout = CoreCbdFixtures.appendModules(modules, directory.resolve("foreign-packages.json").toAbsolutePath().toString());
        assertNotNull(layout, "archiving foreign code preserves the selected target layout"); assertEquals("fixture", Objects.requireNonNull(layout).getCompilerAbi()); modules.append(']');
        var archived = single((List<Map<String, Object>>) Json.parse(modules.toString()));
        assertEquals(2L, archived.get("schema")); assertEquals(direct.get("foreign"), archived.get("foreign"));
        var artifacts = (Map<String, Object>) archived.get("foreign"); assertEquals("not-linked", artifacts.get("execution"));
        var stubs = (Map<String, Object>) artifacts.get("stubs");
        assertTrue(((String) stubs.get("header")).contains("thc_interface_fixture")); assertTrue(((String) stubs.get("source")).contains("rts_lock"));
        assertTrue(((String) stubs.get("source")).contains("registerForeignExports")); assertEquals(List.of(), stubs.get("finalizers"));
        var initializer = single((List<Map<String, Object>>) stubs.get("initializers"));
        assertEquals(Map.of("isInitializer", true, "unit", "thc-interface-fixture-0.1", "module", "InterfaceForeign", "name", "fexports"), initializer);
        var file = single((List<Map<String, Object>>) artifacts.get("files")); assertEquals("int thc_interface_c_control(void) { return 29; }\n", file.get("source"));
        for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("thc", Json.stringify(Map.of("entry", "exported", "backend", backend, "diagnosticUnsupported", diagnostic, "modules", List.of(archived)))));
            assertTrue(error.getMessage().contains("Unsupported foreign code/registration"), error.getMessage()); assertTrue(error.getMessage().contains("thc-interface-fixture-0.1:InterfaceForeign"));
        }
    }
    private static byte[] bytes() { var bytes = new byte[32]; Arrays.fill(bytes, (byte) 0x5a); return bytes; }
    @Test void originalCapiBitcodeCallsThroughManagedOffsetWithoutExposingHostPointers() throws Exception {
        oracle(); var archived = read("clock-capi.json"); var link = Objects.requireNonNull(CoreForeignArtifacts.linked(archived));
        assertEquals(Set.of("fixture_clock_id", "fixture_clock_time", "fixture_clock_resolution"), link.getSymbols());
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var cbits = Language.currentState().cbits(); cbits.link(link); long clock = cbits.capiZero(link.getUnit(), "fixture_clock_id", false);
                var bytes = bytes(); var address = ManagedAddress.fromByteArray(bytes).plus(5);
                assertEquals(0L, cbits.capiWordAddress(link.getUnit(), "fixture_clock_time", clock, address).getValue());
                var view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); assertTrue(view.getLong(5) >= 0);
                long nanos = view.getLong(13); assertTrue(nanos >= 0 && nanos < 1_000_000_000L);
                assertEquals((byte) 0x5a, bytes[4]); assertEquals((byte) 0x5a, bytes[21]);
                assertEquals(0L, cbits.capiWordAddress(link.getUnit(), "fixture_clock_resolution", clock, address).getValue());
                nanos = view.getLong(13); assertTrue(nanos >= 0 && nanos < 1_000_000_000L);
            } finally { context.leave(); }
        }
    }
    private static Map<String, Object> scalar(String rep) { return scalar(rep, true); }
    private static Map<String, Object> scalar(String rep, boolean evaluated) {
        return Map.of("kind", rep == null ? "void" : rep.equals("AddrRep") ? "address" : rep.equals("BoxedRep (Just Lifted)") ? "closure" : "long",
            "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private static Map<String, Object> tuple(boolean evaluated) { return Map.of("kind", "unknown", "primReps", List.of("Int32Rep"), "aggregate", "unboxed-tuple", "components", List.of(scalar(null), scalar("Int32Rep")), "evaluated", evaluated); }
    @Test void linkedCapiStateAndAddressCallsRetainTypedCompiledAstAndBytecodeEntries() throws Exception {
        oracle(); var archived = read("clock-capi.json"); var link = Objects.requireNonNull(CoreForeignArtifacts.linked(archived));
        var reps = Arrays.asList("Word64Rep", "AddrRep", null); var formals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < reps.size(); i++) formals.add(Map.of("id", "p" + i, "lifted", false, "rep", scalar(reps.get(i))));
        var symbols = new ArrayList<String>(); for (var symbol : link.getSymbols()) if (symbol.endsWith("fixture_clock_time")) symbols.add(symbol); var symbol = single(symbols);
        var argumentReps = new ArrayList<Map<String, Object>>(); for (var rep : reps) argumentReps.add(scalar(rep, false));
        var descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", symbol, "unit", link.getUnit(), "isFunction", true),
            "convention", "capi", "safety", "unsafe", "arity", 3L, "suppliedArity", 3L, "argumentReps", argumentReps, "resultRep", tuple(false));
        var operands = new ArrayList<List<Object>>(); for (int i = 0; i < reps.size(); i++) operands.add(List.of("var", "p" + i, Map.of("rep", scalar(reps.get(i)))));
        var call = List.of("app", List.of("var", "foreign-clock-time", Map.of("rep", scalar("BoxedRep (Just Lifted)"))), operands,
            List.of(false, false, false), false, false, Map.of("rep", tuple(true), "foreignCall", descriptor));
        var binders = List.of(Map.of("id", "s", "lifted", false, "rep", scalar(null)), Map.of("id", "value", "lifted", false, "rep", scalar("Int32Rep")));
        var alternative = List.of("data", "T2", List.of("s", "value"), List.of("var", "value", Map.of("rep", scalar("Int32Rep"))), Map.of("binders", binders));
        var body = List.of("case", call, "pair", List.of(alternative), Map.of("rep", scalar("Int32Rep"), "binder", Map.of("id", "pair", "lifted", false, "rep", tuple(true))));
        Map<String, Object> module = Map.of("instrument", true, "foreignLinks", List.of(link), "constructors", List.of(Map.of("id", "T2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", List.of(Map.of("id", "clock", "name", "clock", "arity", 3, "lifted", true, "rep", scalar("BoxedRep (Just Lifted)"),
                "expr", List.of("lam", formals, body, Map.of("rep", scalar("BoxedRep (Just Lifted)"), "resultRep", scalar("Int32Rep"))))));
        for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().cbits().link(link);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                var target = program.entryTarget("clock"); var cbits = Language.currentState().cbits(); long clock = cbits.capiZero(link.getUnit(), "fixture_clock_id", false);
                checkClock(program, target, clock, false); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); checkClock(program, target, clock, true);
                var unchanged = bytes();
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, clock, ManagedAddress.fromByteArray(unchanged).plus(20), thc.runtime.Unit.INSTANCE}));
                assertArrayEquals(bytes(), unchanged);
            } finally { context.leave(); }
        }
    }
    private static void checkClock(ExecutableProgram program, RootCallTarget target, long clock, boolean compiled) throws Exception {
        var bytes = bytes(); var address = ManagedAddress.fromByteArray(bytes).plus(5);
        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
        assertEquals(0, Calls.target(target, new Object[]{0L, clock, address, thc.runtime.Unit.INSTANCE}));
        if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); valid(target); }
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); assertTrue(view.getLong(5) >= 0);
        long nanos = view.getLong(13); assertTrue(nanos >= 0 && nanos < 1_000_000_000L);
        assertEquals((byte) 0x5a, bytes[4]); assertEquals((byte) 0x5a, bytes[21]);
    }

    @Test void helperPreservesGenuineInstalledWorkerCbvMarks() throws Exception {
        oracle(); var direct = read("direct/CBVCoercionAudit.json"); var loaded = source("coercionEntry");
        var original = select((List<Map<String, Object>>) direct.get("bindings"), "name", "$wwitnessed");
        var hydrated = select((List<Map<String, Object>>) loaded.get("bindings"), "name", "$wwitnessed");
        assertEquals(List.of(false, false, true), original.get("entryStrict")); assertEquals(original.get("entryStrict"), hydrated.get("entryStrict"));
        assertEquals("ghc-id", original.get("entryStrictSource")); assertEquals("ghc-id", hydrated.get("entryStrictSource"));
        assertEquals(((Map<?, ?>) original.get("info")).get("cbvMarks"), ((Map<?, ?>) hydrated.get("info")).get("cbvMarks"));
        var parameters = (List<Map<String, Object>>) ((List<?>) hydrated.get("expr")).get(1);
        assertEquals(true, parameters.get(0).get("coercion")); assertEquals(List.of(), CoreRepresentations.binder(parameters.get(0)).getPrimReps());
        assertEquals(true, parameters.get(2).get("lifted"));
        var missing = read("logs/helper-thin.stdout"); assertEquals("unavailable", missing.get("status"));
        assertEquals("complete-interface-core", missing.get("capability")); assertFalse(missing.containsKey("core"));
        var command = read("logs/helper-thin.command.json"); assertEquals(3L, ((Number) command.get("exit")).longValue());
        var wired = read("wired-unit.json"); var wiredResponse = read("logs/helper-wired-unit.stdout"); var wiredCommand = read("logs/helper-wired-unit.command.json");
        assertEquals("ghc-internal", wired.get("interfaceUnit")); assertNotEquals(wired.get("registeredUnit"), wired.get("interfaceUnit")); assertEquals(wired.get("expectedExit"), wiredCommand.get("exit"));
        if (Boolean.TRUE.equals(wired.get("completeCore"))) {
            assertEquals("loaded", wiredResponse.get("status")); assertEquals(wired.get("interfaceUnit"), ((Map<?, ?>) wiredResponse.get("core")).get("unit"));
        } else {
            assertEquals("unavailable", wiredResponse.get("status")); assertEquals("complete-interface-core", wiredResponse.get("capability"));
            assertEquals(wired.get("registeredUnit"), wiredResponse.get("unit")); assertFalse(wiredResponse.containsKey("core"));
        }
    }
    @Test void completeInterfacePreservesMetadataAndPassesStrictAdmission() throws Exception {
        oracle(); var source = source("opaqueEntry"); assertEquals("optimized-Core-after-Tidy-before-CorePrep", source.get("boundary"));
        assertEquals("thc-interface-fixture-0.1", source.get("unit")); assertFalse(Files.exists(directory.resolve("source/InterfaceLibrary.hs")));
        var bindings = (List<Map<String, Object>>) source.get("bindings"); var worker = select(bindings, "name", "privateWorker");
        assertTrue(((String) worker.get("id")).startsWith("thc-interface-fixture-0.1:InterfaceLibrary.privateWorker_"));
        boolean recursive = false;
        for (var group : (List<Map<String, Object>>) source.get("groups")) if (Boolean.TRUE.equals(group.get("recursive"))) { recursive = true; break; }
        assertTrue(recursive); boolean token = false;
        for (var constructor : (List<Map<String, Object>>) source.get("constructors")) if (((String) constructor.get("id")).endsWith("InterfaceLibrary.Token")) { token = true; break; }
        assertTrue(token); var files = (List<Map<String, Object>>) source.get("sourceFiles"); assertTrue(!files.isEmpty());
        boolean missing = true; for (var file : files) if (file.get("content") != null) { missing = false; break; }
        assertTrue(missing, "Missing source text must not be fabricated"); assertTrue(!((List<?>) source.get("sourceSpans")).isEmpty());
        for (var entry : entries) {
            var audit = read(entry + "-audit.json"); assertEquals(true, audit.get("accepted"), entry);
            assertEquals(List.of(), audit.get("issues"), entry); assertEquals(List.of(), audit.get("missingGlobals"), entry);
        }
    }
    private static void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target)) return;
        var node = target.getRootNode(); var roots = new ArrayList<Node>(); roots.add(node);
        if (node instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) roots.add(cached); }
        var calls = new ArrayList<DirectCallNode>(); for (var root : roots) calls.addAll(NodeUtil.findAllNodeInstances(root, DirectCallNode.class));
        var targets = new ArrayList<RootCallTarget>(); for (var call : calls) if (call.getCurrentCallTarget() instanceof RootCallTarget current) targets.add(current);
        var guests = new ArrayList<RootCallTarget>(); for (var current : targets) if (current.getRootNode() instanceof GuestRoot) guests.add(current);
        for (var current : guests) visit(current, seen, found); found.add(target);
    }
    private static List<RootCallTarget> targets(RootCallTarget entry) {
        var found = new ArrayList<RootCallTarget>(); var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); visit(entry, seen, found); return found;
    }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
    private record Constant(Thunk thunk, RootCallTarget target) {}
    private static void check(List<Long> row, int index, RootCallTarget target, String backend, String entry, Language language) {
        assertEquals(row.get(index + 1), Calls.target(target, new Object[]{0L, row.get(0)}), backend + "/" + entry + "/" + row.get(0));
        assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
    }
    @Test void recoveredBodiesMatchNativeInFirstInstalledAstAndBytecodeCode() throws Exception {
        var rows = oracle();
        for (var backend : List.of("ast", "bytecode")) for (int index = 0; index < entries.size(); index++)
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
                var entry = entries.get(index); context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(source(entry), entry, true)); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var target = program.entryTarget(entry);
                    // Save the target before a CAF update clears it. A
                    // memoized constant is not a per-invocation function.
                    var constants = new ArrayList<Constant>();
                    for (var binding : (List<Map<String, Object>>) linked.get("bindings")) if (program.entryValue((String) binding.get("id")) instanceof Thunk thunk) {
                        var body = thunk.getTarget(); if (body == null) throw new IllegalStateException("Required value was null."); constants.add(new Constant(thunk, body));
                    }
                    for (int repeat = 0; repeat < 3; repeat++) for (var row : rows) check(row, index, target, backend, entry, language);
                    var active = targets(target); var memoized = new ArrayList<Constant>();
                    for (var constant : constants) if (constant.thunk().getState() == 2) memoized.add(constant);
                    var perCall = new ArrayList<RootCallTarget>();
                    for (var candidate : active) {
                        boolean cached = false; for (var constant : memoized) if (constant.target() == candidate) { cached = true; break; }
                        if (!cached) perCall.add(candidate);
                    }
                    var thunkCounts = program.diagnostics().get("thunkEvaluationsByLabel");
                    if (entry.equals("coercionEntry")) {
                        assertEquals(1, memoized.size(), "The original lifted Spine CAF was evaluated once"); assertNull(single(memoized).thunk().getTarget());
                        boolean observed = false; for (var current : active) if (current == single(memoized).target()) { observed = true; break; }
                        assertTrue(observed); assertEquals(2, perCall.size()); var occurrences = new LinkedHashSet<String>();
                        for (var current : perCall) { var identity = ((GuestRoot) current.getRootNode()).getCoreIdentity(); occurrences.add(identity == null ? null : identity.occurrence()); }
                        assertEquals(Set.of("coercionEntry", "$wwitnessed"), occurrences);
                    } else assertEquals(active, perCall);
                    if (entry.equals("opaqueEntry")) assertTrue(active.size() >= 2, "Keep the opaque private call");
                    if (entry.equals("wrapperEntry")) {
                        boolean wrapper = false;
                        for (var current : active) { var identity = ((GuestRoot) current.getRootNode()).getCoreIdentity(); if (identity != null && "$WToken".equals(identity.occurrence())) { wrapper = true; break; } }
                        assertTrue(wrapper, "Compile and enter the recovered original constructor wrapper");
                    }
                    for (var current : active) { current.getClass().getMethod("compile", boolean.class).invoke(current, true); valid(current); }
                    var compiledRows = new ArrayList<>(rows.reversed()); compiledRows.addAll(rows);
                    for (var row : compiledRows) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(row, index, target, backend, entry, language);
                        long entered = ((Number) program.diagnostics().get("compiledEntries")).longValue() - before;
                        var names = new ArrayList<String>(); for (var current : perCall) names.add(current.getRootNode().getName());
                        assertTrue(entered >= perCall.size(), backend + "/" + entry + " first installed invocation entered " + entered + " of " + names);
                        if (entry.equals("coercionEntry")) {
                            assertEquals(2L, entered, "Entry and real CBV worker must both execute compiled");
                            assertEquals(thunkCounts, program.diagnostics().get("thunkEvaluationsByLabel"), "The memoized Spine CAF must not execute again");
                        }
                        for (var current : active) valid(current);
                    }
                    assertEquals("reject-at-load", program.diagnostics().get("unsupportedPolicy"));
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue()); assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                } finally { context.leave(); }
            }
    }
}
