// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Supported aggregate guest results execute; public host aggregate boundaries still reject. */
class AggregateFrontierTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> constructors = list("tupleOutstanding", "tupleZeroLazy", "sumPayload", "sumZeroLazy", "coldTuple", "coldSum");
    private final Set<String> supported = new HashSet<>(constructors);
    private final Map<String, String> boundaries = Map.of("emptyIdentity", "unboxed-tuple", "emptyDiscard", "unboxed-tuple",
        "singletonIdentity", "unboxed-tuple", "pairIdentity", "unboxed-tuple", "sumIdentity", "unboxed-sum");
    private Map<String, Object> exported(String stage) throws Exception {
        return object(Json.parse(Files.readString(root.resolve("build/" + stage + "/AggregateFrontier.json"))));
    }
    private String request(Map<String, Object> module, String entry, String backend) {
        return Json.stringify(map("modules", list(module), "entry", entry, "backend", backend, "diagnosticUnsupported", false));
    }
    @Test void strictLoadingRejectsOptimizedAggregatesBeforeAnyInputRuns() throws Exception {
        for (String stage : list("aggregate-core", "aggregate-post-core")) {
            var module = exported(stage);
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var rejected = new ArrayList<>(constructors); rejected.removeAll(supported); rejected.addAll(boundaries.keySet());
                for (String entry : rejected) {
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(module, entry, backend)));
                    String message = Objects.toString(error.getMessage(), "");
                    assertTrue(message.contains("Unsupported Core aggregate representation:"), stage + "/" + backend + "/" + entry + ": " + message);
                    if (boundaries.containsKey(entry)) assertTrue(message.contains(boundaries.get(entry)), stage + "/" + backend + "/" + entry + ": " + message);
                }
                var identity = context.eval("thc", request(module, "abstractIdentity", backend));
                assertEquals(1234L, identity.execute(1234L).asLong());
                context.eval("thc", request(module, "stateIdentity", backend));
            }
        }
    }
    private Object withoutMarkers(Object value) {
        if (value instanceof Map<?, ?> fields) {
            var result = new LinkedHashMap<Object, Object>();
            fields.forEach((key, item) -> { if (!key.equals("aggregate")) result.put(key, withoutMarkers(item)); });
            return result;
        }
        if (value instanceof List<?> values) {
            var result = new ArrayList<>(); for (Object item : values) result.add(withoutMarkers(item)); return result;
        }
        return value;
    }
    @Test void existingConstructorRejectionAlsoCoversColdAndZeroWidthArms() throws Exception {
        var legacy = object(withoutMarkers(exported("aggregate-core")));
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            for (String entry : constructors) {
                var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(legacy, entry, backend)));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported constructor representation unboxed-"), backend + "/" + entry + ": " + error.getMessage());
            }
        }
    }
    private long compiledEntries(Value function) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue(); }
    @Test void diagnosticModeKeepsLegacyColdAggregatePathsLazyAndTrapsWhenReached() throws Exception {
        var module = object(withoutMarkers(exported("aggregate-core")));
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            String entry = "coldSum"; long expected = -7L;
            var function = context.eval("thc", Json.stringify(map("modules", list(module), "entry", entry, "backend", backend, "diagnosticUnsupported", true)));
            for (int i = 0; i < 8; i++) assertEquals(expected, function.execute(0L).asLong());
            assertTrue(function.invokeMember("compile").asBoolean());
            long before = compiledEntries(function);
            assertEquals(expected, function.execute(0L).asLong());
            assertTrue(compiledEntries(function) > before, backend + "/" + entry + ": must enter compiled code before the cold trap");
            var error = assertThrows(PolyglotException.class, () -> function.execute(31337L));
            assertTrue(Objects.toString(error.getMessage(), "").contains("Diagnostic unsupported path reached: Unsupported constructor representation unboxed-sum"), backend + "/" + entry + ": " + error.getMessage());
        }
    }
    @Test void diagnosticModeTrapsConstructorFreeBoundariesIncludingUnusedFormals() throws Exception {
        var module = exported("aggregate-core");
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            for (var boundary : boundaries.entrySet()) {
                var function = context.eval("thc", Json.stringify(map("modules", list(module), "entry", boundary.getKey(), "backend", backend, "diagnosticUnsupported", true)));
                var error = assertThrows(PolyglotException.class, () -> function.execute(0L));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Diagnostic unsupported path reached: Unsupported Core aggregate representation: " + boundary.getValue()), backend + "/" + boundary.getKey() + ": " + error.getMessage());
            }
        }
    }
    @Test void ordinaryUnknownMetadataRemainsOptionalAndDoesNotInferAggregateShape() {
        assertFalse(CoreRepresentations.parse(null).getPresent());
        for (var reps : list(null, list(), list("IntRep"), list("IntRep", "IntRep"), list("BoxedRep (Just Lifted)"), list("FloatRep"))) {
            var proof = map("kind", "unknown", "primReps", reps, "evaluated", true);
            assertEquals(CoreKind.UNKNOWN, CoreRepresentations.parse(proof).getKind());
            for (String aggregate : list("unboxed-tuple", "unboxed-sum")) {
                var error = assertThrows(UnsupportedCore.class, () -> CoreRepresentations.parse(with(proof, "aggregate", aggregate)));
                assertTrue(Objects.toString(error.getMessage(), "").startsWith("Unsupported Core aggregate representation: " + aggregate));
            }
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(proof, "aggregate", "guessed")));
        }
    }
}
