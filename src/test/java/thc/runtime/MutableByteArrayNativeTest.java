// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class MutableByteArrayNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of(
        "filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes", "publicReplicate");
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/mutable-bytearrays/manifest.json").toPath()));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add(thc.CoreCbdFixtures.read(new File(root, path).toPath()));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private final List<ByteArrayOp> operations =
        List.of(ByteArrayOp.SET, ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING);
    @Test
    void nativeFillsMovesAndPublicReplicate() throws Exception {
        verifyNative();
    }
    private record Row(long raw, long code, long expected) {}
    private record Input(long raw, long code) {}
    private record Range(int from, int to, int count) {}
    private final List<Input> inputs = inputs();
    private List<Input> inputs() {
        var values = new HashSet<Input>();
        var carriers = new HashSet<Long>();
        for (long raw = -256; raw <= 511; raw++) carriers.add(raw);
        for (long sign : new long[] {-1, 1})
            for (int bit = 0; bit <= 63; bit++)
                for (long delta = -1; delta <= 1; delta++) carriers.add(sign * ((1L << bit) + delta));
        for (long raw : carriers) values.add(new Input(raw, 72));
        for (long code = 0; code <= 1023; code++)
            values.add(new Input(code * 0x123456789abcdefL + Long.MIN_VALUE, code));
        for (long raw : new long[] {Long.MIN_VALUE, -257, -1, 0, 255, 256, Long.MAX_VALUE})
            for (long code : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) values.add(new Input(raw, code));
        var result = new ArrayList<>(values);
        result.sort(Comparator.comparingLong(Input::raw).thenComparingLong(Input::code));
        return result;
    }
    private Range copyRange(long code) {
        int key = (int) (code & 1023), from = key % 9, to = key / 9 % 9;
        return new Range(from, to, Math.min(key / 81 % 9, Math.min(8 - from, 8 - to)));
    }
    private Range disjointRange(long code) {
        int key = (int) (code & 1023), low = key % 5, high = 4 + key / 5 % 5,
            count = Math.min(key / 25 % 5, Math.min(4 - low, 8 - high));
        return key / 125 % 2 == 0 ? new Range(low, high, count) : new Range(high, low, count);
    }
    private long fingerprint(List<Integer> values) {
        long weight = 1, result = 0;
        for (int value : values) {
            result += weight * value;
            weight *= 257;
        }
        return result;
    }
    // Independent list/snapshot semantics, never a call into ManagedByteArray.
    // Long arithmetic deliberately retains the native signed-64-bit wrap.
    private long model(String name, long raw, long code) {
        if (!names.contains(name))
            throw new IllegalArgumentException("Unknown mutable byte-array entry");
        var source = new ArrayList<Integer>();
        for (int i = 0; i < 8; i++) source.add((int) ((raw + 17L * i) & 255));
        if (name.equals("publicReplicate")) {
            long answer = code & 15;
            for (int i = 0; i < (int) (code & 15); i++) answer = answer * 257 + (raw & 255);
            return answer;
        }
        if (name.equals("filledBytes")) {
            int key = (int) (code & 1023), start = key % 9, count = Math.min(key / 9 % 9, 8 - start);
            for (int i = 0; i < count; i++) source.set(start + i, (int) (raw & 255));
            return fingerprint(source);
        }
        var range = name.equals("disjointBytes") ? disjointRange(code) : copyRange(code);
        if (List.of("movedBytes", "disjointBytes").contains(name)) {
            var snapshot = new ArrayList<>(source);
            for (int i = 0; i < range.count(); i++) source.set(range.to() + i, snapshot.get(range.from() + i));
            return fingerprint(source);
        }
        var destination = new ArrayList<Integer>();
        for (int i = 0; i < 8; i++) destination.add((int) ((raw + 101 + 29L * i) & 255));
        for (int i = 0; i < range.count(); i++) destination.set(range.to() + i, source.get(range.from() + i));
        source.set(0, (int) ((raw + 93) & 255));
        return fingerprint(source) + 65537 * fingerprint(destination);
    }
    private Long longOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }
    private void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }
    private Map<String, List<Row>> checkedRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        require(lines.size() == names.size() * inputs.size(), "Incomplete mutable byte-array corpus");
        var result = new LinkedHashMap<String, List<Row>>();
        for (var name : names) result.put(name, new ArrayList<>());
        for (int index = 0; index < lines.size(); index++) {
            var fields = lines.get(index).split("\t", -1);
            require(fields.length == 4, "Expected entry/raw/code/result");
            var name = names.get(index / inputs.size());
            var input = inputs.get(index % inputs.size());
            require(fields[0].equals(name) && Objects.equals(longOrNull(fields[1]), input.raw())
                    && Objects.equals(longOrNull(fields[2]), input.code()),
                "Missing, duplicate, reordered or unknown mutable byte-array row " + index);
            long expected = model(name, input.raw(), input.code());
            require(Objects.equals(longOrNull(fields[3]), expected), "Native/model mismatch at row " + index);
            result.get(name).add(new Row(input.raw(), input.code(), expected));
        }
        return result;
    }
    @Test
    void independentMutableModelCoversCarriersAndEveryContainedRange() {
        assertEquals(2146, inputs.size());
        var pairs = new HashSet<>(inputs);
        for (long raw = -256; raw <= 511; raw++) assertTrue(pairs.contains(new Input(raw, 72)));
        for (long raw : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) assertTrue(pairs.contains(new Input(raw, 72)));
        var moves = new HashSet<Range>();
        var disjoint = new HashSet<Range>();
        for (var input : inputs) {
            moves.add(copyRange(input.code()));
            disjoint.add(disjointRange(input.code()));
        }
        assertTrue(moves.containsAll(ranges()));
        boolean forward = false, backward = false;
        for (var range : disjoint) {
            if (range.count() > 0 && range.from() < range.to())
                forward = true;
            if (range.count() > 0 && range.from() > range.to())
                backward = true;
            assertTrue(range.count() == 0 || range.from() + range.count() <= range.to()
                || range.to() + range.count() <= range.from());
        }
        assertTrue(forward);
        assertTrue(backward);
    }
    @Test
    void independentMutableMovesSnapshotBothDirectionsAndDistinctCopiesAgree() {
        for (var range : List.of(new Range(0, 1, 7), new Range(1, 0, 7), new Range(0, 0, 8), new Range(8, 8, 0))) {
            var source = new ArrayList<Integer>();
            for (int i = 0; i < 8; i++) source.add((127 + 17 * i) % 256);
            var expected = new ArrayList<Integer>();
            for (int i = 0; i < source.size(); i++)
                expected.add(i >= range.to() && i < range.to() + range.count()
                        ? source.get(range.from() + i - range.to())
                        : source.get(i));
            assertEquals(
                fingerprint(expected), model("movedBytes", 127, range.from() + 9L * range.to() + 81L * range.count()));
        }
        for (var input : inputs)
            assertEquals(model("copiedMutableBytes", input.raw(), input.code()),
                model("copiedDisjointBytes", input.raw(), input.code()));
        assertThrows(IllegalArgumentException.class, () -> model("unknown", 0, 0));
    }
    private String text(List<String> rows) {
        return String.join("\n", rows) + "\n";
    }
    private List<String> replaced(List<String> lines, String first) {
        var result = new ArrayList<>(lines);
        result.set(0, first);
        return result;
    }
    @Test
    void independentMutableCorpusRejectsMissingDuplicateReorderedAndWrongRows() throws Exception {
        var lines = new ArrayList<String>();
        for (var name : names)
            for (var input : inputs)
                lines.add(
                    name + "\t" + input.raw() + "\t" + input.code() + "\t" + model(name, input.raw(), input.code()));
        assertEquals(new HashSet<>(names), checkedRows(text(lines)).keySet());
        var duplicate = new ArrayList<>(lines);
        duplicate.add(lines.getFirst());
        var blank = new ArrayList<>(lines);
        blank.add("");
        for (var bad : List.of(lines.subList(1, lines.size()), duplicate, lines.reversed(),
                 replaced(lines, lines.get(1)), replaced(lines, "unknown\t0\t0\t0"),
                 replaced(lines, lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t999"),
                 replaced(lines, lines.getFirst().replaceFirst("\t", " ")), blank, List.<String>of()))
            assertThrows(IllegalArgumentException.class, () -> checkedRows(text(bad)));
        checkedRows(Files.readString(new File(root, "build/mutable-bytearrays/oracle.tsv").toPath()));
    }
    private long count(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    private void check(Row row, Value function, String label, Language language) {
        assertEquals(row.expected(), function.execute(row.raw(), row.code()).asLong(),
            label + "/" + row.raw() + "/" + row.code());
        released(language);
    }
    private void verifyNative() throws Exception {
        var manifest = manifest();
        ByteArrayFixtureEvidence.verify(root, "mutable-bytearrays", manifest);
        assertEquals(names, manifest.get("entries"));
        var wantedInputs = new ArrayList<List<Long>>();
        for (var input : inputs) wantedInputs.add(List.of(input.raw(), input.code()));
        assertEquals(wantedInputs, manifest.get("inputs"));
        var rows = checkedRows(Files.readString(new File(root, "build/mutable-bytearrays/oracle.tsv").toPath()));
        assertEquals(new HashSet<>(names), rows.keySet());
        int rowCount = 0;
        for (var list : rows.values()) rowCount += list.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (var name : names) {
                var cases = Objects.requireNonNull(rows.get(name));var audit=(Map<?,?>)Json.parse(Files.readString(new File(root,"build/mutable-bytearrays/"+stage.getKey()+"-"+name+".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"));
                for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var module = new LinkedHashMap<>(CoreModules.reachable(merged(stage.getValue()), "main:MutableByteArrayAudit." + name));
                            module.put("instrument", true);
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, "main:MutableByteArrayAudit." + name, 2));
                            var label = stage.getKey() + "/" + backend + "/" + name;
                            for (var row : cases) check(row, function, label, language);
                            long beforeInstallation = count(p);
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            assertEquals(beforeInstallation, count(p), label + " installation executes no guest work");
                            var diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            assertEquals(true, ((Map<?, ?>) diagnostics.get("explicitCompilation")).get("validLastTier"),
                                label + " guest entry and host bridge installed");
                            var installedRows = cases.reversed();
                            check(installedRows.getFirst(), function, label, language);
                            assertTrue(count(p) > beforeInstallation, label + " first installed call enters compiled guest code");
                            diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            assertEquals(true, ((Map<?, ?>) diagnostics.get("explicitCompilation")).get("validLastTier"),
                                label + " first installed call preserves the installed guest entry and host bridge");
                            for (var row : installedRows.subList(1, installedRows.size())) check(row, function, label, language);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), label);
                            System.out.println("MutableByteArray PASS " + label + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
            }
    }
    @Test
    void mutableFixtureEvidenceRejectsMissingAndChangedProvenance() throws Exception {
        ByteArrayFixtureEvidence.rejectionControls(root, "mutable-bytearrays");
    }
    private List<Range> ranges() {
        var result = new ArrayList<Range>();
        for (int a = 0; a <= 8; a++)
            for (int b = 0; b <= 8; b++)
                for (int n = 0; n <= Math.min(8 - a, 8 - b); n++) result.add(new Range(a, b, n));
        return result;
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var item : list) result.addAll(applications(item));}else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<Object> application(Object module, ByteArrayOp op) {
        for (var app : applications(module)) {
            var function = (List<?>) app.get(1);
            if (function.subList(0, Math.min(2, function.size())).equals(List.of("prim", op.getPrimitive())))
                return app;
        }
        throw new NoSuchElementException();
    }
    @Test
    void exactArityIntFillStateAndUnliftedReferenceProofsAreRequired() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations) {
                        var name = operation == ByteArrayOp.SET     ? "filledBytes"
                            : operation == ByteArrayOp.COPY_MUTABLE ? "movedBytes"
                                                                    : "disjointBytes";
                        for (int mutation = 0; mutation <= 12; mutation++)
                            for (boolean diagnostic : new boolean[] {false, true}) {
                                var module = CoreModules.reachable(merged(paths), "main:MutableByteArrayAudit." + name);
                                var app = application(module, operation);
                                var args = (List<Object>) app.get(2);
                                var flags = (List<Object>) app.get(3);
                                var meta = CoreRepresentations.metadata(app);
                                switch (mutation) {
                                    case 0 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                        meta.remove("callDemand");
                                    }
                                    case 1 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                        meta.remove("callDemand");
                                    }
                                    case 2 -> meta.remove("rep");
                                    case 3 -> flags.set(0, true);
                                    case 4 ->
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.getFirst())
                                                .get("rep"))
                                            .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    case 5, 6, 7 -> {
                                        int index = operation == ByteArrayOp.SET ? 3 : 4;
                                        var rep = new LinkedHashMap<>((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.get(index)).get("rep"));
                                        rep.put("primReps", List.of(mutation == 5 ? "Word8Rep"
                                                : mutation == 6 ? "WordRep" : "Int64Rep"));
                                        // A use-only width change would contradict the original variable's binder.
                                        args.set(index, List.of("lit", "int", "0", Map.of("rep", rep)));
                                    }
                                    case 8 ->
                                        meta.put("rep",
                                            Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                List.of(), "primReps", List.of(), "evaluated", true));
                                    case 9 ->
                                        CoreRepresentations.metadata((List<Object>) args.getLast())
                                            .put("rep",
                                                Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                    List.of(), "primReps", List.of(), "evaluated", true));
                                    case 12 -> {
                                        int index = operation == ByteArrayOp.SET ? 0 : 2;
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.get(index))
                                                .get("rep"))
                                            .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    }
                                    case 10, 11 -> {
                                        int index = mutation == 10 ? 1 : operation == ByteArrayOp.SET ? 2 : 3;
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.get(index))
                                                .get("rep"))
                                            .put("primReps", List.of("WordRep"));
                                    }
                                }
                                var configured = new LinkedHashMap<>(module);
                                configured.put("diagnosticUnsupported", diagnostic);
                                var label = operation + "/" + mutation + "/" + backend + "/" + diagnostic;
                                // Word8 uses an Int carrier; native-word fill/count/offset operands use Long.
                                if (mutation >= 6 && mutation <= 7 || mutation >= 10 && mutation <= 11)
                                    assertDoesNotThrow(() -> program(language, configured, backend), label);
                                else
                                    assertThrows(
                                        RuntimeFault.class, () -> program(language, configured, backend), label);
                            }
                        var module = CoreModules.reachable(merged(paths), "main:MutableByteArrayAudit." + name);
                        var app = application(module, operation);
                        var primitive = new ArrayList<>((List<?>) app.get(1));
                        app.clear();
                        app.addAll(primitive);
                        assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                    }
                } finally {
                    context.leave();
                }
            }
    }
}
