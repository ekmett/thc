// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Genuine aggregate exports retain logical host shapes and strict malformed-proof rejection. */
class AggregateFrontierTest {
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> constructors = list("tupleOutstanding", "tupleZeroLazy", "sumPayload", "sumZeroLazy", "coldTuple", "coldSum");
    private final Map<String, String> boundaries = Map.of("emptyIdentity", "unboxed-tuple", "emptyDiscard", "unboxed-tuple",
        "singletonIdentity", "unboxed-tuple", "pairIdentity", "unboxed-tuple", "sumIdentity", "unboxed-sum");
    private Map<String, Object> exported(String stage) throws Exception {
        return CoreCbdFixtures.read(artifact(stage));
    }
    private Path artifact(String stage) { return root.resolve("build/" + stage + "/AggregateFrontier.cbd"); }
    private String request(Path artifact, String entry, String backend) {
        return CoreModules.request(list(artifact.toString()), "main:AggregateFrontier." + entry, true, false, backend);
    }
    @Test void strictLoadingAcceptsExactConstructorFreeHostShapes() throws Exception {
        for (String stage : list("aggregate-core", "aggregate-post-core")) {
            var module = exported(stage);
            for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowHostAccess(HostAccess.ALL).build()) {
                var empty = context.eval("thc", request(artifact(stage), "emptyIdentity", backend));
                assertEquals(0, empty.execute((Object) new Object[0]).getArraySize());
                var discard = context.eval("thc", request(artifact(stage), "emptyDiscard", backend));
                assertEquals(41L, discard.execute((Object) new Object[0]).asLong());
                var singleton = context.eval("thc", request(artifact(stage), "singletonIdentity", backend));
                assertEquals(Long.MIN_VALUE, singleton.execute((Object) new Object[]{Long.MIN_VALUE}).getArrayElement(0).asLong());
                var pair = context.eval("thc", request(artifact(stage), "pairIdentity", backend));
                var pairResult = pair.execute((Object) new Object[]{Long.MIN_VALUE, Long.MAX_VALUE});
                assertEquals(2, pairResult.getArraySize());
                assertEquals(Long.MIN_VALUE, pairResult.getArrayElement(0).asLong());
                assertEquals(Long.MAX_VALUE, pairResult.getArrayElement(1).asLong());
                var sum = context.eval("thc", request(artifact(stage), "sumIdentity", backend));
                var left = sum.execute((Object) new Object[]{1L, new Object[0]});
                assertEquals(2, left.getArraySize()); assertEquals(1L, left.getArrayElement(0).asLong());
                assertEquals(0, left.getArrayElement(1).getArraySize());
                var producer = context.eval("thc", request(artifact(stage), "zeroSumProducer", backend));
                var original = producer.execute(1L); // The selected payload is the unforced bottomBox.
                var right = sum.execute(original);
                assertEquals(2L, right.getArrayElement(0).asLong());
                assertEquals(original.getArrayElement(1), right.getArrayElement(1));
                assertThrows(PolyglotException.class, () -> sum.execute((Object) new Object[]{3L, new Object[0]}));
                assertThrows(PolyglotException.class, () -> sum.execute((Object) new Object[]{2L, 0L}));
                assertThrows(PolyglotException.class, () -> pair.execute((Object) new Object[]{1L}));
                var identity = context.eval("thc", request(artifact(stage), "abstractIdentity", backend));
                var boxedProducer = context.eval("thc", request(artifact(stage), "sumProducer", backend));
                var pointer = boxedProducer.execute(1L).getArrayElement(1).getArrayElement(1);
                assertEquals(pointer, identity.execute(pointer));
                assertThrows(PolyglotException.class, () -> identity.execute(1234L));
                context.eval("thc", request(artifact(stage), "stateIdentity", backend));
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
        var artifact = CoreCbdFixtures.write(temporary.resolve("legacy.cbd"), legacy);
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                // Internal eager-model validation retains the original cold-definition control.
                for (String entry : constructors) {
                    var linked = CoreModules.reachable(legacy, "main:AggregateFrontier." + entry);
                    var error = assertThrows(UnsupportedCore.class, () -> {
                        if (backend.equals("ast")) new Program(language, linked);
                        else new BytecodeProgram(language, linked);
                    }, backend + "/" + entry);
                    assertTrue(error.getMessage().contains("Unsupported constructor representation unboxed-"), backend + "/" + entry + ": " + error.getMessage());
                }
            } finally { context.leave(); }
            for (String entry : constructors) {
                // Public CBD definitions are prepared lazily; cold arms must still reject when reached.
                var error = assertThrows(PolyglotException.class, () -> {
                    var function = context.eval("thc", request(artifact, entry, backend));
                    if (entry.startsWith("cold")) function.execute(31337L);
                }, backend + "/" + entry);
                assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported constructor representation unboxed-"), backend + "/" + entry + ": " + error.getMessage());
            }
        }
    }
    private long compiledEntries(Value function) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get("compiledEntries")).longValue(); }
    @Test void diagnosticModeKeepsLegacyColdAggregatePathsLazyAndTrapsWhenReached() throws Exception {
        var module = object(withoutMarkers(exported("aggregate-core")));
        var legacy = CoreCbdFixtures.write(temporary.resolve("legacy.cbd"), module);
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            String entry = "coldSum"; long expected = -7L;
            var function = context.eval("thc", CoreModules.request(list(legacy.toString()), "main:AggregateFrontier." + entry, true, true, backend));
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
                var function = context.eval("thc", CoreModules.request(list(artifact("aggregate-core").toString()), "main:AggregateFrontier." + boundary.getKey(), true, true, backend));
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
                for (String name : constructors) entries.put(name, context.eval("thc", request(artifact(stage), name, backend)));
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
