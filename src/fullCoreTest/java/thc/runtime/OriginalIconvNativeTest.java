// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class OriginalIconvNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Map<String, Object> document(String name) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/original-iconv/" + name).toPath())); }
    private Context context(boolean nativeAccess, boolean inlining) { return Context.newBuilder("thc", "llvm").allowIO(IOAccess.ALL).allowNativeAccess(nativeAccess).allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private ManagedAddress string(String value) { var bytes = value.getBytes(StandardCharsets.US_ASCII); return ManagedAddress.fromByteArray(Arrays.copyOf(bytes, bytes.length + 1)); }
    private ManagedAddress pointer(ManagedAddress value) { var result = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8, false)); result.writeAddressElementIndex(0, value); return result; }
    private ManagedAddress count(long value) { var result = ManagedAddress.fromByteArray(new byte[8]); result.writeNativeScalar(0, 8, value); return result; }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result);
        result.add(target);
    }
    private List<RootCallTarget> targets(RootCallTarget entry) { var result = new ArrayList<RootCallTarget>(); visit(entry, Collections.newSetFromMap(new IdentityHashMap<>()), result); return result; }
    private final class Session {
        final Map<String, ExecutableProgram> programs = new LinkedHashMap<>(); final Map<String, RootCallTarget> entries = new LinkedHashMap<>();
        final Map<String, List<RootCallTarget>> active = new HashMap<>(); final String label; boolean compiled;
        Session(Language language, Map<String, Object> module, List<String> names, String backend, String label) {
            this.label = label;
            for (var name : names) { var linked = new LinkedHashMap<>(CoreModules.reachable(module, name)); linked.put("instrument", true); ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); programs.put(name, p); entries.put(name, p.entryTarget(name)); }
        }
        Object call(String name, Object... args) throws Exception {
            var program = programs.get(name); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var actuals = new Object[args.length + 1]; actuals[0] = 0L; System.arraycopy(args, 0, actuals, 1, args.length);
            var result = Calls.target(entries.get(name), actuals);
            if (compiled) { int count = 0; for (var target : active.get(name)) if (target.getRootNode() instanceof GuestRoot) count++; assertEquals(before + count, ((Number) program.diagnostics().get("compiledEntries")).longValue(), "first compiled " + label + "/" + name); for (var target : active.get(name)) valid(target); }
            return result;
        }
        void exercise(Map<String, Object> oracle, Language.State state) throws Exception {
            var locale = (ManagedAddress) call("originalLocale", 0L); assertEquals(oracle.get("locale"), locale.utf8()); assertTrue(locale.sameLocation((ManagedAddress) call("originalLocale", 0L))); assertThrows(RuntimeFault.class, () -> locale.writeWord8(0, 0));
            for (var row : (List<Map<String, Object>>) oracle.get("rows")) {
                long id = (Long) call("originalIconvOpen", string((String) row.get("to")), string((String) row.get("from"))); assertTrue(id > 0);
                for (var stage : List.of("first", "continued", "smallReset", "reset")) {
                    boolean reset = stage.equals("smallReset") || stage.equals("reset"); var raw = (List<Number>) row.get(stage.equals("first") ? "input" : "continuationInput"); var inputBytes = new byte[raw.size()]; for (int i = 0; i < raw.size(); i++) inputBytes[i] = raw.get(i).byteValue();
                    var input = ManagedAddress.fromByteArray(inputBytes); var inputCell = pointer(input); var inputCount = count(inputBytes.length); var expected = (Map<String, Object>) row.get(stage);
                    int capacity = stage.equals("first") ? ((Number) row.get("capacity")).intValue() : stage.equals("smallReset") ? 2 : 16; var bytes = new byte[capacity + 2]; Arrays.fill(bytes, (byte) 0xa5); var output = ManagedAddress.fromByteArray(bytes).plus(1); var outputCell = pointer(output); var outputCount = count(capacity);
                    state.getStdio().nativeError(123); assertEquals(expected.get("result"), call("originalIconv", id, reset ? ManagedAddress.nullAddress() : inputCell, inputCount, outputCell, outputCount), row.get("label").toString());
                    long error = (Long) expected.get("errno"); assertEquals(error == 0L ? 123L : error, state.getStdio().errno(), stage + " sticky errno");
                    if (!reset) { long consumed = (Long) expected.get("consumed"); assertTrue(inputCell.readAddressElementIndex(0).sameLocation(input.plus(consumed))); assertEquals(inputBytes.length - consumed, ManagedAddressRead.WORD64.read(inputCount, 0)); }
                    long produced = (Long) expected.get("produced"); assertTrue(outputCell.readAddressElementIndex(0).sameLocation(output.plus(produced))); assertEquals(capacity - produced, ManagedAddressRead.WORD64.read(outputCount, 0)); assertEquals((byte) 0xa5, bytes[0]); assertEquals((byte) 0xa5, bytes[bytes.length - 1]);
                    var observed = new ArrayList<Long>(); for (int i = 1; i <= capacity; i++) observed.add((long) (bytes[i] & 255)); assertEquals(expected.get("bytes"), observed);
                }
                assertEquals(row.get("closed"), call("originalIconvClose", id)); assertEquals(0, state.getIconv().liveHandles());
            }
            assertEquals(-1L, call("originalIconvOpen", string("THC-invalid-encoding"), string("UTF-8"))); assertEquals(oracle.get("missingErrno"), state.getStdio().errno());
        }
    }
    @Test public void actualOriginalImportsMatchNativeIncludingFirstCompiledCalls() throws Exception {
        var oracle = document("oracle.json"); var manifest = document("manifest.json"); assertEquals(10L, manifest.get("nativeRows"));
        for (var field : List.of("inputHashes", "artifactHashes")) OriginalStdioChecks.hashes(root, manifest.get(field), field.equals("inputHashes") ? Set.of("compiler/test-fixtures/OriginalIconvAudit.hs", "compiler/test-fixtures/OriginalIconvAuditNative.hs") : Set.of("build/original-iconv/OriginalIconvAudit.json", "build/original-iconv/oracle.json"), null);
        var declarations = document("declarations.json"); assertEquals(false, declarations.get("completeModule")); assertEquals(false, declarations.get("installedArtifactsHashed")); assertEquals(true, declarations.get("typeEqualityChecked")); assertEquals(true, declarations.get("originalIdentityChecked")); var originals = OriginalStdioChecks.foreignCalls(declarations);
        for (var stage : List.of("pre", "post")) {
            var module = document(stage.equals("pre") ? "PreIconvAudit.json" : "OriginalIconvAudit.json"); assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", module.get("boundary")); var actual = new ArrayList<List<Object>>();
            for (var entry : (List<String>) manifest.get("entries")) actual.addAll(OriginalStdioChecks.foreignCalls(CoreModules.reachable(module, entry))); assertEquals(4, actual.size());
            for (var call : actual) {
                var descriptor = ((Map<?, ?>) call.getLast()).get("foreignCall"); var candidates = new ArrayList<List<Object>>(); for (var original : originals) if (Objects.equals(((Map<?, ?>) original.getLast()).get("foreignCall"), descriptor)) candidates.add(original); assertFalse(candidates.isEmpty(), "exact original foreign descriptor");
                String originalPrefix = "ghc-internal:GHC.Internal.IO.Encoding.Iconv.", adaptedPrefix = "main:OriginalIconvAudit."; var head = (List<?>) call.get(1); assertTrue(((String) head.get(1)).startsWith(adaptedPrefix));
                // Private Ids keep exact Unique/type; serialization changes only the consumer scope.
                boolean matched = false; for (var candidate : candidates) { var original = (List<?>) candidate.get(1); var name = (String) original.get(1); if (name.startsWith(originalPrefix) && Objects.equals(original.get(0), head.get(0)) && Objects.equals(original.get(2), head.get(2)) && name.substring(originalPrefix.length()).equals(((String) head.get(1)).substring(adaptedPrefix.length()))) matched = true; } assertTrue(matched, "original private FCallId Unique/type");
            }
            for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[] {false, true}) try (var context = context(true, inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var session = new Session(language, module, (List<String>) manifest.get("entries"), backend, stage + "/" + backend + "/" + inlining); session.exercise(oracle, Language.currentState(null));
                    for (var entry : session.entries.entrySet()) { var active = targets(entry.getValue()); session.active.put(entry.getKey(), active); for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); } }
                    session.compiled = true; session.exercise(oracle, Language.currentState(null)); // The very first installed execution is checked.
                    for (var p : session.programs.values()) assertEquals(0L, p.diagnostics().get("unsupportedTraps")); var pools = language.getHandoffState().get(); assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth()); assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
                } finally { context.leave(); }
            }
        }
    }
    @Test public void malformedOriginalDescriptorsAreRejectedBeforeEffects() throws Exception {
        var manifest = document("manifest.json"); assertEquals(44L, manifest.get("negativeAudits")); var bad = new ArrayList<File>(); for (var file : Objects.requireNonNull(new File(root, "build/original-iconv/negative").listFiles())) if (file.getName().endsWith(".json") && !file.getName().endsWith(".audit.json")) bad.add(file); assertEquals(11, bad.size());
        try (var context = context(false, true)) { context.initialize("thc"); context.enter(); try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); for (var file : bad) for (var name : (List<String>) manifest.get("entries")) { var module = (Map<String, Object>) Json.parse(Files.readString(file.toPath())); var linked = CoreModules.reachable(module, name); assertThrows(RuntimeFault.class, () -> new Program(language, linked), file + "/" + name + " AST"); assertThrows(RuntimeFault.class, () -> new BytecodeProgram(language, linked), file + "/" + name + " BC"); } assertEquals(0, Language.currentState(null).getIconv().liveHandles()); } finally { context.leave(); } }
    }
    private void bad(Executable action, ManagedAddress input, ManagedAddress inputCell, ManagedAddress inputCount, ManagedAddress output, ManagedAddress outputCell, ManagedAddress outputCount) {
        assertThrows(RuntimeFault.class, action); assertTrue(inputCell.readAddressElementIndex(0).sameLocation(input)); assertTrue(outputCell.readAddressElementIndex(0).sameLocation(output)); assertEquals(2L, ManagedAddressRead.WORD64.read(inputCount, 0)); assertEquals(4L, ManagedAddressRead.WORD64.read(outputCount, 0)); var bytes = new ArrayList<Long>(); for (long i = 0; i <= 3; i++) bytes.add(output.readWord8(i)); assertEquals(List.of(85L, 85L, 85L, 85L), bytes);
    }
    @Test public void ownershipBoundsResetAndNativeAccessAreChecked() {
        try (var context = context(false, true)) { context.initialize("thc"); context.enter(); try { assertThrows(RuntimeFault.class, () -> Language.currentState(null).getIconv().open(string("UTF-8"), string("UTF-8"))); } finally { context.leave(); } }
        long escaped; ManagedIconv disposed;
        try (var context = context(true, true)) { context.initialize("thc"); context.enter(); try {
            var iconv = Language.currentState(null).getIconv(); disposed = iconv; escaped = iconv.open(string("UTF-8"), string("UTF-8"));
            var input = ManagedAddress.fromByteArray(new byte[] {65, 66}); var inputCell = pointer(input); var bytes = new byte[4]; Arrays.fill(bytes, (byte) 0x55); var output = ManagedAddress.fromByteArray(bytes); var outputCell = pointer(output); var inputCount = count(2); var outputCount = count(4);
            var actions = List.<Executable>of(
                () -> iconv.convert(escaped, inputCell, count(-1), outputCell, outputCount), () -> iconv.convert(escaped, inputCell, count(3), outputCell, outputCount), () -> iconv.convert(escaped, inputCell, inputCount, outputCell, count(5)), () -> iconv.convert(escaped, inputCell, inputCount, outputCell, inputCount), () -> iconv.convert(escaped, inputCell, inputCount, pointer(input), count(2)), () -> iconv.convert(escaped, inputCell, inputCount, pointer(ManagedAddress.fromHex("00000000")), outputCount), () -> iconv.convert(escaped, inputCell, inputCount, pointer(inputCell), outputCount), () -> iconv.convert(escaped, inputCell, inputCount, outputCell, ManagedAddress.fromByteArray(new byte[9]).plus(1)));
            for (var action : actions) bad(action, input, inputCell, inputCount, output, outputCell, outputCount);
            var unalignedCell = ManagedAddress.fromAllocation(ManagedAllocation.mutable(9, 8, false)).plus(1); unalignedCell.writeAddressElementIndex(0, output);
            bad(() -> iconv.convert(escaped, inputCell, inputCount, unalignedCell, outputCount), input, inputCell, inputCount, output, outputCell, outputCount);
            bad(() -> iconv.convert(escaped, inputCell, inputCount, ManagedAddress.nullAddress(), outputCount), input, inputCell, inputCount, output, outputCell, outputCount);
            assertEquals(0L, iconv.convert(escaped, ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), ManagedAddress.nullAddress()));
            long closed = iconv.open(string("UTF-8"), string("UTF-8")); assertEquals(0L, iconv.close(closed)); assertThrows(RuntimeFault.class, () -> iconv.close(closed)); assertThrows(RuntimeFault.class, () -> iconv.convert(closed, inputCell, inputCount, outputCell, outputCount));
            try (var other = context(true, true)) { other.initialize("thc"); other.enter(); try { var otherIconv = Language.currentState(null).getIconv(); long ownHandle = otherIconv.open(string("UTF-8"), string("UTF-8")); assertNotEquals(escaped, ownHandle); assertThrows(RuntimeFault.class, () -> otherIconv.close(escaped)); assertThrows(RuntimeFault.class, () -> otherIconv.convert(escaped, inputCell, inputCount, outputCell, outputCount)); assertEquals(1, otherIconv.liveHandles()); assertEquals(0L, otherIconv.close(ownHandle)); } finally { other.leave(); } }
            assertEquals(1, iconv.liveHandles()); // Deliberately left for context finalization.
        } finally { context.leave(); } }
        assertEquals(0, disposed.liveHandles()); assertThrows(RuntimeFault.class, () -> disposed.close(escaped));
    }
}
