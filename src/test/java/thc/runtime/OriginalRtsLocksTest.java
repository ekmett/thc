// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class OriginalRtsLocksTest {
    private final File root = new File(System.getProperty("thc.projectRoot")); private final String prefix = "build/original-rts-locks";
    private Map<String, Object> json(String file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/" + file).toPath())); }
    private Map<String, Object> cbd(String stage) throws Exception { return OriginalStdioChecks.module(new File(root, prefix + "/" + stage + ".cbd")); }
    private static String entryId(String name) { return "main:OriginalRtsLocksAudit." + name; }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void entered(Context context, Action action) throws Exception { context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<RootCallTarget> targets(RootCallTarget entry) { Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result; }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result);
        result.add(target);
    }
    private OriginalStdioOp operation(List<Object> call) {
        var metadata = (Map<?, ?>) call.get(6); var arguments = new ArrayList<Object>();
        for (var argument : (List<List<Object>>) call.get(2)) { var meta = CoreRepresentations.metadata(argument); arguments.add(meta == null ? null : meta.get("rep")); }
        return CoreOriginalStdio.validate(metadata, arguments, (List<?>) call.get(3), metadata.get("rep"));
    }
    private Object symbol(List<Object> call) { return ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"); }
    @Test public void genuineNativeClaimsMatchPrePostBothBackendsAndFirstInstalledCalls() throws Exception {
        var manifest = json("manifest.json"); assertEquals(true, manifest.get("strictAccepted")); assertEquals(true, manifest.get("originalIdsChecked")); assertEquals(true, manifest.get("typeEqualityChecked")); assertEquals(false, manifest.get("installedArtifactsHashed"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalRtsLocksAudit.hs", "t/haskell-fixtures/OriginalRtsLocksFixtures.hs", "bin/core-capabilities.json"), null);
        var required = new LinkedHashSet<>(List.of(prefix + "/pre.cbd", prefix + "/post.cbd", prefix + "/declarations.json", prefix + "/declarations.cbd", prefix + "/template-pre.cbd", prefix + "/oracle.json"));
        for (var stage : List.of("pre", "post")) for (var entry : List.of("originalLock", "originalUnlock")) required.add(prefix + "/" + stage + "-" + entry + ".audit.json");
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var declarations = json("declarations.json"); assertEquals(false, declarations.get("completeModule")); assertEquals("declarations.cbd", declarations.get("core")); var originalCalls = OriginalStdioChecks.foreignCalls(cbd("declarations"));
        var rows = (List<List<Object>>) json("oracle.json").get("rows"); var names = new ArrayList<>(); var results = new ArrayList<>();
        for (var row : rows) { names.add(row.get(0)); results.add(row.get(4)); }
        assertEquals(List.of("unknown", "reader", "repeat-reader", "second-reader", "writer-conflict", "release-one", "still-locked", "release-repeat", "release-other", "writer", "reader-conflict", "writer-repeat", "release-writer", "release-missing"), names);
        assertEquals(List.of(1L, 0L, 0L, 0L, -1L, 0L, -1L, 0L, 0L, 0L, -1L, -1L, 0L, 1L), results);
        for (var row : rows) { assertEquals(StdioHostAbi.load().error(4), row.get(5)); assertEquals(row.get(5), row.get(6)); }
        assertEquals(List.of(-1L, Long.MIN_VALUE, -1L), rows.get(1).get(2)); assertEquals((long) Integer.MIN_VALUE, rows.get(9).get(3));
        for (var stage : List.of("pre", "post")) {
            var module = cbd(stage); assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", module.get("boundary")); var calls = OriginalStdioChecks.foreignCalls(module); var operations = new LinkedHashSet<OriginalStdioOp>(); for (var call : calls) operations.add(operation(call));
            assertEquals(Set.of(OriginalStdioOp.LOCK, OriginalStdioOp.UNLOCK), operations);
            for (var call : calls) {
                // Private Names have no module: the serializer supplies its current
                // owner. The entire original occurrence/type/Unique must survive.
                var head = (List<?>) call.get(1); var adaptedPrefix = "main:OriginalRtsLocksAudit."; var originalPrefix = "ghc-internal:GHC.Internal.IO.FD.";
                assertTrue(((String) head.get(1)).startsWith(adaptedPrefix)); boolean found = false;
                for (var source : originalCalls) {
                    var original = (List<?>) source.get(1);
                    if (Objects.equals(symbol(source), symbol(call)) && Objects.equals(original.get(0), head.get(0)) && Objects.equals(original.get(2), head.get(2)) &&
                            ((String) original.get(1)).startsWith(originalPrefix) && ((String) original.get(1)).substring(originalPrefix.length()).equals(((String) head.get(1)).substring(adaptedPrefix.length())) &&
                            Objects.equals(((Map<?, ?>) source.get(6)).get("foreignCall"), ((Map<?, ?>) call.get(6)).get("foreignCall"))) { found = true; break; }
                }
                assertTrue(found, "exact original private FCallId occurrence, type, Unique and descriptor");
            }
            for (var entry : List.of("originalLock", "originalUnlock")) { var audit = json(stage + "-" + entry + ".audit.json"); assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals")); }
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, language -> {
                var instrumented = new LinkedHashMap<>(module); instrumented.put("instrument", true); var program = load(language, backend, instrumented);
                var entries = new LinkedHashMap<String, RootCallTarget>(); for (var name : List.of("originalLock", "originalUnlock")) entries.put(name, program.entryTarget(entryId(name)));
                class Runner {
                    void exercise(boolean compiled) throws Exception {
                        for (var row : rows) {
                            boolean locking = (Boolean) row.get(1); var name = locking ? "originalLock" : "originalUnlock"; var words = (List<Long>) row.get(2);
                            var args = new ArrayList<Long>(locking ? words : words.subList(0, Math.min(1, words.size()))); if (locking) args.add((Long) row.get(3));
                            var state = Language.currentState(); assertEquals(-1L, state.getStdio().close(-1)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.get(4), OriginalStdioChecks.invoke(program, entryId(name), args.toArray()), stage + "/" + backend + "/" + row.get(0)); assertEquals(row.get(6), state.getStdio().errno());
                            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, stage + "/" + backend + "/" + row.get(0) + " must enter installed guest code");
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        }
                    }
                }
                var runner = new Runner(); runner.exercise(false);
                for (var entry : entries.values()) for (var target : targets(entry)) {
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                }
                runner.exercise(true);
            }); }
        }
    }
    private void reject(List<Object> call, BiConsumer<List<Object>, Map<String, Object>> change) {
        var changed = (List<Object>) Json.parse(Json.stringify(call)); var descriptor = (Map<String, Object>) ((Map<String, Object>) changed.get(6)).get("foreignCall");
        change.accept(changed, descriptor); assertThrows(RuntimeFault.class, () -> operation(changed));
    }
    private record Mutation(String key, Object value) {}
    @Test public void stateRawProofsAndMalformedHeadsRejectBeforeTableMutation() throws Exception {
        var source = cbd("pre");
        for (var call : OriginalStdioChecks.foreignCalls(source)) {
            var op = Objects.requireNonNull(operation(call)); int count = op.getArguments().size();
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) { entered(context, language -> {
                var target = load(language, backend, OriginalStdioChecks.rawModule(call, source, null)).entryTarget("entry");
                var args = op == OriginalStdioOp.LOCK ? new Object[]{0L, -1L, Long.MIN_VALUE, -1L, 0L, thc.runtime.Unit.INSTANCE} : new Object[]{0L, -1L, thc.runtime.Unit.INSTANCE};
                var locks = Language.currentState().getRtsFileLocks(); assertEquals(0L, locks.lock(-1, Long.MIN_VALUE, -1, 0)); args[args.length - 1] = 9L;
                assertThrows(RuntimeFault.class, () -> Calls.target(target, args)); assertEquals(0L, locks.unlock(-1)); assertEquals(1L, locks.unlock(-1));
                if (op == OriginalStdioOp.LOCK) { args[args.length - 1] = thc.runtime.Unit.INSTANCE; args[4] = 1L << 32; assertThrows(RuntimeFault.class, () -> Calls.target(target, args)); assertEquals(1L, locks.unlock(-1)); }
                for (int index = 0; index < count; index++) { final int selected = index; assertThrows(RuntimeFault.class, () -> load(language, backend, OriginalStdioChecks.rawModule(call, source, selected))); }
                for (var head : List.of(List.of("var", 17L), List.of("var", "entry", Map.of("rep", OriginalStdioFixtures.closure())))) {
                    var module = OriginalStdioChecks.rawModule(call, source, null); var calls = OriginalStdioChecks.foreignCalls(module); if (calls.size() != 1) throw new IllegalArgumentException("Expected one call"); calls.getFirst().set(1, head);
                    assertThrows(RuntimeFault.class, () -> load(language, backend, module));
                }
            }); }
            for (var key : List.of("schema", "arity", "suppliedArity")) for (var bad : Arrays.asList(null, true, "2", 2.0, 0L, 1L << 32)) reject(call, (_, d) -> d.put(key, bad));
            for (var item : List.of(new Mutation("unit", "base"), new Mutation("kind", "dynamic"), new Mutation("isFunction", false), new Mutation("extra", 1L))) reject(call, (_, d) -> ((Map<String, Object>) d.get("target")).put(item.key(), item.value()));
            for (var item : List.of(new Mutation("convention", "capi"), new Mutation("safety", "safe"), new Mutation("extra", 1L))) reject(call, (_, d) -> d.put(item.key(), item.value()));
            for (int index = 0; index < count; index++) {
                final int selected = index; reject(call, (c, _) -> ((List<Object>) c.get(3)).set(selected, true));
                reject(call, (_, d) -> ((List<Map<String, Object>>) d.get("argumentReps")).get(selected).put("primReps", List.of("IntRep")));
                reject(call, (_, d) -> ((List<Map<String, Object>>) d.get("argumentReps")).get(selected).put("aggregate", "unboxed-tuple"));
            }
            reject(call, (_, d) -> ((Map<String, Object>) d.get("resultRep")).put("primReps", List.of("IntRep")));
        }
    }
    @Test public void tableIsContextLocalAndIndependentOfDescriptorCloseDupAndErrno() throws Exception {
        var retired = new RtsFileLocks[1];
        try (var first = context()) { entered(first, _ -> {
            var state = Language.currentState(); var locks = state.getRtsFileLocks(); retired[0] = locks; assertEquals(-1L, state.getStdio().close(-1)); long errno = state.getStdio().errno(); var kind = state.getFiles().errorKind();
            assertEquals(0L, locks.lock(1, -1, Long.MIN_VALUE, 0)); assertEquals(0L, locks.lock(1, -1, Long.MIN_VALUE, 0)); assertThrows(RuntimeFault.class, () -> locks.lock(1, -1, 7, 0));
            assertEquals(0L, locks.lock(7, -1, 7, 1)); assertEquals(0L, locks.unlock(7)); assertEquals(0L, locks.unlock(1)); assertEquals(-1L, locks.lock(7, -1, Long.MIN_VALUE, 1));
            long alias = state.getFiles().duplicate(1); assertTrue(alias >= 3); assertEquals(1L, locks.unlock(alias), "dup does not clone RTS claims"); assertEquals(0L, state.getFiles().close(1));
            assertEquals(-1L, locks.lock(7, -1, Long.MIN_VALUE, 1), "raw close must not release RTS keys"); assertEquals(0L, locks.lock(2, 8, 9, 1)); assertEquals(2L, state.getFiles().duplicateTo(alias, 2));
            assertEquals(-1L, locks.lock(7, 8, 9, 0), "dup2 replacement must not release the target's RTS key"); assertEquals(0L, locks.unlock(2)); assertEquals(0L, state.getFiles().close(2));
            assertEquals(alias, state.getFiles().duplicateTo(alias, alias)); assertEquals(0L, state.getFiles().close(alias)); assertEquals(0L, locks.unlock(1)); assertEquals(1L, locks.unlock(1));
            assertEquals(errno, state.getStdio().errno()); assertEquals(kind, state.getFiles().errorKind()); assertEquals(0L, locks.lock(0, -1, Long.MIN_VALUE, -2));
            try (var second = context()) { entered(second, _ -> { assertEquals(1L, Language.currentState().getRtsFileLocks().unlock(0)); assertEquals(0L, Language.currentState().getRtsFileLocks().lock(0, -1, Long.MIN_VALUE, 1)); }); }
        }); }
        assertThrows(RuntimeFault.class, () -> retired[0].lock(0, 0, 0, 0)); assertThrows(RuntimeFault.class, () -> retired[0].unlock(0)); retired[0].dispose(); // Idempotent teardown.
    }
}
