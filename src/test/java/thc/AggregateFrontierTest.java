// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Genuine aggregate exports retain logical host shapes and strict malformed-proof rejection. */
class AggregateFrontierTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> constructors = list("tupleOutstanding", "tupleZeroLazy", "sumPayload", "sumZeroLazy", "coldTuple", "coldSum");
    private final Map<String, String> boundaries = Map.of("emptyIdentity", "unboxed-tuple", "emptyDiscard", "unboxed-tuple",
        "singletonIdentity", "unboxed-tuple", "pairIdentity", "unboxed-tuple", "sumIdentity", "unboxed-sum");
    private Map<String, Object> exported(String stage) throws Exception {
        return object(Json.parse(Files.readString(root.resolve("build/" + stage + "/AggregateFrontier.json"))));
    }
    private String request(Map<String, Object> module, String entry, String backend) {
        return Json.stringify(map("modules", list(module), "entry", entry, "backend", backend, "diagnosticUnsupported", false));
    }
    @Test void strictLoadingAcceptsExactConstructorFreeHostShapes() throws Exception {
        for (String stage : list("aggregate-core", "aggregate-post-core")) {
            var module = exported(stage);
            for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowHostAccess(HostAccess.ALL).build()) {
                var empty = context.eval("thc", request(module, "emptyIdentity", backend));
                assertEquals(0, empty.execute((Object) new Object[0]).getArraySize());
                var discard = context.eval("thc", request(module, "emptyDiscard", backend));
                assertEquals(41L, discard.execute((Object) new Object[0]).asLong());
                var singleton = context.eval("thc", request(module, "singletonIdentity", backend));
                assertEquals(Long.MIN_VALUE, singleton.execute((Object) new Object[]{Long.MIN_VALUE}).getArrayElement(0).asLong());
                var pair = context.eval("thc", request(module, "pairIdentity", backend));
                var pairResult = pair.execute((Object) new Object[]{Long.MIN_VALUE, Long.MAX_VALUE});
                assertEquals(2, pairResult.getArraySize());
                assertEquals(Long.MIN_VALUE, pairResult.getArrayElement(0).asLong());
                assertEquals(Long.MAX_VALUE, pairResult.getArrayElement(1).asLong());
                var sum = context.eval("thc", request(module, "sumIdentity", backend));
                var left = sum.execute((Object) new Object[]{1L, new Object[0]});
                assertEquals(2, left.getArraySize()); assertEquals(1L, left.getArrayElement(0).asLong());
                assertEquals(0, left.getArrayElement(1).getArraySize());
                var producer = context.eval("thc", request(module, "zeroSumProducer", backend));
                var original = producer.execute(1L); // The selected payload is the unforced bottomBox.
                var right = sum.execute(original);
                assertEquals(2L, right.getArrayElement(0).asLong());
                assertEquals(original.getArrayElement(1), right.getArrayElement(1));
                assertThrows(PolyglotException.class, () -> sum.execute((Object) new Object[]{3L, new Object[0]}));
                assertThrows(PolyglotException.class, () -> sum.execute((Object) new Object[]{2L, 0L}));
                assertThrows(PolyglotException.class, () -> pair.execute((Object) new Object[]{1L}));
                var identity = context.eval("thc", request(module, "abstractIdentity", backend));
                var boxedProducer = context.eval("thc", request(module, "sumProducer", backend));
                var pointer = boxedProducer.execute(1L).getArrayElement(1).getArrayElement(1);
                assertEquals(pointer, identity.execute(pointer));
                assertThrows(PolyglotException.class, () -> identity.execute(1234L));
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
    @Test void diagnosticModeStillValidatesHostShapesIncludingUnusedFormals() throws Exception {
        var module = exported("aggregate-core");
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            for (var boundary : boundaries.entrySet()) {
                var function = context.eval("thc", Json.stringify(map("modules", list(module), "entry", boundary.getKey(), "backend", backend, "diagnosticUnsupported", true)));
                var error = assertThrows(PolyglotException.class, () -> function.execute(0L));
                assertTrue(Objects.toString(error.getMessage(), "").contains("Host ABI requires an array of"), backend + "/" + boundary.getKey() + ": " + error.getMessage());
            }
        }
    }
    @Test void supportedScalarObserversMatchTheNativeOracle() throws Exception {
        var rows = Files.readAllLines(root.resolve("build/aggregate-native/oracle.tsv"));
        assertEquals(48, rows.size());
        for (String stage : list("aggregate-core", "aggregate-post-core")) {
            var module = exported(stage);
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var entries = new HashMap<String, Value>();
                for (String name : constructors) entries.put(name, context.eval("thc", request(module, name, backend)));
                for (String row : rows) {
                    var fields = row.split("\t");
                    assertEquals(Long.parseLong(fields[2]), entries.get(fields[0]).execute(Long.parseLong(fields[1])).asLong(),
                        stage + "/" + backend + "/" + row);
                }
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
