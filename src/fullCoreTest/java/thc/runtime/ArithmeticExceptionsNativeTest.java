// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class ArithmeticExceptionsNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/arithmetic-exceptions");
    private final List<String> entries = List.of("scalarDivZero", "scalarOverflow", "scalarUnderflow", "tupleDivZero", "tupleOverflow", "tupleUnderflow");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target), "Installed last-tier code required"); }
    private long model(String entry, long x) { int kind = entry.endsWith("DivZero") ? 1 : entry.endsWith("Overflow") ? 2 : 3; return x == 0L ? 100L + kind : x + (entry.startsWith("scalar") ? 10L : 20L) + kind; }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var found = new ArrayList<RootCallTarget>(); Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>()); visit(entry, seen, found); return found;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target)) return; var node = target.getRootNode(); var roots = new ArrayList<Node>(); roots.add(node);
        if (node instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) roots.add(cached); }
        for (var body : roots) for (var call : NodeUtil.findAllNodeInstances(body, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, found);
        found.add(target);
    }
    private record Row(long input, long result) {}
    @Test public void originalSomeExceptionsMatchNativeIncludingFirstCompiledColdRaise() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (var record : List.of(manifest.get("inputHashes"), manifest.get("artifactHashes"), ((Map<?, ?>) manifest.get("native")).get("artifactHashes")))
            for (var hash : ((Map<String, String>) record).entrySet()) assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale arithmetic exception fixture: " + hash.getKey());
        assertEquals(entries, manifest.get("entries")); var rows = new LinkedHashMap<String, List<Row>>(); int count = 0;
        for (String line : Files.readAllLines(new File(root, (String) manifest.get("oracle")).toPath(), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1); rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(new Row(Long.parseLong(row[1]), Long.parseLong(row[2]))); count++;
        }
        assertEquals(42, count); var stages = (Map<String, String>) manifest.get("stages"); var originals = new ArrayList<Map<String, Object>>();
        var targetLayout = CoreCbdFixtures.visitModules(new File(root, (String) manifest.get("packageManifest")).getPath(), (module, path) -> originals.add(module)).getTargetLayout(); assertNotNull(targetLayout, "Original installed RTS target layout is required");
        for (var stage : stages.entrySet()) {
            var modules = new ArrayList<>(originals); modules.add(OriginalStdioChecks.module(new File(root, stage.getValue()))); var module = new LinkedHashMap<>(CoreModules.merge(modules)); module.put("targetLayout", targetLayout);
            // The plugin also discovers RTS-only dependencies in thin interface
            // closures. Complete installed Core supplies the executable bodies.
            var closure = OriginalStdioChecks.module(new File(directory, stage.getKey() + "/core/THC.InterfaceClosure.cbd")); var discovered = new ArrayList<Object>();
            for (String key : List.of("bindings", "missingDefinitions")) for (var binding : (List<Map<String, Object>>) closure.get(key)) discovered.add(binding.get("id"));
            for (String name : List.of("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) assertTrue(discovered.contains(CoreArithmeticExceptions.payload(name)), stage.getKey() + " discovers the original implicit " + name + " payload");
            for (String backend : List.of("ast", "bytecode")) for (String entry : entries) for (boolean cold : new boolean[] {false, true}) try (Context context = context()) {
                var selected = Objects.requireNonNull(rows.get(entry)); var inputs = new ArrayList<Long>(); for (var row : selected) inputs.add(row.input());
                assertEquals(List.of(Long.MIN_VALUE, -17L, -1L, 0L, 1L, 17L, Long.MAX_VALUE), inputs);
                var audit = json(new File(directory, stage.getKey() + "/" + entry + "-audit.json")); assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("missingGlobals"));
                String expectedPrimitive = "raise" + (entry.startsWith("scalar") ? entry.substring(6) : entry.startsWith("tuple") ? entry.substring(5) : entry) + "#"; boolean retained = false;
                for (var primitive : (List<Map<String, Object>>) audit.get("primitives")) if (expectedPrimitive.equals(primitive.get("name"))) retained = true; assertTrue(retained, stage.getKey() + "/" + entry + " retains the intended primitive");
                for (var row : selected) assertEquals(model(entry, row.input()), row.result(), "native/" + entry + "/" + row.input());
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:ArithmeticExceptionsAudit." + entry, true)); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var function = context.asValue(new EntryValue(program, "main:ArithmeticExceptionsAudit." + entry, 1));
                    String label = stage.getKey() + "/" + backend + "/" + entry + "/" + (cold ? "cold" : "profiled");
                    // The cold scenario first encounters exceptions in installed
                    // guest code. The separate profiled scenario must preserve
                    // that code on the first compiled throw, without recompiling.
                    for (int i = 0; i < 5; i++) for (var row : selected) if (!cold || row.input() != 0L) assertEquals(row.result(), function.execute(row.input()).asLong(), label);
                    var body = program.entryTarget("main:ArithmeticExceptionsAudit." + entry + "Body"); var active = targets(program.entryTarget("main:ArithmeticExceptionsAudit." + entry)); boolean actualBody = false;
                    for (var target : active) if (target == body || target.getRootNode().getName().contains(entry + "Body")) actualBody = true;
                    assertTrue(actualBody, label + " observes the retained primitive body in the actual guest call graph"); for (var target : active) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean()); for (var target : active) assertTrue(valid(target), label + " remains installed before its first raise");
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(model(entry, 0), function.execute(0L).asLong(), label + " first installed raise");
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() - before >= 2, label + " entered the retained compiled guest call chain");
                    if (!cold) for (var target : active) assertTrue(valid(target), label + " first installed throw retains compiled " + target.getRootNode().getName());
                    for (var row : selected) assertEquals(row.result(), function.execute(row.input()).asLong(), label + "/" + row.input());
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"), label); assertEquals(0L, program.diagnostics().get("blackholes"), label);
                } finally { context.leave(); }
            }
        }
    }
}
