// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic load-time contracts only: no MVar operation is executed here. */
@SuppressWarnings("unchecked")
class ManagedMVarLoadingTest {
    private final String liftedBox = "BoxedRep (Just Lifted)";
    private final String unliftedBox = "BoxedRep (Just Unlifted)";
    private Map<String, Object> leaf(String kind, String... registers) {
        return Map.of("kind", kind, "primReps", List.of(registers), "evaluated", true);
    }
    private final Map<String, Object> state = leaf("void");
    private final Map<String, Object> integer = leaf("long", "IntRep");
    private final Map<String, Object> closure = leaf("closure", liftedBox);
    private Map<String, Object> tuple(List<Map<String, Object>> fields) {
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", fields,
            "primReps", fields.stream().flatMap(field -> ((List<?>) field.get("primReps")).stream()).toList(), "evaluated", true);
    }
    private Map<String, Object> role(String role, boolean lifted, String kind) {
        return switch (role) {
            case "state" -> state;
            case "mvar" -> leaf("object", unliftedBox);
            case "flag" -> integer;
            default -> leaf(kind, lifted ? liftedBox : unliftedBox);
        };
    }
    private record Contract(String name, List<String> arguments, List<String> results) {}
    // Independent of MVarOp's private role tables, including logical zero-width State#.
    private final List<Contract> contracts = List.of(
        new Contract("newMVar#", List.of("state"), List.of("state", "mvar")),
        new Contract("takeMVar#", List.of("mvar", "state"), List.of("state", "boxed")),
        new Contract("putMVar#", List.of("mvar", "boxed", "state"), List.of("state")),
        new Contract("readMVar#", List.of("mvar", "state"), List.of("state", "boxed")),
        new Contract("tryTakeMVar#", List.of("mvar", "state"), List.of("state", "flag", "boxed")),
        new Contract("tryPutMVar#", List.of("mvar", "boxed", "state"), List.of("state", "flag")),
        new Contract("tryReadMVar#", List.of("mvar", "state"), List.of("state", "flag", "boxed")),
        new Contract("isEmptyMVar#", List.of("mvar", "state"), List.of("state", "flag")));
    private static Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    /** Declare the lazy RTS dependency without executing any MVar operation. */
    private Map<String, Object> blockedMVarDependency() {
        var proof = Map.<String, Object>of("kind", "data", "primReps", List.of(liftedBox), "evaluated", false);
        return Map.of("id", CoreBlockedExceptions.MVAR, "name", CoreBlockedExceptions.MVAR,
            "type", "SomeException", "lifted", true, "arity", 0, "rep", proof,
            "expr", variable(CoreBlockedExceptions.MVAR, proof));
    }
    private final class Fixture {
        final Contract contract;
        final List<Map<String, Object>> parameters = new ArrayList<>();
        final List<List<Object>> arguments = new ArrayList<>();
        final List<Object> flags = new ArrayList<>();
        final List<Map<String, Object>> resultFields;
        final Map<String, Object> result;
        final Map<String, Object> metadata;
        final List<Object> application;
        final Map<String, Object> binding;
        final List<Map<String, Object>> bindings;
        final Map<String, Object> module;
        Fixture(Contract contract) { this(contract, true, "object"); }
        Fixture(Contract contract, boolean lifted) { this(contract, lifted, "object"); }
        Fixture(Contract contract, boolean lifted, String kind) {
            this.contract = contract;
            for (int index = 0; index < contract.arguments.size(); index++) {
                String role = contract.arguments.get(index);
                parameters.add(new LinkedHashMap<>(Map.of("id", "x" + index, "lifted", role.equals("boxed") && lifted,
                    "rep", role(role, lifted, kind))));
            }
            for (var parameter : parameters) {
                arguments.add(variable((String) parameter.get("id"), (Map<String, Object>) parameter.get("rep")));
                flags.add(parameter.get("lifted"));
            }
            resultFields = contract.results.stream().map(value -> role(value, lifted, kind)).toList();
            result = contract.name.equals("putMVar#") ? state : tuple(resultFields);
            metadata = new LinkedHashMap<>(Map.of("rep", result));
            application = List.of("app", List.of("prim", contract.name), arguments, flags, false, false, metadata);
            binding = new LinkedHashMap<>(Map.of("id", "root", "name", "root", "lifted", true,
                "arity", parameters.size(), "rep", closure,
                "expr", List.of("lam", parameters, application, Map.of("rep", closure, "resultRep", result))));
            bindings = new ArrayList<>(List.of(binding, blockedMVarDependency()));
            module = Map.of("bindings", bindings, "constructors", List.of(Map.of(
                "id", "Carrier", "name", "Carrier", "arity", 0, "kind", "boxed",
                "strictFields", List.of(), "fieldLifted", List.of(), "fieldTypes", List.of(), "fieldReps", List.of())));
        }
        void stored(int index, Map<String, Object> proof, boolean global) {
            var selected = parameters.stream().filter(parameter -> parameter.get("id").equals("x" + index)).toList();
            if (selected.size() != 1) throw new IllegalArgumentException("Expected one parameter");
            var parameter = selected.getFirst();
            if (!global) parameter.put("rep", proof);
            else {
                parameters.remove(parameter);
                // Keep an ordinary lambda even when newMVar#'s sole operand becomes global.
                if (parameters.isEmpty()) parameters.add(new LinkedHashMap<>(Map.of("id", "unused", "lifted", false, "rep", integer)));
                binding.put("arity", parameters.size());
                bindings.add(global("x" + index, proof));
            }
        }
    }
    private List<Object> variable(String id, Map<String, Object> proof) { return List.of("var", id, Map.of("rep", proof)); }
    private Map<String, Object> global(String id, Map<String, Object> proof) {
        var registers = (List<?>) proof.get("primReps");
        // Initializers are inert scalar/constructor/closure values, never MVar calls.
        // An object proof does not assert a particular runtime reference class.
        List<?> expression;
        if (registers.isEmpty()) expression = List.of("void", Map.of("rep", state));
        else if (registers.equals(List.of("IntRep"))) expression = List.of("lit", "int", "0", Map.of("rep", integer));
        else if (proof.get("kind").equals("closure")) expression = List.of("lam",
            List.of(Map.of("id", "ignored", "lifted", false, "rep", state)), List.of("void", Map.of("rep", state)),
            Map.of("rep", proof, "resultRep", state));
        else expression = List.of("con", "Carrier", 0, Map.of("rep", changed(proof, "kind", "data")));
        return Map.of("id", id, "name", id,
            "lifted", registers.size() == 1 && Set.of(liftedBox, "BoxedRep Nothing").contains(registers.getFirst()),
            "rep", proof, "expr", expression);
    }
    private void forBackends(BiConsumer<Language, String> action) {
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend); }
            finally { context.leave(); }
        }
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void accepts(Language language, String backend, Fixture fixture, String label) {
        assertDoesNotThrow(() -> load(language, backend, fixture.module), backend + "/" + label);
    }
    private void rejects(Language language, String backend, Fixture fixture, String label, String message, boolean diagnostic) {
        var failure = assertThrows(RuntimeFault.class,
            () -> load(language, backend, changed(fixture.module, "diagnosticUnsupported", diagnostic)),
            backend + "/" + label + "/diagnostic=" + diagnostic);
        if (message != null) assertTrue(Objects.toString(failure.getMessage(), "").contains(message),
            backend + "/" + label + ": expected '" + message + "', got '" + failure.getMessage() + "'");
    }

    @Test void everyPrimitiveLoadsWithBothPayloadLevitiesAndAllBoxedKinds() {
        forBackends((language, backend) -> {
            for (var contract : contracts) for (boolean lifted : new boolean[]{false, true}) for (String kind : List.of("data", "closure", "object"))
                accepts(language, backend, new Fixture(contract, lifted, kind), contract.name + "/" + lifted + "/" + kind);
        });
    }
    @Test void localAndGlobalDataOrClosureCannotBeRelabelledAsMVarObjects() {
        forBackends((language, backend) -> {
            for (var contract : contracts) if (contract.arguments.contains("mvar")) for (String kind : List.of("data", "closure"))
                for (boolean global : new boolean[]{false, true}) {
                    var fixture = new Fixture(contract);
                    fixture.stored(contract.arguments.indexOf("mvar"), leaf(kind, unliftedBox), global);
                    rejects(language, backend, fixture, contract.name + "/" + kind + "/global=" + global,
                        "MVar argument contradicts its binding proof", false);
                }
        });
    }
    @Test void objectPayloadOccurrencesMayRetainStoredDataOrClosureKinds() {
        forBackends((language, backend) -> {
            for (var contract : contracts) if (contract.arguments.contains("boxed")) for (String kind : List.of("data", "closure"))
                for (boolean lifted : new boolean[]{false, true}) for (boolean global : new boolean[]{false, true}) {
                    var fixture = new Fixture(contract, lifted);
                    fixture.stored(contract.arguments.indexOf("boxed"), leaf(kind, lifted ? liftedBox : unliftedBox), global);
                    accepts(language, backend, fixture, contract.name + "/" + kind + "/" + lifted + "/global=" + global);
                }
        });
    }
    @Test void unknownKindDoesNotEraseConcreteScalarRegistersAtAnyOperand() {
        forBackends((language, backend) -> {
            for (var contract : contracts) for (int index = 0; index < contract.arguments.size(); index++) for (boolean global : new boolean[]{false, true}) {
                var fixture = new Fixture(contract);
                fixture.stored(index, leaf("unknown", "IntRep"), global);
                rejects(language, backend, fixture, contract.name + "/operand" + index + "/global=" + global,
                    "MVar argument contradicts its binding proof", false);
            }
        });
    }
    @Test void unknownBoxedLevityCannotRefineToZeroWidthState() {
        forBackends((language, backend) -> {
            for (String kind : List.of("unknown", "object", "data", "closure")) for (boolean global : new boolean[]{false, true}) {
                var fixture = new Fixture(contracts.getFirst());
                fixture.stored(0, leaf(kind, "BoxedRep Nothing"), global);
                // Concrete local boxed kinds conflict during expression proof refinement;
                // globals and unknown kinds reach the primitive's zero-width State# validation.
                String reason = global || kind.equals("unknown") ? "MVar argument contradicts its binding proof" :
                    "Conflicting Core representation proofs: " + kind.toUpperCase(Locale.ROOT) + " and VOID";
                rejects(language, backend, fixture, kind + "/global=" + global, reason, false);
            }
        });
    }
    @Test void unknownBoxedLevityMayRefineToExactBoxedPayloads() {
        forBackends((language, backend) -> {
            for (var contract : contracts) if (contract.arguments.contains("boxed")) for (String kind : List.of("unknown", "object", "data", "closure"))
                for (boolean lifted : new boolean[]{false, true}) for (boolean global : new boolean[]{false, true}) {
                    var fixture = new Fixture(contract, lifted);
                    fixture.stored(contract.arguments.indexOf("boxed"), leaf(kind, "BoxedRep Nothing"), global);
                    accepts(language, backend, fixture, contract.name + "/" + kind + "/" + lifted + "/global=" + global);
                }
        });
    }
    @Test void lexicalLocalsShadowGlobalBindingProofsInBothDirections() {
        forBackends((language, backend) -> {
            var contract = contracts.stream().filter(value -> value.name.equals("isEmptyMVar#")).findFirst().orElseThrow();
            var positive = new Fixture(contract);
            positive.bindings.add(global("x0", integer));
            accepts(language, backend, positive, "local MVar shadows global IntRep");
            var negative = new Fixture(contract);
            negative.stored(0, leaf("data", unliftedBox), false);
            negative.bindings.add(global("x0", leaf("object", unliftedBox)));
            rejects(language, backend, negative, "local data shadows global object", "MVar argument contradicts its binding proof", false);
            var stateShadow = new Fixture(contracts.getFirst());
            stateShadow.bindings.add(global("x0", integer));
            accepts(language, backend, stateShadow, "local State shadows global IntRep");
        });
    }
    @Test void loweredCompositeOperandsAreRevalidatedAfterOccurrenceChecks() {
        forBackends((language, backend) -> {
            for (String kind : List.of("data", "closure")) {
                var fixture = new Fixture(contracts.stream().filter(value -> value.name.equals("isEmptyMVar#")).findFirst().orElseThrow());
                var stored = leaf(kind, unliftedBox);
                fixture.stored(0, stored, false);
                // A non-var operand bypasses validateBindings; lowering retains its
                // more precise data/closure kind despite the outer object occurrence.
                fixture.arguments.set(0, List.of("let", false, List.of(), variable("x0", stored), Map.of("rep", leaf("object", unliftedBox))));
                rejects(language, backend, fixture, kind, "MVar primitive argument representation mismatch", false);
            }
        });
    }
    @Test void argumentRolesFlagsAndLogicalArityRemainExactInBothLoadModes() {
        forBackends((language, backend) -> {
            for (var contract : contracts) for (boolean diagnostic : new boolean[]{false, true}) {
                for (int index = 0; index < contract.arguments.size(); index++) {
                    for (var badFlag : Arrays.asList(null, 0L, "false", !contract.arguments.get(index).equals("boxed"))) {
                        var fixture = new Fixture(contract); fixture.flags.set(index, badFlag);
                        rejects(language, backend, fixture, contract.name + "/flag" + index + "=" + badFlag, null, diagnostic);
                    }
                    for (var bad : List.of(leaf("unknown"), leaf("object", "BoxedRep Nothing"), leaf("address", "AddrRep"), integer, tuple(List.of()))) {
                        var fixture = new Fixture(contract); fixture.arguments.set(index, variable("x" + index, bad));
                        rejects(language, backend, fixture, contract.name + "/operand" + index + "=" + bad, null, diagnostic);
                    }
                }
                for (int mutation = 0; mutation <= 3; mutation++) {
                    var fixture = new Fixture(contract);
                    switch (mutation) {
                        case 0 -> { fixture.arguments.removeLast(); fixture.flags.removeLast(); }
                        case 1 -> { fixture.arguments.add(fixture.arguments.getFirst()); fixture.flags.add(false); }
                        case 2 -> fixture.flags.removeLast();
                        default -> fixture.flags.add(false);
                    }
                    rejects(language, backend, fixture, contract.name + "/arity" + mutation, null, diagnostic);
                }
            }
        });
    }
    @Test void resultsRetainLogicalStateExactFlagsAndFlattenedTupleProofs() {
        forBackends((language, backend) -> {
            for (var contract : contracts) for (boolean diagnostic : new boolean[]{false, true}) {
                var template = new Fixture(contract);
                var malformed = new ArrayList<Map<String, Object>>(Arrays.asList(null, integer, tuple(List.of())));
                if (!contract.name.equals("putMVar#")) {
                    var rest = template.resultFields.subList(1, template.resultFields.size());
                    malformed.add(tuple(rest)); // State# cannot disappear from the logical tuple.
                    var nested = new ArrayList<>(List.of(tuple(List.of()))); nested.addAll(rest);
                    malformed.add(tuple(nested));
                    malformed.add(changed(template.result, "primReps", List.of()));
                    // A generic unsupported tuple leaf is deliberately deferred by
                    // diagnostic mode; strict mode must still reject it at load time.
                    if (!diagnostic) {
                        var unknown = new ArrayList<>(List.of(leaf("unknown"))); unknown.addAll(rest);
                        malformed.add(tuple(unknown));
                    }
                    for (int index = 0; index < contract.results.size(); index++) {
                        var fields = new ArrayList<>(template.resultFields);
                        fields.set(index, switch (contract.results.get(index)) {
                            case "state" -> leaf("object", unliftedBox);
                            case "flag" -> leaf("long", "WordRep");
                            case "mvar" -> leaf("object", liftedBox);
                            default -> integer;
                        });
                        malformed.add(tuple(fields));
                    }
                }
                for (int index = 0; index < malformed.size(); index++) {
                    var fixture = new Fixture(contract); fixture.metadata.put("rep", malformed.get(index));
                    rejects(language, backend, fixture, contract.name + "/result" + index, null, diagnostic);
                }
            }
        });
    }
}
