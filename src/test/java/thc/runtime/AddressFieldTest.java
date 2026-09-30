// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RepresentationTestSupport.*;

class AddressFieldTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Context context(boolean inline) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inline)).build(); }
    private void withLanguage(CheckedConsumer<Language> action) throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try { action.accept(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private Map<String, Object> manifest() throws Exception { return object(Json.parse(Files.readString(root.resolve("build/address-fields/manifest.json")))); }
    private void verify(Map<String, Object> manifest) throws Exception {
        for (var key : list("inputHashes", "artifactHashes")) for (var entry : object(manifest.get(key)).entrySet()) {
            var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
            assertEquals(entry.getValue(), actual, "Stale address-field evidence: " + entry.getKey());
        }
    }
    @Test void genuineAddressFieldsInline() throws Exception { nativeFields(true); }
    @Test void genuineAddressFieldsResidual() throws Exception { nativeFields(false); }
    private void check(Value value, List<String> row, String label) { assertEquals(Long.parseLong(row.get(2)), value.execute(Long.parseLong(row.get(1))).asLong(), label + "/" + row.get(1)); }
    private List<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
        return NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class).stream().filter(node -> node.getCallTarget() == original).map(node -> (RootCallTarget) node.getCurrentCallTarget()).toList();
    }
    private void nativeFields(boolean inline) throws Exception {
        var manifest = manifest(); verify(manifest); var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(root.resolve("build/address-fields/oracle.tsv"))) { var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); }
        assertEquals(new HashSet<>((List<?>) manifest.get("entries")), rows.keySet()); assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        for (var stage : object(manifest.get("stages")).entrySet()) for (var entry : rows.entrySet()) for (var backend : list("ast", "bytecode")) try (var context = context(inline)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var source = CoreCbdFixtures.read(root.resolve((String) stage.getValue()));
                var name = entry.getKey(); var entryId = source.get("unit") + ":" + source.get("module") + "." + name; var inputs = entry.getValue(); var program = program(language, with(CoreModules.reachable(source, entryId, false), "instrument", true), backend);
                var value = context.asValue(new EntryValue(program, entryId, 1)); var host = program.hostEntryTarget(1); var original = program.entryTarget(entryId);
                var label = stage.getKey() + "/" + backend + "/" + name + "/inline=" + inline;
                for (var row : inputs) check(value, row, label);
                var active = active(host, original); assertTrue(!active.isEmpty(), label + " observed host-to-entry call"); assertTrue(value.invokeMember("compile").asBoolean(), label + " installed");
                for (int i = inputs.size() - 1; i >= 0; i--) {
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(value, inputs.get(i), label);
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label + " compiled guest entry"); assertEquals(active, active(host, original), label + " active target identities");
                    valid(host, label + " host"); valid(original, label + " original"); for (var target : active) valid(target, label + " active"); released(language);
                }
                for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
            } finally { context.leave(); }
        }
    }
    private Map<String, Object> proof(String kind, List<String> reps, boolean evaluated) { return map("kind", kind, "primReps", reps, "evaluated", evaluated); }
    private final Map<String, Object> address = proof("address", list("AddrRep"), true), wide = proof("long", list("IntRep"), true), lazy = proof("data", list("BoxedRep (Just Lifted)"), false);
    private Map<String, Object> constructor(boolean typed) {
        var result = map("id", "Record", "name", "Record", "kind", "boxed", "arity", 3, "tag", 1, "fieldReps", list(list("AddrRep"), list("IntRep"), list("BoxedRep (Just Lifted)")), "fieldLifted", list(false, false, true), "strictFields", list(false, false, false));
        if (typed) result.put("fieldTypes", list(address, wide, lazy)); return result;
    }
    private List<Object> variable(String id) { return list("var", id); }
    private Map<String, Object> binding(String id, List<Object> expr, int arity) { return map("id", id, "name", id, "lifted", true, "arity", arity, "expr", expr); }
    private Map<String, Object> module(boolean typed, boolean partial) {
        var arguments = new ArrayList<List<Object>>(list(variable("address"), list("lit", "int", "17"))); if (!partial) arguments.add(variable("bottom"));
        var body = list("app", list("con", "Record", 3), arguments, partial ? list(false, false) : list(false, false, true));
        return map("schema", 1, "ghc", "9.14.1", "constructors", list(constructor(typed)), "bindings", list(binding("bottom", variable("bottom"), 0), binding("entry", list("lam", list(map("id", "address", "lifted", false)), body), 1)));
    }
    @Test void knownAddressStorageIsFinalAndPreciseEvenWithoutOptionalFieldTypes() throws Exception {
        withLanguage(language -> {
            for (boolean typed : new boolean[]{true, false}) {
                var fields = new CoreFields(constructor(typed)); assertEquals(ManagedAddress.class, fields.getReferenceTypes()[0]);
                var layout = new DataLayout(language, "Record" + typed, "Record", fields.getStorage(), fields.getReferenceTypes()); var address = ManagedAddress.fromHex("41ff0042").plus(1); var untouched = new Object();
                var value = layout.create(new Object[]{address, 17L, untouched}); assertSame(address, layout.read(value, 0)); assertSame(untouched, layout.read(value, 2)); assertEquals(255L, ((ManagedAddress) layout.read(value, 0)).indexChar(0));
                var stored = Arrays.asList(value.getClass().getDeclaredFields()); stored.sort(Comparator.comparing(java.lang.reflect.Field::getName));
                assertEquals(list(ManagedAddress.class, long.class, Object.class), stored.stream().map(java.lang.reflect.Field::getType).toList());
                assertTrue(stored.stream().allMatch(field -> Modifier.isFinal(field.getModifiers()))); assertSame(layout, value.getLayout());
                for (var fake : list(null, 0L, new Object(), new byte[]{65}, ByteBuffer.allocateDirect(8))) assertThrows(RuntimeFault.class, () -> layout.create(new Object[]{fake, 17L, untouched}));
            }
        });
    }
    private Object call(ExecutableProgram program, Object value) { return Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("entry"), new Object[]{value}}); }
    @Test void bothBackendsRejectWrongCarriersAndKeepLazyNeighboursUnforced() throws Exception {
        withLanguage(language -> {
            for (boolean typed : new boolean[]{true, false}) for (var backend : list("ast", "bytecode")) {
                var program = program(language, module(typed, false), backend); var address = ManagedAddress.fromHex("ff0041"); var result = (DataValue) call(program, address);
                assertSame(address, result.getLayout().read(result, 0)); assertEquals(17L, result.getLayout().readLong(result, 1)); var bottom = (Thunk) program.entryValue("bottom");
                assertSame(bottom, result.getLayout().read(result, 2)); assertEquals(0, bottom.getState()); for (int i = 0; i < 12; i++) call(program, address);
                var target = program.entryTarget("entry"); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, backend + "/typed=" + typed + " carrier check installed");
                for (var fake : list(0L, new Object(), new byte[]{65}, null)) assertThrows(RuntimeFault.class, () -> call(program, fake));
                var after = (DataValue) call(program, address); assertSame(address, after.getLayout().read(after, 0)); assertEquals(0, bottom.getState()); released(language);
            }
        });
    }
    @Test void constructorPartialApplicationRetainsAddressAndDefersLazyPayload() throws Exception {
        withLanguage(language -> {
            for (boolean typed : new boolean[]{true, false}) for (var backend : list("ast", "bytecode")) {
                var program = program(language, module(typed, true), backend); var address = ManagedAddress.fromHex("41"); var pap = (Closure) call(program, address);
                assertEquals(2, pap.suppliedCount); assertEquals(1, pap.arity);
                if (typed) {
                    var prefix = Objects.requireNonNull(pap.typedSupplied); var shape = prefix.getLayout();
                    assertEquals(0, pap.supplied.length); assertFalse(prefix.getLive()); assertEquals(0, prefix.getInputMode());
                    assertSame(address, shape.getObject(prefix, 0)); assertEquals(17L, shape.getLong(prefix, 1));
                } else {
                    assertNull(pap.typedSupplied); assertEquals(2, pap.supplied.length);
                    assertSame(address, pap.supplied[0]); assertEquals(17L, pap.supplied[1]);
                }
                var bottom = (Thunk) program.entryValue("bottom"); assertEquals(0, bottom.getState());
                var result = (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{pap, new Object[]{bottom}});
                assertSame(address, result.getLayout().read(result, 0)); assertSame(bottom, result.getLayout().read(result, 2)); assertEquals(0, bottom.getState()); released(language);
            }
        });
    }
    @Test void unliftedAddressComputationRunsBeforePartialApplicationPublication() throws Exception {
        withLanguage(language -> {
            var source = module(true, true); var shifted = list("app", list("prim", "plusAddr#"), list(list("lit", "string-bytes", "41"), variable("offset")), list(false, false));
            // Sentinel address arithmetic is allowed; a strict read exposes failure before publication.
            var read = list("app", list("prim", "indexCharOffAddr#"), list(shifted, list("lit", "int", "0")), list(false, false));
            var checked = list("case", read, "checkedByte", list(list("default", null, list(), shifted)));
            var partial = list("app", list("con", "Record", 3), list(checked, list("lit", "int", "17")), list(false, false));
            var bindings = new ArrayList<>(objects(source.get("bindings"))); bindings.set(bindings.size() - 1, binding("entry", list("lam", list(map("id", "offset", "lifted", false)), partial), 1));
            for (var backend : list("ast", "bytecode")) {
                var program = program(language, with(source, "bindings", bindings), backend);
                for (long offset : new long[]{-1L, 3L, Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> call(program, offset));
                var pap = (Closure) call(program, 1L); var prefix = Objects.requireNonNull(pap.typedSupplied); var shape = prefix.getLayout();
                assertEquals(2, pap.suppliedCount); assertEquals(1, pap.arity); assertEquals(0, pap.supplied.length);
                assertFalse(prefix.getLive()); assertEquals(0, prefix.getInputMode());
                assertEquals(0L, ((ManagedAddress) shape.getObject(prefix, 0)).indexChar(0)); assertEquals(17L, shape.getLong(prefix, 1)); var bottom = (Thunk) program.entryValue("bottom");
                var result = (DataValue) Calls.target(program.hostEntryTarget(1), new Object[]{pap, new Object[]{bottom}});
                assertEquals(0L, ((ManagedAddress) result.getLayout().read(result, 0)).indexChar(0)); assertEquals(0, bottom.getState()); released(language);
            }
        });
    }
    private Expr field(Object value) { var expr = new Expr() { @Override public Object execute(VirtualFrame frame) { return value; } }; expr.setRepresentation(CoreRepresentations.parse(address)); return expr; }
    @Test void tupleAddressesKeepTheirManagedCarrierAndReleaseResultLoans() throws Exception {
        withLanguage(language -> {
            var tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(address, wide), "primReps", list("AddrRep", "IntRep"), "evaluated", true);
            var shape = new TupleShape(CoreRepresentations.parse(tuple), language); var builder = FrameDescriptor.newBuilder(); var slots = new int[4];
            for (int i = 0; i < 4; i++) slots[i] = builder.addSlot(FrameSlotKind.Illegal, "address tuple " + i, null);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build()); var literal = ManagedAddress.fromHex("4100");
            var number = new Expr() { @Override public Object execute(VirtualFrame frame) { return 17L; } @Override public long executeLong(VirtualFrame frame) { return 17L; } }; number.setRepresentation(CoreRepresentations.parse(wide));
            new TupleConstruct(shape, new Expr[]{field(literal), number}).executeTuple(frame, slots, 0); var result = shape.finish(frame, slots); assertSame(TupleComplete.INSTANCE, result);
            assertEquals(1, language.getHandoffState().get().getResults().retainedReferences()); shape.consume(frame, result, slots, 2);
            assertSame(literal, frame.getObject(slots[2])); assertEquals(17L, frame.getLong(slots[3])); released(language);
            for (var bad : list(null, 0L, new Object(), new byte[]{65}, ByteBuffer.allocateDirect(8))) {
                assertThrows(RuntimeFault.class, () -> new TupleConstruct(shape, new Expr[]{field(bad), number}).executeTuple(frame, slots, 0));
                var forged = shape.getLayout().create(); shape.getLayout().setObject(forged, 0, bad); shape.getLayout().setLong(forged, 1, 17L); assertThrows(RuntimeFault.class, () -> shape.consume(frame, forged, slots, 2));
                var pool = language.getHandoffState().get().getResults(); var loan = pool.acquire(shape.getLayout()); shape.getLayout().setObject(loan, 0, bad); shape.getLayout().setLong(loan, 1, 17L); pool.complete(loan);
                assertThrows(RuntimeFault.class, () -> shape.consume(frame, TupleComplete.INSTANCE, slots, 2)); released(language);
            }
        });
    }
    @Test void contradictoryFieldProofsRejectAndAddressSumsKeepManagedStorage() throws Exception {
        withLanguage(language -> {
            var original = constructor(true);
            for (var bad : list(with(original, "fieldTypes", list(with(address, "kind", "unknown"), wide, lazy)), with(original, "fieldTypes", list(with(address, "evaluated", false), wide, lazy)),
                    with(original, "fieldTypes", list(wide, wide, lazy)), with(original, "fieldLifted", list(true, false, true)), with(without(original, "fieldTypes"), "fieldLifted", list(true, false, true)), with(without(original, "fieldTypes"), "fieldLifted", "invalid"))) {
                assertThrows(RuntimeFault.class, () -> new CoreFields(bad));
                for (var backend : list("ast", "bytecode")) assertThrows(RuntimeFault.class, () -> program(language, with(module(true, false), "constructors", list(bad)), backend));
            }
            var tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(address), "primReps", list("AddrRep"), "evaluated", true); assertTrue(CoreRepresentations.parse(tuple).isTuple());
            // GHC shares a WordSlot; JVM transport retains a separate managed-address field.
            var sum = map("kind", "unknown", "aggregate", "unboxed-sum", "alternatives", list(address, wide), "primReps", list("WordRep", "WordRep"), "tagSlot", 0, "alternativeSlots", list(list(1), list(1)), "evaluated", true);
            var sumProof = CoreRepresentations.parse(sum); assertTrue(sumProof.isSum());
            var transport = SumShape.transport(sumProof);
            assertEquals(list(list("WordRep"), list("WordRep"), list("AddrRep")),
                transport.getFields().stream().map(CoreRepresentation::getPrimReps).toList());
            assertEquals(list("WordRep", "WordRep"), sumProof.getPrimReps());
            assertEquals(list(list(1), list(1)), sumProof.getAlternativeSlots());
            assertEquals(list(list(2), list(1)), transport.getProjections());
            assertEquals(ManagedAddress.class, transport.getFields().get(2).referenceCarrier());
            assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(with(sum,
                "alternatives", list(with(address, "evaluated", false), wide))));
            var state = map("kind", "void", "primReps", list(), "evaluated", true); var empty = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(), "primReps", list(), "evaluated", true);
            var tagOnly = with(sum, "alternatives", list(state, empty), "primReps", list("WordRep"), "alternativeSlots", list(list(), list())); assertTrue(CoreRepresentations.parse(tagOnly).isSum());
            var sumField = map("id", "SumField", "kind", "boxed", "arity", 1, "fieldReps", list(list("WordRep")), "fieldTypes", list(tagOnly), "fieldLifted", list(false), "strictFields", list(false));
            var fields = new CoreFields(sumField); assertTrue(fields.getHasAggregates()); assertEquals(1, fields.getLogicalProofs().length); assertTrue(fields.getLogicalProofs()[0].isSum());
            assertArrayEquals(new String[]{"WordRep"}, fields.getStorage()); assertArrayEquals(new int[]{0, 1}, fields.getOffsets()); assertThrows(RuntimeFault.class, () -> new CoreFields(with(sumField, "fieldReps", list(list("IntRep")))));
        });
    }
}
