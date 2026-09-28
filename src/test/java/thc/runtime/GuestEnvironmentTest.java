// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GuestEnvironmentTest {
    private static Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true)
            .allowEnvironmentAccess(EnvironmentAccess.NONE).environment("THC_INITIAL", "lambda-\u03bb")
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }

    @FunctionalInterface private interface Body { void run(Language language) throws Exception; }
    private static void inside(Body body) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") &&
            Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try { body.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }

    private static ManagedAddress string(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var address = Language.currentState(null).getNativeAllocations().malloc((long) bytes.length + 1);
        for (int index = 0; index < bytes.length; index++) address.writeWord8(index, bytes[index]);
        address.writeWord8(bytes.length, 0);
        return address;
    }

    private static String text(ManagedAddress address) {
        var bytes = new byte[(int) address.cStringLength()];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) address.readWord8(index);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> entries(ManagedAddress vector) {
        var result = new ArrayList<String>();
        long index = 0;
        while (true) {
            var address = vector.readAddressElementIndex(index++);
            if (address == ManagedAddress.nullAddress()) break;
            result.add(text(address));
        }
        return result;
    }

    private static Map<String, Object> scalar(String primitive) { return scalar(primitive, true); }
    private static Map<String, Object> scalar(String primitive, boolean evaluated) {
        String kind = primitive == null ? "void" : switch (primitive) {
            case "AddrRep" -> "address";
            case "BoxedRep (Just Lifted)" -> "closure";
            default -> "long";
        };
        return Map.of("kind", kind, "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", evaluated);
    }

    private final Map<String, Object> closure = scalar("BoxedRep (Just Lifted)");
    private static Map<String, Object> tuple(EnvironmentOp operation) { return tuple(operation, false); }
    private static Map<String, Object> tuple(EnvironmentOp operation, boolean evaluated) {
        return Map.of("kind", "unknown", "primReps", List.of(operation.getResult()), "evaluated", evaluated,
            "aggregate", "unboxed-tuple", "components", List.of(scalar(null), scalar(operation.getResult())));
    }

    private static Map<String, Object> declaration(EnvironmentOp operation) {
        return Map.of("schema", 1, "target", Map.of("kind", "static", "symbol", operation.getSymbol(),
            "unit", operation == EnvironmentOp.SET || operation == EnvironmentOp.CLEAR ? "unix-2.8.8.0-inplace" : "ghc-internal", "isFunction", true), "convention", "ccall", "safety", "unsafe",
            "arity", operation.getArguments().size(), "suppliedArity", operation.getArguments().size(),
            "argumentReps", operation.getArguments().stream().map(rep -> scalar(rep, false)).toList(), "resultRep", tuple(operation));
    }

    private Map<String, Object> module() {
        // Explicitly synthetic consumers. EnvironmentFullCore exercises genuine
        // original installed System.Environment definitions independently.
        var bindings = new ArrayList<Map<String, Object>>();
        for (var operation : EnvironmentOp.values()) {
            var name = operation.getSymbol();
            var formals = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < operation.getArguments().size(); index++)
                formals.add(Map.of("id", name + "-" + index, "lifted", false, "rep", scalar(operation.getArguments().get(index))));
            var call = List.of("app", List.of("var", name + "-synthetic-fcall", Map.of("rep", closure)),
                formals.stream().map(formal -> List.of("var", formal.get("id"), Map.of("rep", formal.get("rep")))).toList(),
                formals.stream().map(formal -> false).toList(), false, false,
                Map.of("rep", tuple(operation), "foreignCall", declaration(operation)));
            var fields = List.of(scalar(null), scalar(operation.getResult()));
            var ids = List.of(name + "-state", name + "-result");
            var binders = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < fields.size(); index++)
                binders.add(Map.of("id", ids.get(index), "lifted", false, "rep", fields.get(index)));
            var body = List.of("case", call, name + "-tuple", List.of(List.of("data", "tuple2", ids,
                List.of("var", ids.get(1), Map.of("rep", fields.get(1))), Map.of("binders", binders))),
                Map.of("rep", fields.get(1), "binder", Map.of("id", name + "-tuple", "lifted", false,
                    "rep", tuple(operation, true))));
            bindings.add(Map.of("id", name, "name", name, "arity", formals.size(), "lifted", true, "rep", closure,
                "expr", List.of("lam", formals, body, Map.of("rep", closure, "resultRep", fields.get(1)))));
        }
        return Map.of("bindings", bindings, "instrument", true, "constructors", List.of(
            Map.of("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }

    @Test void firstInstalledCallsUseBothTypedBackendsAndReleaseHandoffs() throws Exception {
        for (var backend : List.of("ast", "bytecode")) inside(language -> {
            ExecutableProgram program = backend.equals("ast") ? new Program(language, module(), false, false)
                : new BytecodeProgram(language, module());
            var targets = new LinkedHashMap<EnvironmentOp, com.oracle.truffle.api.RootCallTarget>();
            for (var operation : EnvironmentOp.values()) targets.put(operation, program.entryTarget(operation.getSymbol()));
            var name = string("THC_LOCAL");
            var entry = string("THC_LOCAL=first");
            var environment = Language.currentState(null).getEnvironment();
            environment.environ(); // Materialize host-authorized initial state before compiling.
            class Caller {
                boolean compiled;
                Object call(EnvironmentOp operation, Object... arguments) throws Exception {
                    var target = targets.get(operation);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    var inputs = new Object[arguments.length + 2];
                    inputs[0] = 0L;
                    System.arraycopy(arguments, 0, inputs, 1, arguments.length);
                    inputs[inputs.length - 1] = Unit.INSTANCE;
                    var result = ScalarTestCalls.callScalarTestTarget(target, inputs);
                    assertEquals(before + (compiled ? 1 : 0),
                        ((Number) program.diagnostics().get("compiledEntries")).longValue(), backend);
                    if (compiled) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                    assertNull(handoff.getPending());
                    return result;
                }
                void semantics() throws Exception {
                    assertEquals(0, call(EnvironmentOp.PUT, entry));
                    assertEquals("first", text((ManagedAddress) call(EnvironmentOp.GET, name)));
                    assertEquals(Set.of("THC_INITIAL=lambda-\u03bb", "THC_LOCAL=first"),
                        new HashSet<>(entries((ManagedAddress) call(EnvironmentOp.ENUMERATE))));
                    assertEquals(0, call(EnvironmentOp.UNSET, name));
                    assertSame(ManagedAddress.nullAddress(), call(EnvironmentOp.GET, name));
                    var value = string("copied");
                    assertEquals(0, call(EnvironmentOp.SET, name, value, 1));
                    value.writeWord8(0, 'X');
                    assertEquals("copied", text((ManagedAddress) call(EnvironmentOp.GET, name)));
                    assertEquals(0, call(EnvironmentOp.SET, name, string("ignored"), 0));
                    assertEquals("copied", text((ManagedAddress) call(EnvironmentOp.GET, name)));
                    assertEquals(0L, call(EnvironmentOp.CLEAR));
                    assertEquals(List.of(), entries((ManagedAddress) call(EnvironmentOp.ENUMERATE)));
                    environment.set(string("THC_INITIAL"), string("lambda-\u03bb"), 1);
                }
            }
            var caller = new Caller();
            // Check the entire semantic corpus in the interpreter, then install
            // once. There is no guest call between compilation and the first
            // checked installed entry, and no settling/retry after compilation.
            caller.semantics();
            for (var target : targets.values()) {
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
            }
            caller.compiled = true;
            caller.semantics();
        });
    }

    @Test void putenvRetainsCallerBytesAndEnumerationHasNativePointerIdentity() throws Exception {
        inside(language -> {
            var environment = Language.currentState(null).getEnvironment();
            var name = string("LOCAL");
            var entry = string("LOCAL=abc");
            assertEquals(0L, environment.put(entry));
            var firstVector = environment.environ();
            assertEquals(entry.toNativeBits() + 6, environment.get(name).toNativeBits());
            entry.writeWord8(6, 'X');
            assertEquals("Xbc", text(environment.get(name)));
            assertTrue(entries(firstVector).contains("LOCAL=Xbc"));
            assertEquals(0L, environment.put(string("LOCAL=")));
            assertEquals("", text(environment.get(name)));
            assertThrows(RuntimeFault.class, () -> firstVector.readAddressElementIndex(0));
            assertEquals(0L, environment.put(name)); // Linux putenv without '=' removes.
            assertSame(ManagedAddress.nullAddress(), environment.get(name));
            assertEquals(-1L, environment.unset(string("invalid=name")));
            assertEquals(-1L, environment.unset(string("")));
            assertEquals(0L, environment.unset(name));
            assertSame(ManagedAddress.nullAddress(), environment.get(string("")));
            var unterminated = Language.currentState(null).getNativeAllocations().malloc(1);
            unterminated.writeWord8(0, 65);
            assertThrows(RuntimeFault.class, () -> environment.put(unterminated));
            assertEquals(List.of("THC_INITIAL=lambda-\u03bb"), entries(environment.environ()));
        });
    }

    @Test void contextIsolationEnvironmentPolicyAndDisposalArePreserved() throws Exception {
        inside(language -> {
            var outer = Language.currentState(null).getEnvironment();
            var name = string("THC_LOCAL");
            outer.put(string("THC_LOCAL=outer"));
            ManagedAddress escaped;
            try (var inner = context()) {
                inner.initialize("thc"); inner.enter();
                try {
                    assertThrows(RuntimeFault.class, outer::environ);
                    var current = Language.currentState(null).getEnvironment();
                    assertSame(ManagedAddress.nullAddress(), current.get(string("THC_LOCAL")));
                    assertEquals(List.of("THC_INITIAL=lambda-\u03bb"), entries(current.environ()));
                    escaped = current.get(string("THC_INITIAL"));
                } finally { inner.leave(); }
            }
            assertThrows(RuntimeFault.class, () -> escaped.readWord8(0));
            assertEquals("outer", text(outer.get(name)));
            assertNotEquals("outer", System.getenv("THC_LOCAL"));
        });
    }

    @Test void foreignAdmissionRejectsWrongOwnerAbiAndTuple() {
        for (var operation : EnvironmentOp.values()) {
            var original = declaration(operation);
            assertEquals(operation, CoreEnvironmentForeign.validate(Map.of("foreignCall", original, "rep", tuple(operation)),
                operation.getArguments().stream().map(GuestEnvironmentTest::scalar).toList(),
                operation.getArguments().stream().map(rep -> false).toList(), tuple(operation)));
            for (var change : List.of(Map.entry("safety", (Object) "safe"), Map.entry("convention", (Object) "capi"),
                    Map.entry("arity", (Object) 0), Map.entry("resultRep", (Object) scalar(operation.getResult())),
                    Map.entry("argumentReps", (Object) List.of()), Map.entry("target", (Object) Map.of("kind", "static",
                        "symbol", operation.getSymbol(), "unit", "other", "isFunction", true)))) {
                var call = new LinkedHashMap<>(original);
                call.put(change.getKey(), change.getValue());
                assertThrows(RuntimeFault.class, () -> CoreEnvironmentForeign.validate(Map.of("foreignCall", call, "rep", tuple(operation)),
                    operation.getArguments().stream().map(GuestEnvironmentTest::scalar).toList(),
                    operation.getArguments().stream().map(rep -> false).toList(), tuple(operation)));
            }
        }
    }
}
