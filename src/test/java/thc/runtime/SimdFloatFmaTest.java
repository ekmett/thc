// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.DoubleVector;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Four floating vector shapes share one native executable and pre/post Core export. */
@SuppressWarnings("unchecked")
class SimdFloatFmaTest {
    private final File root = new File(System.getProperty("thc.projectRoot")), directory = new File(root, "build/simd-floatx4-fma");
    private final List<String> names = List.of("addCase", "subCase", "negAddCase", "negSubCase");
    private record FloatLane(float x, float y, float z) {}
    private record DoubleLane(double x, double y, double z) {}
    private long bits(float value) { return Float.floatToRawIntBits(value) & 0xffff_ffffL; }
    private void same(long expected, long actual, String label) {
        if (Float.isNaN(Float.intBitsToFloat((int) expected))) assertTrue(Float.isNaN(Float.intBitsToFloat((int) actual)), label + " NaN (payload unspecified)");
        else assertEquals(expected, actual, label);
    }
    private List<FloatLane> lanes(List<Long> input) {
        float x = Float.intBitsToFloat(input.get(0).intValue()), y = Float.intBitsToFloat(input.get(1).intValue()), z = Float.intBitsToFloat(input.get(2).intValue());
        return List.of(new FloatLane(x, y, z), new FloatLane(y, z, x), new FloatLane(z, x, y), new FloatLane(x, y, -z));
    }
    private long model(int operation, FloatLane lane) { return bits(Math.fma(operation >= 2 ? -lane.x : lane.x, lane.y, (operation & 1) != 0 ? -lane.z : lane.z)); }
    @Test void singleRoundingAndOperandNegationPreserveCancellationAndSignedZero() {
        float x = Float.intBitsToFloat(0x3f800001), y = Float.intBitsToFloat(0x3f7ffffe); assertEquals(0.0f, x * y - 1f);
        assertEquals(bits(-Math.scalb(1.0f, -46)), bits(BytecodeRoot.VectorFloatFused.apply(0, FloatVector.broadcast(FloatVector.SPECIES_128, x),
            FloatVector.broadcast(FloatVector.SPECIES_128, y), FloatVector.broadcast(FloatVector.SPECIES_128, -1f)).lane(0)));
        for (int operation = 0; operation <= 3; operation++) {
            var a = FloatVector.broadcast(FloatVector.SPECIES_128, 0f).withLane(1, -0f).withLane(2, 2f).withLane(3, -2f);
            var b = FloatVector.broadcast(FloatVector.SPECIES_128, 0f).withLane(1, 0f).withLane(2, 3f).withLane(3, 3f);
            var c = FloatVector.broadcast(FloatVector.SPECIES_128, 0f).withLane(1, -0f).withLane(2, 4f).withLane(3, -4f);
            var actual = BytecodeRoot.VectorFloatFused.apply(operation, a, b, c);
            for (int lane = 0; lane <= 3; lane++) same(model(operation, new FloatLane(a.lane(lane), b.lane(lane), c.lane(lane))), bits(actual.lane(lane)), operation + "/" + lane);
        }
        assertEquals(0L, bits(BytecodeRoot.VectorFloatFused.apply(2, FloatVector.broadcast(FloatVector.SPECIES_128, 0f),
            FloatVector.broadcast(FloatVector.SPECIES_128, 0f), FloatVector.broadcast(FloatVector.SPECIES_128, 0f)).lane(0)), "Negating the rounded answer would give -0");
        var wide = BytecodeRoot.VectorFloat8Fused.apply(0, FloatVector.broadcast(FloatVector.SPECIES_256, x), FloatVector.broadcast(FloatVector.SPECIES_256, y), FloatVector.broadcast(FloatVector.SPECIES_256, -1f));
        for (int i = 0; i < 8; i++) assertEquals(bits(-Math.scalb(1.0f, -46)), bits(wide.lane(i)));
        var zero = BytecodeRoot.VectorFloat8Fused.apply(2, FloatVector.broadcast(FloatVector.SPECIES_256, 0f), FloatVector.broadcast(FloatVector.SPECIES_256, 0f), FloatVector.broadcast(FloatVector.SPECIES_256, 0f));
        for (int i = 0; i < 8; i++) assertEquals(0L, bits(zero.lane(i)), "Negating the rounded answer would give -0");
    }
    private record ProofCase(Collection<String> names, CoreRepresentation proof, CoreRepresentation wrong) {}
    @Test void exactThreeVectorInputsAndResultAreRequired() {
        for (var c : List.of(new ProofCase(CoreVectors.fusedFloat, CoreVectors.proofFloat, CoreVectors.proofDouble),
            new ProofCase(CoreVectors.fusedFloat8, GeneratedVectors.proofFloatX8, CoreVectors.proofFloat),
            new ProofCase(CoreVectors.fusedDouble4, GeneratedVectors.proofDoubleX4, CoreVectors.proofDouble),
            new ProofCase(CoreVectors.fusedDouble, CoreVectors.proofDouble, CoreVectors.proofFloat))) for (var name : c.names) {
            var good = Collections.nCopies(3, c.proof); CoreVectors.validate(name, good, c.proof);
            for (int index = 0; index < good.size(); index++) {
                var wrong = new ArrayList<>(good); wrong.set(index, c.wrong); assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, wrong, c.proof));
            }
            for (int count : List.of(2, 4)) assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, Collections.nCopies(count, c.proof), c.proof));
            assertThrows(RuntimeFault.class, () -> CoreVectors.validate(name, good, c.wrong)); assertThrows(RuntimeFault.class, () -> CoreVectors.validateFlags(List.of(false, true, false)));
        }
    }
    @Test void doubleSingleRoundingAndSignVariantsKeepIeeeControls() {
        double x = Double.longBitsToDouble(0x3ff0000000000001L), y = Double.longBitsToDouble(0x3feffffffffffffeL); assertEquals(0.0, x * y - 1.0);
        assertEquals(Double.doubleToRawLongBits(-Math.scalb(1.0, -104)), Double.doubleToRawLongBits(BytecodeRoot.VectorDoubleFused.apply(0,
            DoubleVector.broadcast(DoubleVector.SPECIES_128, x), DoubleVector.broadcast(DoubleVector.SPECIES_128, y), DoubleVector.broadcast(DoubleVector.SPECIES_128, -1.0)).lane(0)));
        var controls = List.of(new DoubleLane(0.0, 0.0, 0.0), new DoubleLane(-0.0, 0.0, -0.0), new DoubleLane(2.0, 3.0, 4.0),
            new DoubleLane(Double.POSITIVE_INFINITY, 0.0, 1.0), new DoubleLane(Double.NaN, 1.0, 0.0), new DoubleLane(Double.MAX_VALUE, 2.0, -Double.MAX_VALUE));
        for (int operation = 0; operation <= 3; operation++) for (var lane : controls) {
            double a = lane.x, b = lane.y, c = lane.z, expected = Math.fma(operation >= 2 ? -a : a, b, (operation & 1) != 0 ? -c : c);
            double actual = BytecodeRoot.VectorDoubleFused.apply(operation, DoubleVector.broadcast(DoubleVector.SPECIES_128, a),
                DoubleVector.broadcast(DoubleVector.SPECIES_128, b), DoubleVector.broadcast(DoubleVector.SPECIES_128, c)).lane(0);
            if (Double.isNaN(expected)) assertTrue(Double.isNaN(actual), "NaN payload is unspecified");
            else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), operation + "/" + a + "/" + b + "/" + c);
        }
        assertEquals(0L, Double.doubleToRawLongBits(BytecodeRoot.VectorDoubleFused.apply(2, DoubleVector.broadcast(DoubleVector.SPECIES_128, 0.0),
            DoubleVector.broadcast(DoubleVector.SPECIES_128, 0.0), DoubleVector.broadcast(DoubleVector.SPECIES_128, 0.0)).lane(0)));
        var wide = BytecodeRoot.VectorDouble4Fused.apply(0, DoubleVector.broadcast(DoubleVector.SPECIES_256, x), DoubleVector.broadcast(DoubleVector.SPECIES_256, y), DoubleVector.broadcast(DoubleVector.SPECIES_256, -1.0));
        for (int i = 0; i < 4; i++) assertEquals(Double.doubleToRawLongBits(-Math.scalb(1.0, -104)), Double.doubleToRawLongBits(wide.lane(i)));
        for (int operation = 0; operation <= 3; operation++) for (var lane : controls) {
            double a = lane.x, b = lane.y, c = lane.z, expected = Math.fma(operation >= 2 ? -a : a, b, (operation & 1) != 0 ? -c : c);
            var actual = BytecodeRoot.VectorDouble4Fused.apply(operation, DoubleVector.broadcast(DoubleVector.SPECIES_256, a), DoubleVector.broadcast(DoubleVector.SPECIES_256, b), DoubleVector.broadcast(DoubleVector.SPECIES_256, c));
            for (int i = 0; i < 4; i++) {
                if (Double.isNaN(expected)) assertTrue(Double.isNaN(actual.lane(i)), "NaN payload is unspecified");
                else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual.lane(i)), operation + "/" + a + "/" + b + "/" + c);
            }
        }
    }
    @Test void genuineCoreAndNativeLaneBitsSurviveBothCompiledBackends() throws Exception {
        checkGenuineCoreAndNativeLaneBits(false, 4); checkGenuineCoreAndNativeLaneBits(true, 2);
        checkGenuineCoreAndNativeLaneBits(false, 8); checkGenuineCoreAndNativeLaneBits(true, 4);
    }
    private long bits(boolean doubles, double value) { return doubles ? Double.doubleToRawLongBits(value) : bits((float) value); }
    private void same(boolean doubles, long expected, long actual, String label) {
        if (!doubles) same(expected, actual, label);
        else if (Double.isNaN(Double.longBitsToDouble(expected))) assertTrue(Double.isNaN(Double.longBitsToDouble(actual)), label + " NaN (payload unspecified)");
        else assertEquals(expected, actual, label);
    }
    private List<DoubleLane> lanes(boolean doubles, int laneCount, List<Long> input) {
        boolean vector512 = doubles ? laneCount == 8 : laneCount == 16;
        var all = new ArrayList<DoubleLane>();
        if (!doubles) {
            var base = lanes(input); var first = base.getFirst(); float x = first.x, y = first.y, z = first.z;
            var floats = new ArrayList<>(base);
            if (laneCount >= 8) floats.addAll(List.of(new FloatLane(-x, y, z), new FloatLane(x, -y, z), new FloatLane(-y, z, -x), new FloatLane(z, -x, -y)));
            if (vector512) { var reversed = new ArrayList<FloatLane>(); for (var lane : floats) reversed.add(new FloatLane(-lane.x, -lane.y, -lane.z)); floats.addAll(reversed); }
            for (var lane : floats) all.add(new DoubleLane(lane.x, lane.y, lane.z));
        } else {
            double x = Double.longBitsToDouble(input.get(0)), y = Double.longBitsToDouble(input.get(1)), z = Double.longBitsToDouble(input.get(2));
            all.add(new DoubleLane(x, y, z)); all.add(new DoubleLane(y, z, -x));
            if (laneCount >= 4) all.addAll(List.of(new DoubleLane(z, x, y), new DoubleLane(-x, y, -z)));
            if (vector512) { var reversed = new ArrayList<DoubleLane>(); for (var lane : all) reversed.add(new DoubleLane(-lane.x, -lane.y, -lane.z)); all.addAll(reversed); }
        }
        return all;
    }
    private long model(boolean doubles, int operation, DoubleLane lane) {
        return doubles ? bits(true, Math.fma(operation >= 2 ? -lane.x : lane.x, lane.y, (operation & 1) != 0 ? -lane.z : lane.z)) :
            model(operation, new FloatLane((float) lane.x, (float) lane.y, (float) lane.z));
    }
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath())); }
    private List<String> request(String name, List<Long> input, int lane) {
        var result = new ArrayList<String>(); result.add(name); for (long value : input) result.add(Long.toUnsignedString(value)); result.add(Integer.toString(lane)); return result;
    }
    private boolean compiled(CallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<CallTarget> activeEntries(RootCallTarget host, RootCallTarget entry) {
        var targets = new LinkedHashSet<CallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)) if (call.getCallTarget() == entry) targets.add(call.getCurrentCallTarget());
        if (targets.isEmpty()) targets.add(entry); return new ArrayList<>(targets);
    }
    private void check(boolean doubles, int laneCount, List<List<String>> nativeRows, String name, int operation, Value function, List<Long> input, int lane, String label) {
        long expected;
        if (nativeRows == null) expected = model(doubles, operation, lanes(doubles, laneCount, input).get(lane));
        else {
            var key = request(name, input, lane); List<String> match = null;
            for (var row : nativeRows) if (row.subList(0, 5).equals(key)) { match = row; break; }
            expected = Long.parseUnsignedLong(Objects.requireNonNull(match).getLast());
        }
        same(doubles, expected, function.execute(input.get(0), input.get(1), input.get(2), (long) lane).asLong(), label + "/" + input + "/" + lane);
    }
    private Object argumentVector(boolean doubles, int laneCount, List<DoubleLane> laneInputs, int argument) {
        if (doubles) {
            var species = switch (laneCount) { case 2 -> DoubleVector.SPECIES_128; case 4 -> DoubleVector.SPECIES_256; default -> DoubleVector.SPECIES_512; };
            var values = new double[laneCount];
            for (int i = 0; i < laneCount; i++) { var lane = laneInputs.get(i); values[i] = argument == 0 ? lane.x : argument == 1 ? lane.y : lane.z; }
            return DoubleVector.fromArray(species, values, 0);
        }
        var species = switch (laneCount) { case 4 -> FloatVector.SPECIES_128; case 8 -> FloatVector.SPECIES_256; default -> FloatVector.SPECIES_512; };
        var values = new float[laneCount];
        for (int i = 0; i < laneCount; i++) { var lane = laneInputs.get(i); values[i] = (float) (argument == 0 ? lane.x : argument == 1 ? lane.y : lane.z); }
        return FloatVector.fromArray(species, values, 0);
    }
    private void workerCall(boolean doubles, int laneCount, int operation, List<Long> input, Language language, RootCallTarget worker,
                            TypedInputLayout inputLayout, TupleShape resultShape, String label) {
        var laneInputs = lanes(doubles, laneCount, input); var arguments = new ArrayList<Object>();
        for (int argument = 0; argument < 3; argument++) arguments.add(argumentVector(doubles, laneCount, laneInputs, argument));
        var loan = language.getHandoffState().get().getArguments().acquire(inputLayout.getPacket()); loan.setInputMode(1); inputLayout.getPacket().setLong(loan, 0, 0L);
        for (int argument = 0; argument < arguments.size(); argument++) inputLayout.getPacket().setObject(loan, inputLayout.getHeader() + inputLayout.getLogical().offset(argument), arguments.get(argument));
        var result = TupleResults.ownedTupleResult(TypedInputs.invokeTypedInput(worker, loan, args -> Calls.target(worker, args)), resultShape);
        var raw = resultShape.getLayout().getObject(result, 0);
        if (doubles) {
            var vector = assertInstanceOf(DoubleVector.class, raw);
            assertEquals(switch (laneCount) { case 2 -> DoubleVector.SPECIES_128; case 4 -> DoubleVector.SPECIES_256; default -> DoubleVector.SPECIES_512; }, vector.species());
            assertEquals(laneCount, vector.length());
            for (int lane = 0; lane < laneCount; lane++) same(true, model(true, operation, laneInputs.get(lane)), bits(true, vector.lane(lane)), label + " worker lane " + lane);
        } else {
            var vector = assertInstanceOf(FloatVector.class, raw);
            assertEquals(switch (laneCount) { case 4 -> FloatVector.SPECIES_128; case 8 -> FloatVector.SPECIES_256; default -> FloatVector.SPECIES_512; }, vector.species());
            assertEquals(laneCount, vector.length());
            for (int lane = 0; lane < laneCount; lane++) same(false, model(false, operation, laneInputs.get(lane)), bits(false, vector.lane(lane)), label + " worker lane " + lane);
        }
    }
    void checkGenuineCoreAndNativeLaneBits(boolean doubles, int laneCount) throws Exception {
        boolean vector512 = doubles ? laneCount == 8 : laneCount == 16, doubleWide = doubles && laneCount >= 4, wide = !doubles && laneCount >= 8;
        var directory = vector512 ? new File(root, "build/simd-wide-floating-fma") : this.directory; var moduleName = vector512 ? "SimdWideFloatFma" : "SimdFloatFma"; int nativeCount = vector512 ? 2112 : 1584;
        var names = vector512 && doubles ? List.of("doubleHugeAddCase", "doubleHugeSubCase", "doubleHugeNegAddCase", "doubleHugeNegSubCase") :
            vector512 ? List.of("hugeAddCase", "hugeSubCase", "hugeNegAddCase", "hugeNegSubCase") :
            doubleWide ? List.of("doubleWideAddCase", "doubleWideSubCase", "doubleWideNegAddCase", "doubleWideNegSubCase") :
            doubles ? List.of("doubleAddCase", "doubleSubCase", "doubleNegAddCase", "doubleNegSubCase") :
            wide ? List.of("wideAddCase", "wideSubCase", "wideNegAddCase", "wideNegSubCase") : this.names;
        var manifest = json(new File(directory, "manifest.json")); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc"));
        boolean exportOnly = List.of("aarch64", "arm64").contains(System.getProperty("os.arch"));
        assertEquals(vector512 || exportOnly ? List.of("pre") : List.of("pre", "post"), manifest.get("stages"));
        assertEquals(exportOnly && !vector512 ? null : (long) nativeCount, manifest.get("nativeRows"), "Native preparation cannot silently downgrade");
        assertEquals(vector512 ? exportOnly ? List.of() : List.of("-mfma") : List.of("-fllvm", "-mavx2", "-mfma"), manifest.get("nativeFlags"));
        if (vector512) { assertEquals("native-ghc-scalar-fma-lanes", manifest.get("oracleKind")); assertEquals(false, manifest.get("nativeVectorParity"), "Native 512-bit instruction parity remains unproved"); }
        var hashes = new LinkedHashMap<>((Map<String, String>) manifest.get("inputHashes")); hashes.putAll((Map<String, String>) manifest.get("artifactHashes"));
        for (var item : hashes.entrySet()) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), hash, "Stale shared SIMD fused fixture " + item.getKey());
        }
        assertEquals(names, manifest.get(vector512 && doubles ? "doubleHugeEntries" : vector512 ? "hugeEntries" : doubleWide ? "doubleWideEntries" : doubles ? "doubleEntries" : wide ? "wideEntries" : "entries"));
        for (var stage : (List<String>) manifest.get("stages")) {
            var audit = json(new File(directory, stage + "-audit.json")); assertEquals(true, audit.get("accepted"), stage + " canonical audit");
            assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            var doubleAudit = json(new File(directory, stage + "-double-audit.json")); assertEquals(true, doubleAudit.get("accepted"), stage + " canonical double-vector audit");
            assertEquals(List.of(), doubleAudit.get("issues")); assertEquals(List.of(), doubleAudit.get("missingGlobals"));
        }
        var inputs = new ArrayList<List<Long>>();
        if (doubles) for (var row : (List<List<String>>) manifest.get("doubleInputs")) { var values = new ArrayList<Long>(); for (var value : row) values.add(Long.parseUnsignedLong(value)); inputs.add(values); }
        else for (var row : (List<List<Number>>) manifest.get("inputs")) { var values = new ArrayList<Long>(); for (var value : row) values.add(value.longValue()); inputs.add(values); }
        assertEquals(22, inputs.size());
        List<List<String>> nativeRows = null;
        if (manifest.get("nativeRows") != null) {
            var lines = Files.readAllLines(new File(directory, "oracle.txt").toPath()); assertEquals(nativeCount, lines.size()); nativeRows = new ArrayList<>();
            for (var line : lines) { var row = Arrays.asList(line.split(" ", -1)); if (names.contains(row.getFirst())) nativeRows.add(row); }
        }
        var expectedRequests = new ArrayList<List<String>>(); for (var name : names) for (var input : inputs) for (int lane = 0; lane < laneCount; lane++) expectedRequests.add(request(name, input, lane));
        if (nativeRows != null) {
            var actualRequests = new ArrayList<List<String>>(); for (var row : nativeRows) actualRequests.add(row.subList(0, 5));
            assertEquals(expectedRequests, actualRequests); assertEquals(4 * 22 * laneCount, nativeRows.size());
            for (var row : nativeRows) {
                var input = new ArrayList<Long>(); for (var value : row.subList(1, 4)) input.add(Long.parseUnsignedLong(value));
                same(doubles, model(doubles, names.indexOf(row.getFirst()), lanes(doubles, laneCount, input).get(Integer.parseInt(row.get(4)))),
                    Long.parseUnsignedLong(row.get(5)), "Native " + row.subList(0, 5));
            }
        }
        for (var stage : (List<String>) manifest.get("stages")) for (var backend : List.of("ast", "bytecode")) for (boolean inlining : List.of(false, true))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var source = json(new File(directory, stage + "-core/" + moduleName + ".json"));
                    for (int operation = 0; operation < names.size(); operation++) {
                        var name = names.get(operation); var linked = new LinkedHashMap<>(CoreModules.reachable(source, name)); linked.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var label = stage + "/" + backend + "/inlining=" + inlining + "/" + name; var host = program.hostEntryTarget(4); var function = context.asValue(new EntryValue(program, name, 4));
                        for (var input : inputs) for (int lane = 0; lane < laneCount; lane++) check(doubles, laneCount, nativeRows, name, operation, function, input, lane, label);
                        // These entries may be CAFs returning closures. Snapshot the resolved function, not its initial thunk root.
                        var entry = program.entryTarget(name); var installedEntries = activeEntries(host, entry);
                        assertTrue(function.invokeMember("compile").asBoolean()); assertTrue(compiled(host), label + " host installation");
                        for (var target : installedEntries) assertTrue(compiled(target), label + " guest installation");
                        for (var input : inputs) for (int lane = 0; lane < laneCount; lane++) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(doubles, laneCount, nativeRows, name, operation, function, input, lane, label);
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, "Every first-installed lane call must enter compiled code");
                            assertSame(entry, program.entryTarget(name), label + " entry identity"); assertSame(host, program.hostEntryTarget(4), label + " host identity");
                            assertEquals(installedEntries, activeEntries(host, entry), label + " installed entry identities"); assertTrue(compiled(host), label + " host retained");
                            for (var target : installedEntries) assertTrue(compiled(target), label + " guest retained");
                            assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); assertNull(language.getHandoffState().get().getPending());
                        }
                        // Compile the genuine vector worker itself as well: a compiled scalar wrapper alone could hide an interpreted FMA.
                        var workerName = name.substring(0, name.length() - "Case".length()) + "Worker"; var worker = program.entryTarget(workerName); var workerRoot = (GuestRoot) worker.getRootNode();
                        var inputLayout = Objects.requireNonNull(workerRoot.getTypedInput()); var resultShape = Objects.requireNonNull(workerRoot.getTupleResult());
                        assertEquals(3, inputLayout.getLogical().getLogicalArity()); assertEquals(3, inputLayout.getLogical().getPhysicalArity());
                        assertEquals(inputLayout.getHeader() + 3, inputLayout.getPacket().getReps().size()); assertEquals(1, resultShape.getWidth());
                        assertEquals(1, resultShape.getLayout().getReps().size()); assertTrue(resultShape.getLayout().isObject(0));
                        assertEquals(vector512 && doubles ? GeneratedVectors.vectorDoubleX8 : vector512 ? GeneratedVectors.vectorFloatX16 :
                            doubleWide ? GeneratedVectors.vectorDoubleX4 : doubles ? CoreVector.DOUBLEX2 : wide ? GeneratedVectors.vectorFloatX8 : CoreVector.FLOATX4, resultShape.getProof().getVector());
                        for (int argument = 0; argument < 3; argument++) {
                            assertEquals(resultShape.getProof().getVector(), inputLayout.getLogical().proof(argument).getVector()); assertEquals(argument, inputLayout.getLogical().offset(argument));
                            assertTrue(inputLayout.getPacket().isObject(inputLayout.getHeader() + argument));
                        }
                        for (var input : inputs) workerCall(doubles, laneCount, operation, input, language, worker, inputLayout, resultShape, label);
                        worker.getClass().getMethod("compile", boolean.class).invoke(worker, true); assertTrue(compiled(worker), label + " worker installation");
                        for (var input : inputs) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); workerCall(doubles, laneCount, operation, input, language, worker, inputLayout, resultShape, label);
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertSame(worker, program.entryTarget(workerName), label + " worker identity");
                            assertTrue(compiled(worker), label + " worker retained"); assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); assertNull(language.getHandoffState().get().getPending());
                        }
                    }
                } finally { context.leave(); }
            }
    }
}
