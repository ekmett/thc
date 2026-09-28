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
import thc.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class WordCarryTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> entries = list("addWordC", "subWordC", "addWordCall", "subWordCall");
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation"); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long x, long y, long first, long flag) {}
    private Map<String, Object> module(String stage) throws Exception { return object(Json.parse(Files.readString(root.resolve("build/tuple-arithmetic/" + stage + "-core/TupleArithmeticAudit.json")))); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private List<Row> rows() throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/tuple-arithmetic/manifest.json"))));
        for (var kind : list("inputHashes", "artifactHashes")) for (var entry : object(manifest.get(kind)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale carry/borrow fixture: " + entry.getKey());
        }
        var modulus = BigInteger.ONE.shiftLeft(64); var rows = new ArrayList<Row>();
        for (var name : list("oracle.tsv", "call-oracle.tsv")) for (var line : Files.readAllLines(root.resolve("build/tuple-arithmetic/" + name))) {
            var fields = line.split("\t", -1); var row = new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4]));
            if (entries.contains(row.name())) rows.add(row);
        }
        assertEquals(new HashSet<>(entries), new HashSet<>(rows.stream().map(Row::name).toList()));
        record Input(String name, long x, long y) {}
        assertEquals(rows.size(), new HashSet<>(rows.stream().map(row -> new Input(row.name(), row.x(), row.y())).toList()).size());
        for (var row : rows) {
            var x = BigInteger.valueOf(row.x()).mod(modulus); var y = BigInteger.valueOf(row.y()).mod(modulus);
            var result = row.name().startsWith("add") ? x.add(y) : x.subtract(y);
            assertEquals(result.longValue(), row.first(), "Wrapped field: " + row);
            assertEquals(result.signum() < 0 || result.compareTo(modulus) >= 0 ? 1L : 0L, row.flag(), "Full-width flag: " + row);
        }
        return rows;
    }
    @Test void nativeMixedWordIntFieldsWithInlining() throws Exception { nativeValues(true); }
    @Test void nativeMixedWordIntFieldsAcrossResidualCalls() throws Exception { nativeValues(false); }
    private void nativeValues(boolean inlining) throws Exception {
        var rows = rows();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var name : entries) try (var context = context(inlining)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var selected = rows.stream().filter(row -> row.name().equals(name)).toList();
                var audit = object(Json.parse(Files.readString(root.resolve("build/tuple-arithmetic/" + stage + "-audit.json")))); assertEquals(true, audit.get("accepted"));
                var primitive = (name.endsWith("Call") ? name.replace("Call", "C") : name) + "#";
                assertTrue(objects(audit.get("primitives")).stream().anyMatch(item -> primitive.equals(item.get("name"))), stage + "/" + name + " primitive audited");
                if (name.endsWith("Call")) assertTrue(objects(audit.get("reachableBindings")).stream().anyMatch(item -> ("main:TupleArithmeticAudit." + name.replace("Call", "Result")).equals(item.get("id"))));
                var program = program(language, with(CoreModules.reachable(module(stage), name), "instrument", true), backend);
                var function = context.asValue(new EntryValue(program, name, 3)); var host = program.hostEntryTarget(3); var original = program.entryTarget(name);
                var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                CheckedBiConsumer<Row, Long> check = (row, field) -> {
                    assertEquals(field == 0L ? row.first() : row.flag(), function.execute(row.x(), row.y(), field).asLong(), label + "/" + row + "/" + field); released(language);
                };
                for (var row : selected) for (long field = 0; field <= 1; field++) check.accept(row, field);
                var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " adopted guest path");
                for (var target : targets) if (target != host) compile(target); assertTrue(function.invokeMember("compile").asBoolean());
                long allocations = language.getHandoffState().get().getResults().getAllocations(); long expectedEntries = name.endsWith("Call") ? 2L : 1L;
                for (var row : selected.reversed()) for (long field = 0; field <= 1; field++) {
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(row, field);
                    assertEquals(expectedEntries, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before, label + " exact installed entries");
                    assertEquals(targets, activeTargets(host), label + " active target identities"); valid(original, label + " original"); for (var target : targets) valid(target, label + " active");
                }
                assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(), label + " result slabs reused");
                if (!name.endsWith("Call")) assertEquals(0L, allocations, label + " direct primitive needs no carrier");
                for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                System.out.println("WordCarry PASS " + label + " pairs=" + selected.size() + " comparisons=" + selected.size() * 2 + " entriesPerCall=" + expectedEntries);
            } finally { context.leave(); }
        }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) {
            if (!values.isEmpty() && "app".equals(values.getFirst())) result.add(expression(values));
            for (var item : values) result.addAll(applications(item));
        } else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<Object> application(Object module, String name) {
        var matches = applications(module).stream().filter(app -> {
            var head = expression(app.get(1)); return head.size() >= 2 && head.subList(0, 2).equals(list("prim", name + "#"));
        }).toList(); assertEquals(1, matches.size()); return matches.getFirst();
    }
    @Test void mixedResultFieldOrderSignednessAndLogicalShapeCannotBeForged() throws Exception {
        var shapes = list(list("IntRep", "WordRep"), list("WordRep", "WordRep"), list("IntRep", "IntRep"), list("WordRep", "Int64Rep"), list("Word64Rep", "IntRep"));
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : list("addWordC", "subWordC")) for (var shape : shapes) for (boolean diagnostic : list(false, true)) {
                    var module = CoreModules.reachable(module(stage), name); var app = application(module, name);
                    var rep = object(Objects.requireNonNull(CoreRepresentations.metadata(app)).get("rep")); rep.put("primReps", shape);
                    var components = objects(rep.get("components")); for (int i = 0; i < components.size(); i++) components.get(i).put("primReps", list(shape.get(i)));
                    var error = assertThrows(RuntimeFault.class, () -> program(language, with(module, "diagnosticUnsupported", diagnostic), backend));
                    // Only the application's annotation changed; its enclosing
                    // case still supplies the original logical aggregate proof.
                    // This is a contradictory Core annotation, not evidence that
                    // physically equivalent Long carriers select an operation.
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Conflicting logical aggregate representation proofs"), error.getMessage());
                }
                for (var name : list("addWordC", "subWordC")) for (var variant : list("nested", "state", "unknown")) {
                    var module = CoreModules.reachable(module(stage), name); var app = application(module, name);
                    var rep = object(Objects.requireNonNull(CoreRepresentations.metadata(app)).get("rep")); var components = objects(rep.get("components")); var flag = components.get(1);
                    components.set(1, switch (variant) {
                        case "nested" -> map("kind", "unknown", "evaluated", true, "aggregate", "unboxed-tuple", "primReps", list("IntRep"), "components", list(flag));
                        case "state" -> map("kind", "void", "evaluated", true, "primReps", list());
                        default -> with(flag, "kind", "unknown");
                    });
                    rep.put("primReps", variant.equals("state") ? list("WordRep") : list("WordRep", "IntRep"));
                    assertThrows(RuntimeFault.class, () -> program(language, module, backend), stage + "/" + backend + "/" + name + "/" + variant + " flag");
                }
            } finally { context.leave(); }
        }
    }
    private Expr operand(List<Integer> events, int position, boolean fail) {
        return new Expr() { @Override public Object execute(VirtualFrame frame) { events.add(position); if (fail) throw new RuntimeFault("operand failed"); return 1L; } };
    }
    @Test void bothOperandsCompleteBeforeEitherDestinationIsWritten() throws Exception {
        var descriptor = FrameDescriptor.newBuilder(); int first = descriptor.addSlot(FrameSlotKind.Long, "first", null), second = descriptor.addSlot(FrameSlotKind.Long, "flag", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor.build()); var components = new ArrayList<Map<String, Object>>();
        for (var rep : list("WordRep", "IntRep")) components.add(map("kind", "long", "evaluated", true, "primReps", list(rep)));
        var proof = CoreRepresentations.parse(map("aggregate", "unboxed-tuple", "kind", "unknown", "evaluated", true, "primReps", list("WordRep", "IntRep"), "components", components));
        for (var operation : list(TupleArithmeticOp.ADD_WORD_C, TupleArithmeticOp.SUB_WORD_C)) {
            var events = new ArrayList<Integer>(); frame.setLong(first, 42L); frame.setLong(second, 43L);
            var failing = new TupleArithmeticExpression(operation, proof, operand(events, 0, false), operand(events, 1, true));
            assertThrows(RuntimeFault.class, () -> failing.executeTuple(frame, new int[]{first, second}, 0));
            assertEquals(list(0, 1), events); assertEquals(42L, frame.getLong(first)); assertEquals(43L, frame.getLong(second)); events.clear();
            new TupleArithmeticExpression(operation, proof, operand(events, 0, false), operand(events, 1, false)).executeTuple(frame, new int[]{first, second}, 0);
            assertEquals(list(0, 1), events); assertEquals(operation == TupleArithmeticOp.ADD_WORD_C ? 2L : 0L, frame.getLong(first)); assertEquals(0L, frame.getLong(second));
        }
    }
}
