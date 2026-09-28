// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kotlin.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Retained genuine GHC declarations; synthetic callers isolate the versioned host ABI. */
@Timeout(60)
@SuppressWarnings("unchecked")
class RuntimeServicesApiTest {
    private final Map<String, Object> state = scalar("void", null);
    private final Map<String, Object> integer = scalar("long", "IntRep");
    private final Map<String, Object> cInt = scalar("long", "Int32Rep");
    private final Map<String, Object> cLong = scalar("long", "Int64Rep");
    private final Map<String, Object> address = scalar("address", "AddrRep");
    private final Map<String, Object> closure = scalar("closure", "BoxedRep (Just Lifted)");
    private Map<String, Object> result() { return tuple(state, cLong); }
    private record Parameter(String name, Map<String, Object> rep) {}
    private Map<String, List<Parameter>> arguments() {
        var arguments = new LinkedHashMap<String, List<Parameter>>();
        arguments.put("query", List.of(new Parameter("selector", cInt), new Parameter("index", cLong),
            new Parameter("detail", cLong), new Parameter("s", state)));
        arguments.put("control", List.of(new Parameter("selector", cInt), new Parameter("setting", cLong), new Parameter("s", state)));
        arguments.put("trace", List.of(new Parameter("operation", cInt), new Parameter("token", cLong),
            new Parameter("bytes", address), new Parameter("length", cLong), new Parameter("s", state)));
        return arguments;
    }

