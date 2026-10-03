// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreCbdFixtures;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.*;
import static thc.runtime.ScalarValueTestSupport.*;

class FloatingPrimitiveTest {
    @TempDir Path temporary;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File module = new File(root, "build/floating/core/FloatingAudit.cbd");
    private record Row(String entry, long input, long expected) {}
    private List<Row> rows() throws Exception {
        var result = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, "build/floating/oracle.tsv").toPath())) if (!line.isBlank()) {
            var fields = line.split("\t");
            result.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        return result;
    }
    private static long count(Value fn, String name) {
        return ((Number) object(Json.parse(fn.getMember("diagnostics").asString())).get(name)).longValue();
    }
    private static void checkRows(Value fn, List<Row> rows, String backend, String entry, String phase) {
        for (var row : rows) {
            long before = count(fn, "compiledEntries");
            assertEquals(row.expected, fn.execute(row.input).asLong(), backend + "/" + entry + "/" + phase + "/" + row.input);
            if (phase.equals("after")) assertTrue(count(fn, "compiledEntries") > before,
                backend + "/" + entry + "/" + row.input + " must execute installed code");
        }
    }
    @Test void nativeArithmeticRoundingNonFiniteComparisonsAndStorageMatchBothCompiledBackends() throws Exception {
        var provenance = object(Json.parse(Files.readString(new File(root, "build/floating/checks.json").toPath())));
        var records = new ArrayList<>(objects(provenance.get("sources")));
        records.addAll(objects(provenance.get("artifacts")));
        for (var record : records) {
            var file = new File(root, (String) record.get("path"));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(record.get("sha256"), hash, "Stale floating fixture: " + file + "; fixture quarantined: see docs/fixture-quarantine.log");
        }
        var entries = new LinkedHashMap<String, List<Row>>();
        for (var row : rows()) entries.computeIfAbsent(row.entry, ignored -> new ArrayList<>()).add(row);
        assertEquals(15, entries.size());
        assertEquals(441, entries.values().stream().mapToInt(List::size).sum());
        for (var backend : list("ast", "bytecode")) try (var context = executionContext()) {
            for (var entryRows : entries.entrySet()) {
                var entry = entryRows.getKey();
                var rows = entryRows.getValue();
                var fn = loadEntry(context, list(module.getPath()), "main:FloatingAudit." + entry, true, backend);
                checkRows(fn, rows, backend, entry, "before");
                for (int i = 0; i < 40; i++) {
                    var row = rows.get(i % rows.size());
                    assertEquals(row.expected, fn.execute(row.input).asLong());
                }
                assertTrue(fn.invokeMember("compile").asBoolean(), backend + "/" + entry);
                long before = count(fn, "compiledEntries");
                checkRows(fn, rows, backend, entry, "after");
                assertTrue(count(fn, "compiledEntries") > before, backend + "/" + entry + " must execute installed code");
                assertEquals(0L, count(fn, "unsupportedTraps"), backend + "/" + entry);
                if (entry.equals("floatingJoinSwap")) assertTrue(count(fn, "localJoinTransfers") > 0, backend + " real join");
            }
        }
    }
    @Test void previouslyColdNaNsInfinitiesSubnormalsAndSignedZerosRemainCorrectAfterCompilation() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = executionContext()) {
            for (var entry : list("floatComparisons", "doubleComparisons", "floatSignedZero", "doubleSignedZero")) {
                var fn = loadEntry(context, list(module.getPath()), "main:FloatingAudit." + entry, true, backend);
                var selected = rows().stream().filter(row -> row.entry.equals(entry)).toList();
                var warmRows = selected.stream().filter(row -> row.input == 7L).toList();
                assertEquals(1, warmRows.size());
                var warm = warmRows.getFirst();
                for (int i = 0; i < 40; i++) assertEquals(warm.expected, fn.execute(warm.input).asLong());
                assertTrue(fn.invokeMember("compile").asBoolean());
                for (var row : selected) if (row.input >= -3L && row.input <= 6L)
                    assertEquals(row.expected, fn.execute(row.input).asLong(), backend + "/" + entry + "/cold/" + row.input);
            }
        }
    }
    private static List<Class<?>> payloadTypes(Object value) {
        var types = new ArrayList<Class<?>>();
        for (var field : value.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) types.add(field.getType());
        return types;
    }
    @Test void fieldsAndCapturesHaveConcreteFloatingStorageAndPreserveBits() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = new DataLayout(language, "Floating", "Floating", new String[]{"FloatRep", "DoubleRep"});
                var captures = new CaptureLayout(language, new boolean[2], new boolean[2], new Class<?>[2],
                    new boolean[]{true, false}, new boolean[]{false, true});
                var layout = new FrameLayout();
                int[] slots = {layout.bind("float"), layout.bind("double")};
                var descriptor = layout.build();
                record Floating(float f, double d) {}
                var values = list(new Floating(0.0f, -0.0), new Floating(-0.0f, 0.0),
                    new Floating(Float.MIN_VALUE, Double.MIN_VALUE), new Floating(Float.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                    new Floating(Float.intBitsToFloat(0x7fc01234), Double.longBitsToDouble(0x7ff8000000001234L)));
                for (var value : values) {
                    float f = value.f; double d = value.d;
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                    FrameAccess.writeFloat(frame, slots[0], f); FrameAccess.writeDouble(frame, slots[1], d);
                    assertTrue(frame.isFloat(slots[0])); assertTrue(frame.isDouble(slots[1]));
                    var box = data.create(new Object[]{f, d});
                    assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(data.readFloat(box, 0)));
                    assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(data.readDouble(box, 1)));
                    assertTrue(payloadTypes(box).contains(float.class)); assertTrue(payloadTypes(box).contains(double.class));
                    for (var capture : list(captures.capture(frame, slots), captures.captureValues(new Object[]{f, d}))) {
                        assertFalse(capture.isObject(0)); assertFalse(capture.isObject(1));
                        assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(captures.readFloat(capture, 0)));
                        assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(captures.readDouble(capture, 1)));
                        assertTrue(payloadTypes(capture).contains(float.class)); assertTrue(payloadTypes(capture).contains(double.class));
                        var restored = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                        captures.restore(capture, 0, restored, slots[0]); captures.restore(capture, 1, restored, slots[1]);
                        assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(restored.getFloat(slots[0])));
                        assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(restored.getDouble(slots[1])));
                    }
                    data.restore(box, 0, frame, slots[0]); data.restore(box, 1, frame, slots[1]);
                    assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits(frame.getFloat(slots[0])));
                    assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(frame.getDouble(slots[1])));
                }
                assertThrows(RuntimeFault.class, () -> captures.captureValues(new Object[]{1L, 2.0}));
                assertThrows(RuntimeFault.class, () -> data.create(new Object[]{1.0, 2.0}));
                // Descriptor widening must not erase the live tags of older frames.
                var old = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                FrameAccess.writeFloat(old, slots[0], -0.0f);
                var widened = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
                FrameAccess.write(widened, slots[0], new Object());
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slots[0]));
                assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float) FrameAccess.read(old, slots[0])));
                // API/legacy robustness: no ordinary valid GHC scalar binder that widens
                // this way is known. New activations retain exact boxed carriers.
                var scalarLayout = new FrameLayout();
                int[] scalarSlots = {scalarLayout.bind("long"), scalarLayout.bind("float"), scalarLayout.bind("double")};
                var scalarDescriptor = scalarLayout.build();
                var scalarCaptures = new CaptureLayout(language, new boolean[]{true, false, false},
                    new boolean[]{true, false, false}, new Class<?>[3], new boolean[]{false, true, false}, new boolean[]{false, false, true});
                Object[] scalarValues = {Long.MIN_VALUE, -0.0f, Double.longBitsToDouble(0x7ff8000000001234L)};
                var primitiveFrame = Truffle.getRuntime().createVirtualFrame(new Object[0], scalarDescriptor);
                for (int i = 0; i < scalarSlots.length; i++) FrameAccess.write(primitiveFrame, scalarSlots[i], scalarValues[i]);
                var wideningFrame = Truffle.getRuntime().createVirtualFrame(new Object[0], scalarDescriptor);
                for (int slot : scalarSlots) FrameAccess.write(wideningFrame, slot, new Object());
                var boxedFrame = Truffle.getRuntime().createVirtualFrame(new Object[0], scalarDescriptor);
                FrameAccess.writeLong(boxedFrame, scalarSlots[0], (Long) scalarValues[0]);
                FrameAccess.writeFloat(boxedFrame, scalarSlots[1], (Float) scalarValues[1]);
                FrameAccess.writeDouble(boxedFrame, scalarSlots[2], (Double) scalarValues[2]);
                for (int slot : scalarSlots) assertTrue(boxedFrame.isObject(slot));
                for (var frame : list(primitiveFrame, boxedFrame)) {
                    var capture = scalarCaptures.capture(frame, scalarSlots);
                    assertEquals(scalarValues[0], scalarCaptures.readLong(capture, 0));
                    assertEquals(Float.floatToRawIntBits((Float) scalarValues[1]), Float.floatToRawIntBits(scalarCaptures.readFloat(capture, 1)));
                    assertEquals(Double.doubleToRawLongBits((Double) scalarValues[2]), Double.doubleToRawLongBits(scalarCaptures.readDouble(capture, 2)));
                }
                Object[] wrong = {1.0, 1.0, 1.0f};
                for (int i = 0; i < wrong.length; i++) {
                    FrameAccess.write(boxedFrame, scalarSlots[i], wrong[i]);
                    assertThrows(RuntimeFault.class, () -> scalarCaptures.capture(boxedFrame, scalarSlots));
                    FrameAccess.write(boxedFrame, scalarSlots[i], scalarValues[i]);
                }
            } finally { context.leave(); }
        }
    }
    // Malformed representation models exercise lowering directly; serialized execution uses CBD above.
    private static void rejectedModel(String module, String backend, Map<String, Object> binding, String message) {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = map("schema", 1, "ghc", "9.14.1", "module", module,
                    "bindings", list(binding), "constructors", list());
                var error = assertThrows(RuntimeFault.class, () -> {
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                    program.entryTarget("entry");
                });
                assertTrue(Objects.toString(error.getMessage(), "").contains(message), error.getMessage());
            } finally { context.leave(); }
        }
    }
    @Test void exactFloatingTupleProofsDoNotEnableFloatingLiteralAlternatives() {
        for (var kind : list("float", "double")) {
            var register = kind.equals("float") ? "FloatRep" : "DoubleRep";
            var proof = map("kind", kind, "primReps", list(register), "evaluated", true);
            assertEquals(kind.toUpperCase(Locale.ROOT), CoreRepresentations.parse(proof).getKind().name());
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(proof, "primReps", list("IntRep"))));
            assertTrue(CoreRepresentations.parse(map("kind", "unknown", "evaluated", true,
                "aggregate", "unboxed-tuple", "components", list(proof), "primReps", list(register))).isTuple());
            var body = list("case", list("lit", kind, "0.0"), "scrutinee", list(
                list("lit", list(kind, "-0.0"), list(), list("lit", "int", "1")),
                list("default", null, list(), list("lit", "int", "0"))));
            var binding = map("id", "entry", "name", "entry", "arity", 0, "lifted", true, "expr", body);
            for (var backend : list("ast", "bytecode"))
                rejectedModel("Floating.Invalid", backend, binding, "Floating literal alternatives");
            var opposite = kind.equals("float") ? "double" : "float";
            record Carrier(String kind, List<String> registers, List<Object> literal) {}
            var carriers = list(new Carrier(opposite, list(opposite.equals("float") ? "FloatRep" : "DoubleRep"), list("lit", opposite, "1.0")),
                new Carrier("long", list("IntRep"), list("lit", "int", "1")),
                new Carrier("address", list("AddrRep"), list("lit", "string-bytes", "41")), new Carrier("void", list(), list("void")));
            var malformed = new ArrayList<List<Object>>();
            for (var carrier : carriers) {
                var otherProof = map("kind", carrier.kind, "primReps", carrier.registers, "evaluated", true);
                for (int i = 0; i < 2; i++) {
                    List<Object> literal = i == 0 ? list("lit", kind, "1.0") : carrier.literal;
                    var declared = i == 0 ? otherProof : proof;
                    var annotated = new ArrayList<>(literal); annotated.add(map("rep", declared)); malformed.add(annotated);
                    malformed.add(list("case", list("lit", "int", "0"), "s",
                        list(list("default", null, list(), literal)), map("rep", declared)));
                }
            }
            for (var expr : malformed) for (var backend : list("ast", "bytecode"))
                rejectedModel("Floating.Invalid", backend, with(binding, "expr", expr), "Conflicting Core representation proofs");
        }
    }
    @Test void floatingCaseValidationChecksEveryKnownAlternativeWithoutOrderDependence() {
        for (var kind : list("float", "double")) {
            var proof = map("kind", kind, "primReps", list(kind.equals("float") ? "FloatRep" : "DoubleRep"), "evaluated", true);
            var floating = list("lit", kind, "1.0");
            var others = list(list("lit", kind.equals("float") ? "double" : "float", "1.0"),
                list("lit", "int", "1"), list("lit", "string-bytes", "41"), list("void"));
            for (var other : others) for (var arms : list(list(floating, other), list(other, floating)))
                for (var declared : list(null, proof)) for (var backend : list("ast", "bytecode")) {
                    var expr = new ArrayList<Object>(list("case", list("lit", "int", "0"), "s", list(
                        list("default", null, list(), arms.get(0)), list("lit", list("int", "0"), list(), arms.get(1)))));
                    if (declared != null) expr.add(map("rep", declared));
                    var binding = map("id", "entry", "name", "entry", "arity", 0, "lifted", true, "expr", expr);
                    rejectedModel("Floating.MixedCase", backend, binding, "Conflicting Core representation proofs");
                }
        }
    }
    @Test void floatingSelfTransfersRejectWrongPrimitiveCarriers() {
        var target = new RootNode(null) { @Override public Object execute(VirtualFrame frame) { return 0L; } }.getCallTarget();
        var closure = new Closure(null, 1, target);
        for (var kind : list(CoreKind.FLOAT, CoreKind.DOUBLE)) {
            var layout = new FrameLayout();
            int destination = layout.bind("formal"), temporary = layout.bind("temporary");
            var self = new AstSelfLayout(null, new int[0], new int[]{destination},
                new CoreRepresentation[]{new CoreRepresentation(kind, true, false, null, null, null, null, null, null)}, new boolean[]{true});
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], layout.build());
            FrameAccess.writeLong(frame, temporary, 1L);
            assertThrows(RuntimeFault.class, () -> self.transfer(frame, closure, new int[]{temporary}));
        }
    }
    @Test void serializedFloatingRejectionsSurviveCbdEncodingAndDecoding() throws Exception {
        for (var kind : list("float", "double")) {
            var alternative = list("case", list("lit", kind, "0.0", map()), "scrutinee", list(
                list("lit", list(kind, "-0.0"), list(), list("lit", "int", "1", map()), map("binders", list())),
                list("default", null, list(), list("lit", "int", "0", map()), map("binders", list()))), map());
            var conflicting = list("lit", kind, "1.0", map("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true)));
            for (var control : list(map("expr", alternative, "error", "Floating literal alternatives"),
                                    map("expr", conflicting, "error", "Conflicting Core representation proofs"))) {
                var entry = "main:Floating.Invalid.entry";
                var binding = map("id", entry, "name", "entry", "arity", 0, "lifted", true, "expr", control.get("expr"));
                var artifact = CoreCbdFixtures.write(temporary.resolve(kind + "-" + (control.get("expr") == alternative ? "alternative" : "conflict") + ".cbd"),
                    map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Floating.Invalid",
                        "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", list(binding), "constructors", list()));
                for (var backend : list("ast", "bytecode")) try (var context = executionContext()) {
                    var error = assertThrows(PolyglotException.class, () -> loadEntry(context, list(artifact.toString()), entry, true, backend));
                    assertTrue(Objects.toString(error.getMessage(), "").contains((String) control.get("error")), error.getMessage());
                }
            }
        }
    }
}
