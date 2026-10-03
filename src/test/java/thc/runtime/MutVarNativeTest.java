// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
class MutVarNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("stRef", "lazyRef", "closureRef", "orderedRef", "unliftedRef", "stLoop"),
                               equalityNames = List.of("stRefEquality", "lazyRefEquality"),
                               lazyIONames = List.of("lazyIORef"), swapNames = List.of("swapRef", "lazySwapRef"),
                               modifyNames = List.of("modifyRef", "lazyModifyRef", "lazyBottomModifierRef");
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/mutvar/manifest.json").toPath()));
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
    private long mathematical(String name, long seed) {
        var x = BigInteger.valueOf(seed);
        BigInteger result;
        switch (name) {
            case "stRefEquality", "lazyRefEquality" -> {
                var score = BigInteger.valueOf(seed < 0 ? 5 : 1);
                if (name.equals("lazyRefEquality"))
                    result = x.add(score.multiply(BigInteger.valueOf(17)));
                else {
                    var left = seed < 0 ? x.add(BigInteger.valueOf(17)) : x;
                    var right = seed < 0 ? x : x.add(BigInteger.valueOf(17));
                    result = left.multiply(BigInteger.valueOf(257))
                                 .add(right.multiply(BigInteger.valueOf(65537)))
                                 .add(score.multiply(BigInteger.valueOf(17)));
                }
            }
            case "lazyRef" -> result = x.add(BigInteger.valueOf(5));
            case "lazyIORef" -> result = x.add(BigInteger.valueOf(17));
            case "closureRef" -> result = x.multiply(BigInteger.valueOf(4)).add(BigInteger.valueOf(11));
            case "swapRef" ->
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(x.multiply(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(65537)));
            case "modifyRef" ->
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(x.multiply(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(65537)))
                             .add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(16777259)));
            case "lazyModifyRef", "lazyBottomModifierRef" -> result = x;
            case "lazySwapRef" -> result = x.multiply(BigInteger.valueOf(258)).add(BigInteger.valueOf(7));
            case "unliftedRef" -> result = x.multiply(BigInteger.valueOf(258)).add(BigInteger.ONE);
            case "stLoop" -> {
                var value = x;
                for (int n = x.abs().mod(BigInteger.valueOf(33)).intValue(); n >= 1; n--)
                    value = value.multiply(BigInteger.valueOf(3)).add(BigInteger.valueOf(n));
                result = value;
            }
            default -> {
                var last = (name.equals("stRef") ? x.add(BigInteger.valueOf(17)) : x).multiply(BigInteger.valueOf(3));
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(last.multiply(BigInteger.valueOf(65537)))
                             .add(x.add(BigInteger.valueOf(71)).multiply(BigInteger.valueOf(16777259)));
            }
        }
        return result.longValue();
    }
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.CompilationTimeout", "30")
            .option("compiler.MaximumGraalGraphSize", "100000")
            .build();
    }
    @Test
    void nativeSTRefsMatchNative() throws Exception {
        verifyNative(names);
    }
    @Test
    void publicSTRefEqualityMatchesNative() throws Exception {
        verifyNative(equalityNames);
    }
    @Test
    void publicLazyIORefMatchesNative() throws Exception {
        verifyNative(lazyIONames);
    }
    @Test
    void nativeAtomicSwapMatchesNative() throws Exception {
        verifyNative(swapNames);
    }
    @Test
    void nativeLazyAtomicModifyMatchesNative() throws Exception {
        verifyNative(modifyNames);
    }
    private long compiled(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    private void check(long[] row, Value function, String label) {
        assertEquals(row[1], function.execute(row[0]).asLong(), label + "(" + row[0] + ")");
    }
    private void verifyNative(List<String> entryNames) throws Exception {
        var manifest = manifest();
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(
                    item.getValue(), actual, "Stale MutVar fixture: " + item.getKey() + "; rerun thc-fixtures mutvar");
            }
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(new File(root, "build/mutvar/oracle.tsv").toPath())) {
            var fields = Arrays.asList(line.split("\t", -1));
            rows.computeIfAbsent(fields.getFirst(), k -> new ArrayList<>()).add(fields);
        }
        var allNames = new HashSet<>(names);
        allNames.addAll(equalityNames);
        allNames.addAll(lazyIONames);
        allNames.addAll(swapNames);
        allNames.addAll(modifyNames);
        assertEquals(allNames, rows.keySet());
        int rowCount = 0;
        for (var list : rows.values()) rowCount += list.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var module = merged(stage.getValue());
            for (var name : entryNames) {
                var audit = (Map<String, Object>) Json.parse(Files.readString(
                    new File(root, "build/mutvar/" + stage.getKey() + "/" + name + ".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"), stage.getKey() + "/" + name + " strict Core audit");
                assertEquals(List.of(), audit.get("issues"), stage.getKey() + "/" + name + " audit issues");
                assertEquals(List.of(), audit.get("missingGlobals"), stage.getKey() + "/" + name + " missing globals");
                var primitives = new HashSet<Object>();
                for (var p : (List<Map<String, Object>>) audit.get("primitives")) primitives.add(p.get("name"));
                Set<String> required = switch (name) {
                    case "swapRef", "lazySwapRef" -> Set.of("newMutVar#", "readMutVar#", "atomicSwapMutVar#");
                    case "modifyRef" -> Set.of("newMutVar#", "readMutVar#", "atomicModifyMutVar2#");
                    case "lazyModifyRef", "lazyBottomModifierRef" -> Set.of("newMutVar#", "atomicModifyMutVar2#");
                    case "lazyRefEquality" -> Set.of("newMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#");
                    case "stRefEquality" ->
                        Set.of("newMutVar#", "readMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#");
                    default -> Set.of("newMutVar#", "readMutVar#", "writeMutVar#");
                };
                assertTrue(primitives.containsAll(required),
                    stage.getKey() + "/" + name + " lost primitive evidence: " + required);
                var cases = new ArrayList<long[]>();
                for (var row : Objects.requireNonNull(rows.get(name)))
                    cases.add(new long[] {Long.parseLong(row.get(1)), Long.parseLong(row.get(2))});
                for (var row : cases)
                    assertEquals(mathematical(name, row[0]), row[1], "Native " + name + "(" + row[0] + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var label = stage.getKey() + "/" + backend + "/" + name;
                            var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:MutVarAudit." + name));
                            linked.put("instrument", true);
                            var p = program(language, linked, backend);
                            var function = context.asValue(new EntryValue(p, "main:MutVarAudit." + name, 1));
                            for (var row : cases) check(row, function, label);
                            long beforeInstallation = compiled(p);
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            assertEquals(beforeInstallation, compiled(p), label + " installation executes no guest work");
                            var installedCases = cases.reversed();
                            check(installedCases.getFirst(), function, label);
                            assertTrue(compiled(p) > beforeInstallation, label + " first installed call enters compiled guest code");
                            var diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            assertEquals(true, ((Map<?, ?>) diagnostics.get("explicitCompilation")).get("validLastTier"),
                                label + " first installed call preserves the installed guest entry and host bridge");
                            for (var row : installedCases.subList(1, installedCases.size())) check(row, function, label);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(
                                    0L, ((Number) p.diagnostics().get(counter)).longValue(), label + "/" + counter);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth(),
                                label + " releases tuple results");
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
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
    private List<Object> application(Object module, MutVarOp operation) {
        for (var app : applications(module)) {
            var fn = (List<?>) app.get(1);
            if (fn.subList(0, Math.min(2, fn.size())).equals(List.of("prim", operation.getPrimitive())))
                return app;
        }
        throw new NoSuchElementException();
    }
    private Map<String, Object> configured(Map<String, Object> module, boolean diagnostic) {
        var result = new LinkedHashMap<>(module);
        result.put("diagnosticUnsupported", diagnostic);
        return result;
    }
    @Test
    void exactShapesAritySaturationAndLevityAreRequiredInBothLoadModes() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        var operations = new ArrayList<MutVarOp>();
        for (var operation : MutVarOp.values())
            if (operation != MutVarOp.CAS && operation != MutVarOp.MODIFY)
                operations.add(operation);
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 6; mutation++)
                            for (boolean diagnostic : new boolean[] {false, true}) {
                                var module = CoreModules.reachable(merged(paths),
                                    operation == MutVarOp.SWAP          ? "main:MutVarAudit.swapRef"
                                        : operation == MutVarOp.MODIFY2 ? "main:MutVarAudit.modifyRef"
                                                                        : "main:MutVarAudit.orderedRef");
                                var app = application(module, operation);
                                var args = (List<Object>) app.get(2);
                                var flags = (List<Object>) app.get(3);
                                var metadata = CoreRepresentations.metadata(app);
                                switch (mutation) {
                                    case 0 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                        metadata.remove("callDemand");
                                    }
                                    case 1 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                        metadata.remove("callDemand");
                                    }
                                    case 2 -> metadata.remove("rep");
                                    case 3 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        proof.put("primReps", List.of("IntRep"));
                                        proof.put("kind", "long");
                                        proof.remove("aggregate");
                                        proof.remove("components");
                                    }
                                    case 4 -> flags.set(0, !Boolean.TRUE.equals(flags.getFirst()));
                                    case 5 ->
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.getFirst())
                                                .get("rep"))
                                            .put("kind", "unknown");
                                    case 6 -> {
                                        if (operation.getTuple()) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            var children = (List<Object>) proof.get("components");
                                            children.set(0,
                                                Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                    List.of(), "primReps", List.of(), "evaluated", true));
                                        } else
                                            ((Map<String, Object>) CoreRepresentations
                                                    .metadata((List<Object>) args.getFirst())
                                                    .get("rep"))
                                                .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(language, configured(module, diagnostic), backend),
                                    backend + "/" + operation.getPrimitive() + "/mutation" + mutation + "/"
                                        + diagnostic);
                            }
                    for (var operation : operations) {
                        var module = CoreModules.reachable(merged(paths),
                            operation == MutVarOp.SWAP          ? "main:MutVarAudit.swapRef"
                                : operation == MutVarOp.MODIFY2 ? "main:MutVarAudit.modifyRef"
                                                                : "main:MutVarAudit.orderedRef");
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