    private static Map<String, Object> scalar(String kind, String rep) {
        return Map.of("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", true);
    }
    @SafeVarargs private static Map<String, Object> tuple(Map<String, Object>... fields) {
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(fields),
            "primReps", Arrays.stream(fields).flatMap(field -> ((List<?>) field.get("primReps")).stream()).toList(), "evaluated", true);
    }
    private static List<Object> variable(String name, Map<String, Object> rep) { return List.of("var", name, Map.of("rep", rep)); }
    private List<Object> literal(long value, Map<String, Object> rep) {
        return List.of("lit", rep.equals(cInt) ? "int32" : "int64", Long.toString(value), Map.of("rep", rep));
    }
    private Map<String, Object> binder(String name, Map<String, Object> rep) {
        return Map.of("id", name, "lifted", rep.equals(closure), "rep", rep);
    }
    private Map<String, Object> descriptor(String name) throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/runtime-services-descriptors.json"))) {
            return (Map<String, Object>) ((Map<?, ?>) Json.INSTANCE.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)))
                .get("thc_runtime_v1_" + name);
        }
    }
    private List<Object> foreign(String name) throws IOException { return foreign(name, descriptor(name)); }
    private List<Object> foreign(String name, Map<String, Object> declaration) {
        return foreign(name, declaration, arguments().get(name).stream().map(parameter -> variable(parameter.name(), parameter.rep())).toList());
    }
    private List<Object> foreign(String name, Map<String, Object> declaration, List<List<Object>> operands) {
        return List.of("app", variable("foreign-" + name, closure), operands, Collections.nCopies(operands.size(), false),
            false, false, Map.of("rep", result(), "foreignCall", declaration));
    }
    private List<Object> unpack(List<Object> value, String next, String answer) { return unpack(value, next, answer, variable(answer, cLong)); }
    private List<Object> unpack(List<Object> value, String next, String answer, List<Object> body) {
        return List.of("case", value, "pair-" + next, List.of(List.of("data", "tuple2", List.of(next, answer), body,
            Map.of("binders", List.of(binder(next, state), binder(answer, cLong))))),
            Map.of("rep", cLong, "binder", binder("pair-" + next, result())));
    }
    private Map<String, Object> binding(String name, List<Parameter> parameters, List<Object> body) {
        return Map.of("id", name, "name", name, "arity", parameters.size(), "lifted", true, "rep", closure,
            "expr", List.of("lam", parameters.stream().map(parameter -> binder(parameter.name(), parameter.rep())).toList(), body,
                Map.of("rep", closure, "resultRep", cLong)));
    }
    private static Map<String, Object> module(List<Map<String, Object>> bindings) {
        return Map.of("schema", 1, "ghc", "9.14.1", "unit", "test", "module", "RuntimeServicesSynthetic",
            "instrument", true, "bindings", bindings,
            "constructors", List.of(Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private Map<String, Object> module() throws IOException {
        var entries = new ArrayList<Map<String, Object>>();
        for (var entry : arguments().entrySet()) entries.add(binding(entry.getKey(), entry.getValue(), unpack(foreign(entry.getKey()), "next", "answer")));
        var enable = foreign("control", descriptor("control"), List.of(literal(500, cInt), literal(1, cLong), variable("s", state)));
        var emit = foreign("trace", descriptor("trace"), arguments().get("trace").stream().map(parameter ->
            variable(parameter.name().equals("s") ? "enabled" : parameter.name(), parameter.rep())).toList());
        entries.add(binding("enableThenTrace", arguments().get("trace"),
            unpack(enable, "enabled", "enabled-result", unpack(emit, "done", "answer"))));
        return module(entries);
    }
    private static Context context() { return context(new ByteArrayOutputStream()); }
    private static Context context(ByteArrayOutputStream output) { return context(output, false, false); }
    private static Context context(ByteArrayOutputStream output, boolean nativeAccess, boolean threads) {
        return Context.newBuilder("thc").err(output).allowNativeAccess(nativeAccess).allowCreateThread(threads).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private ExecutableProgram program(Language language, String backend) throws IOException { return program(language, backend, module()); }
    private static ExecutableProgram program(Language language, String backend, Map<String, Object> source) {
        return backend.equals("ast") ? new Program(language, source, false, false) : new BytecodeProgram(language, source, true);
    }
    private static long call(ExecutableProgram guest, String name, Object... values) {
        // The CInt selector/operation uses an Int carrier in the typed entry.
        var arguments = new Object[values.length + 2];
        arguments[0] = 0L;
        System.arraycopy(values, 0, arguments, 1, values.length);
        arguments[arguments.length - 1] = Unit.INSTANCE;
        arguments[1] = ((Long) values[0]).intValue();
        return (Long) callScalarTestTarget(guest.entryTarget(name), arguments);
    }
    private static long query(ExecutableProgram guest, long selector) { return query(guest, selector, 0, 0); }
    private static long query(ExecutableProgram guest, long selector, long index, long detail) { return call(guest, "query", selector, index, detail); }
    private static void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
        assertNull(handoff.getPending());
    }
    private static void valid(RootCallTarget target) throws ReflectiveOperationException {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName());
    }
    private static void compile(RootCallTarget target) throws ReflectiveOperationException {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
    }
    private static List<Map<String, Object>> records(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isEmpty())
            .map(line -> (Map<String, Object>) Json.INSTANCE.parse(line)).toList();
    }

    @Test void queriesReportExecutingBackendPermissionsAndActualProvidersOnFirstInstalledCalls() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean permissions : new boolean[]{false, true})
            try (var context = context(new ByteArrayOutputStream(), permissions, permissions)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, backend);
                    var target = guest.entryTarget("query");
                    class Corpus {
                        boolean compiled;
                        long measured(long selector) throws ReflectiveOperationException { return measured(selector, 0, 0); }
                        long measured(long selector, long index, long detail) throws ReflectiveOperationException {
                            long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                            long answer = query(guest, selector, index, detail);
                            if (compiled) {
                                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue());
                                valid(target);
                            }
                            released(language);
                            return answer;
                        }
                        void exercise(boolean compiled) throws ReflectiveOperationException {
                            this.compiled = compiled;
                            assertEquals(1L, measured(0));
                            assertEquals(backend.equals("ast") ? 1L : 2L, measured(1));
                            assertEquals(permissions ? 1L : 0L, measured(2));
                            assertEquals(permissions ? 1L : 0L, measured(3));
                            assertTrue(measured(4) >= 1);
                            for (long selector : new long[]{5, 6, 7}) {
                                long length = measured(selector, 0, -1);
                                assertTrue(length > 0);
                                assertTrue(Character.isValidCodePoint((int) measured(selector, 0, 0)));
                            }
                            assertTrue(measured(200) >= 0, "JVM heap used bytes");
                            assertTrue(measured(201) >= 0, "JVM heap committed bytes");
                            assertEquals(0L, measured(208), "No context-owned libc allocations");
                            assertEquals(0L, measured(209), "No context-owned libc allocations");
                            long collectors = measured(300);
                            assertTrue(collectors >= 0);
                            if (collectors > 0) {
                                assertTrue(measured(301, 0, -1) > 0);
                                long count = measured(302);
                                assertTrue(count >= 0 || count == RuntimeServiceStatus.UNAVAILABLE);
                            }
                        }
                    }
                    var corpus = new Corpus();
                    corpus.exercise(false);
                    compile(target);
                    corpus.exercise(true);
                    assertThrows(RuntimeFault.class, () -> query(guest, 9999));
                    assertThrows(RuntimeFault.class, () -> query(guest, 0, 1, 0));
                    assertThrows(RuntimeFault.class, () -> query(guest, 5, 0, -2));
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, 0L, 0L, 0L, 7L}));
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, 0, 0L, 0L, 7L}));
                    released(language);
                } finally { context.leave(); }
            }
    }

    @Test void traceControlAndBytesExecuteInStateOrderAndRetainTheirFirstCompiledEntries() throws Exception {
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream();
            try (var context = context(output)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, backend);
                    var text = "runtime λ\uD83D\uDE00\n\"event\"\u0000tail";
                    var bytes = text.getBytes(StandardCharsets.UTF_8);
                    var pointer = ManagedAddress.fromByteArray(bytes);
                    var targets = new LinkedHashMap<String, RootCallTarget>();
                    for (var name : List.of("control", "trace", "enableThenTrace")) targets.put(name, guest.entryTarget(name));
                    assertEquals(0L, query(guest, 500));
                    assertEquals(RuntimeServiceStatus.DISABLED, call(guest, "trace", 0L, 0L, pointer, (long) bytes.length));
                    assertEquals(0, output.size());
                    class Corpus {
                        boolean compiled;
                        long checked(String name, Object... values) throws ReflectiveOperationException {
                            long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                            long answer = call(guest, name, values);
                            if (compiled) {
                                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue(), name);
                                valid(targets.get(name));
                            }
                            released(language);
                            return answer;
                        }
                        void exercise(boolean compiled) throws ReflectiveOperationException {
                            this.compiled = compiled;
                            assertEquals(0L, checked("control", 500L, 0L));
                            output.reset();
                            assertEquals(0L, checked("enableThenTrace", 0L, 0L, pointer, (long) bytes.length));
                            assertEquals(1L, query(guest, 500));
                            long token = checked("trace", 1L, 0L, pointer, (long) bytes.length);
                            assertTrue(token > 0);
                            assertEquals(0L, checked("trace", 2L, token, ManagedAddress.nullAddress(), 0L));
                            var actual = records(output);
                            assertEquals(List.of("event", "begin", "end"), actual.stream().map(record -> record.get("phase")).toList());
                            assertEquals(List.of(0L, token, token), actual.stream().map(record -> record.get("span")).toList());
                            assertEquals(text, actual.get(0).get("name"));
                            assertEquals(text, actual.get(1).get("name"));
                            assertEquals(1, actual.stream().map(record -> record.get("context")).distinct().toList().size());
                            assertTrue((Long) actual.get(2).get("elapsedNanos") >= 0);
                        }
                    }
                    var corpus = new Corpus();
                    corpus.exercise(false);
                    for (var target : targets.values()) compile(target);
                    corpus.exercise(true);
                    // Invalid State must be checked before either control or trace has an effect.
                    assertEquals(0L, call(guest, "control", 500L, 0L));
                    output.reset();
                    assertThrows(RuntimeFault.class, () -> Calls.target(guest.entryTarget("enableThenTrace"),
                        new Object[]{0L, 0L, 0L, pointer, (long) bytes.length, 7L}));
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(guest.entryTarget("enableThenTrace"),
                        new Object[]{0L, 0, 0L, pointer, (long) bytes.length, 7L}));
                    assertEquals(0L, query(guest, 500));
                    assertEquals(0, output.size());
                    assertEquals(0L, call(guest, "control", 500L, 1L));
                    for (var invalid : List.of(Map.entry(pointer, -1L), Map.entry(pointer, bytes.length + 1L),
                            Map.entry(ManagedAddress.fromByteArray(new byte[]{(byte) 0xc3, 0x28}), 2L))) {
                        assertThrows(RuntimeFault.class, () -> call(guest, "trace", 0L, 0L, invalid.getKey(), invalid.getValue()));
                        released(language);
                    }
                    assertThrows(RuntimeFault.class, () -> Calls.target(guest.entryTarget("trace"),
                        new Object[]{0L, 0L, 0L, pointer, (long) bytes.length, 7L}));
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(guest.entryTarget("trace"),
                        new Object[]{0L, 0, 0L, pointer, (long) bytes.length, 7L}));
                    assertEquals(0, output.size(), "Malformed payloads and State cannot publish partial records");
                    released(language);
                } finally { context.leave(); }
            }
        }
    }

    @Test void traceSinksAndLiveSpanOwnershipAreContextLocalThroughBothLoaders() throws Exception {
        for (var backend : List.of("ast", "bytecode")) {
            var left = new ByteArrayOutputStream(); var right = new ByteArrayOutputStream();
            try (var first = context(left); var second = context(right)) {
                first.initialize("thc"); second.initialize("thc");
                var bytes = "context-local".getBytes(StandardCharsets.UTF_8);
                var pointer = ManagedAddress.fromByteArray(bytes);
                ExecutableProgram owner;
                long token;
                first.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    owner = program(language, backend);
                    assertEquals(0L, call(owner, "control", 500L, 1L));
                    token = call(owner, "trace", 1L, 0L, pointer, (long) bytes.length);
                    assertTrue(token > 0);
                    released(language);
                } finally { first.leave(); }
                second.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, backend);
                    assertEquals(0L, query(guest, 500));
                    assertEquals(RuntimeServiceStatus.DISABLED, call(guest, "trace", 0L, 0L, pointer, (long) bytes.length));
                    assertEquals(0L, call(guest, "control", 500L, 1L));
                    assertThrows(RuntimeFault.class, () -> call(guest, "trace", 2L, token, ManagedAddress.nullAddress(), 0L));
                    assertEquals(0, right.size());
                    released(language);
                } finally { second.leave(); }
                first.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    assertEquals(1L, query(owner, 500));
                    assertEquals(0L, call(owner, "trace", 3L, token, ManagedAddress.nullAddress(), 0L));
                    assertThrows(RuntimeFault.class, () -> call(owner, "trace", 2L, token, ManagedAddress.nullAddress(), 0L));
                    assertEquals(List.of("begin", "exception"), records(left).stream().map(record -> record.get("phase")).toList());
                    released(language);
                } finally { first.leave(); }
            }
        }
    }

    @Test void jitTelemetryIsExplicitlyEnabledAndControlsDoNotFabricateDisabledCounters() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var guest = program(language, backend);
                assertEquals(0L, query(guest, 400));
                for (long selector = 401; selector <= 406; selector++) assertEquals(RuntimeServiceStatus.DISABLED, query(guest, selector));
                assertEquals(0L, call(guest, "control", 400L, 1L));
                assertEquals(1L, query(guest, 400));
                for (long selector = 401; selector <= 406; selector++) assertTrue(query(guest, selector) >= 0);
                var target = guest.entryTarget("query");
                compile(target);
                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                assertEquals(1L, query(guest, 0));
                assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue());
                valid(target);
                long completed = query(guest, 402);
                assertTrue(completed >= 1, "The installed guest compilation is attributed to its own context");
                assertEquals(0L, call(guest, "control", 400L, 0L));
                assertEquals(0L, query(guest, 400));
                for (long selector = 401; selector <= 406; selector++) assertEquals(RuntimeServiceStatus.DISABLED, query(guest, selector));
                assertEquals(0L, call(guest, "control", 400L, 1L));
                assertEquals(completed, query(guest, 402), "Disabling observation does not silently reset its history");
                assertThrows(RuntimeFault.class, () -> call(guest, "control", 400L, 2L));
                assertThrows(RuntimeFault.class, () -> query(guest, 400, 1, 0));
                released(language);
            } finally { context.leave(); }
        }
    }

    private static Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        var result = new LinkedHashMap<>(original);
        result.put(key, value);
        return result;
    }

    @Test void foreignDeclarationsRequireExactShapeWidthsConventionSafetyAndUnresolvedHead() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : arguments().entrySet()) {
                    var name = entry.getKey();
                    var parameters = entry.getValue();
                    var valid = descriptor(name);
                    var declared = (List<Map<String, Object>>) valid.get("argumentReps");
                    var badArguments = new ArrayList<Map<String, Object>>();
                    badArguments.add(integer); badArguments.addAll(declared.subList(1, declared.size()));
                    var badDeclarations = List.of(changed(valid, "schema", 2), changed(valid, "safety", "safe"), changed(valid, "convention", "prim"),
                        changed(valid, "arity", parameters.size() + 1), changed(valid, "suppliedArity", parameters.size() - 1),
                        changed(valid, "argumentReps", badArguments), changed(valid, "resultRep", tuple(state, integer)),
                        changed(valid, "resultRep", cLong), changed(valid, "target", changed((Map<String, Object>) valid.get("target"), "isFunction", false)));
                    for (int index = 0; index < badDeclarations.size(); index++) {
                        var bad = badDeclarations.get(index);
                        var source = module(List.of(binding(name, parameters, unpack(foreign(name, bad), "next", "answer"))));
                        assertThrows(RuntimeFault.class, () -> program(language, backend, source), backend + "/" + name + " declaration " + index);
                    }
                    var call = foreign(name);
                    var operation = CoreRuntimeServices.validate(call, false);
                    assertNotNull(operation);
                    assertThrows(RuntimeFault.class, () -> CoreRuntimeServices.validate(call, true));
                    var badFlags = new ArrayList<>(call);
                    badFlags.set(3, Collections.nCopies(parameters.size(), true));
                    var badOperands = new ArrayList<>(call);
                    var operands = (List<?>) call.get(2);
                    badOperands.set(2, operands.subList(0, operands.size() - 1));
                    var badResult = new ArrayList<>(call);
                    badResult.set(6, changed((Map<String, Object>) call.get(6), "rep", tuple(state, cInt)));
                    for (var bad : List.of(badFlags, badOperands, badResult)) {
                        var source = module(List.of(binding(name, parameters, unpack(bad, "next", "answer"))));
                        assertThrows(RuntimeFault.class, () -> program(language, backend, source), backend + "/" + name + " call shape");
                    }
                    // A valid annotation on the occurrence cannot overrule its differently typed binder.
                    for (int index = 0; index < parameters.size(); index++) {
                        var changed = new ArrayList<>(parameters);
                        changed.set(index, new Parameter(parameters.get(index).name(), integer));
                        var source = module(List.of(binding(name, changed, unpack(call, "next", "answer"))));
                        assertThrows(RuntimeFault.class, () -> program(language, backend, source), backend + "/" + name + " contradictory binder " + index);
                    }
                    var shadowed = new ArrayList<>(parameters);
                    shadowed.add(new Parameter("foreign-" + name, closure));
                    var source = module(List.of(binding(name, shadowed, unpack(call, "next", "answer"))));
                    assertThrows(RuntimeFault.class, () -> program(language, backend, source), backend + "/" + name + " defined foreign identifier");
                    released(language);
                }
            } finally { context.leave(); }
        }
    }
}
