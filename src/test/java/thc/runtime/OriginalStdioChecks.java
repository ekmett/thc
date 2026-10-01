// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import thc.CoreModules;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Independent expectations; the native preparer only records observations. */
@SuppressWarnings("unchecked")
public final class OriginalStdioChecks {
    private OriginalStdioChecks() {}
    /** Inspect the authentic executable CBD through the canonical record decoder. */
    public static Map<String, Object> module(File file) throws Exception {
        return thc.CoreCbdFixtures.read(file.toPath());
    }
    private record NativeProviders(List<thc.PackageScalarAdmission> admissions, TargetLayout target, Map<String, Object> runtime) {}
    private static NativeProviders providers;
    private static synchronized NativeProviders nativeProviders() throws Exception {
        if (providers == null) {
            var root = new File(System.getProperty("thc.projectRoot"));
            var prefix = "build/original-process-identity/";
            var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "manifest.json").toPath()));
            assertEquals(true, manifest.get("installedArtifactsHashed"));
            hashes(root, manifest.get("inputHashes"), Set.of("t/haskell-fixtures/InstalledCoreFixtures.hs"));
            hashes(root, manifest.get("artifactHashes"), Set.of(prefix + "installed/packages.json"), prefix);
            var directory = thc.CoreUnitDirectory.read((Map<?, ?>) Json.parse(Files.readString(
                new File(root, (String) manifest.get("packageManifest")).toPath())));
            var artifactHashes = (Map<String, String>) manifest.get("artifactHashes");
            for (var record : directory.getModules()) {
                var artifact = record.artifact();
                var base = root.getCanonicalFile().toPath();
                assertTrue(artifact.path().startsWith(base), "External executable CBD: " + artifact.path());
                assertEquals(artifact.sha256(), artifactHashes.get(base.relativize(artifact.path()).toString()), "Uninventoried executable CBD");
            }
            var owners = Set.of("GHC.Internal.Fingerprint", "GHC.Internal.System.Posix.Internals", "System.Posix.User");
            var found = new HashSet<String>();
            var requiredUnits = new HashSet<String>();
            for (var record : directory.getModules()) if (owners.contains(record.getName())) {
                requiredUnits.add(record.getUnit()); found.add(record.getName());
                assertTrue(record.getPackageScalarDeclarations(), "Missing original C declarations: " + record.getName());
            }
            assertEquals(owners, found);
            var target = Objects.requireNonNull(directory.getTargetLayout());
            var admissions = new LinkedHashMap<String, thc.PackageScalarAdmission>();
            // Complete unit declarations prove the component ABI. Each canonical reader
            // checks its artifact and metadata without decoding unused binding bodies.
            for (var record : directory.getModules()) if (requiredUnits.contains(record.getUnit()) && record.getPackageScalarDeclarations()) {
                try (var reader = new thc.CoreCompactModule(record, target, true)) {
                    var admission = thc.PackageScalarLinks.read(reader.metadata(), true, false);
                    if (admission == null) continue;
                    var unit = admission.link().getUnit();
                    var previous = admissions.get(unit);
                    if (previous == null) admissions.put(unit, admission);
                    else {
                        assertTrue(previous.link().same(admission.link()), "Conflicting original C component: " + unit);
                        var proved = new LinkedHashSet<>(previous.proved()); proved.addAll(admission.proved());
                        admissions.put(unit, new thc.PackageScalarAdmission(previous.link(), Set.copyOf(proved)));
                    }
                }
            }
            var runtimeModules = new ArrayList<Map<String, Object>>();
            for (var path : (List<String>) manifest.get("runtimeModules")) runtimeModules.add(module(new File(root, path)));
            var runtime = CoreModules.merge(runtimeModules);
            var proof = CoreForeignExceptionBridge.select(runtime);
            var paths = new ArrayList<String>(); paths.add("@" + new File(root, (String) manifest.get("packageManifest")).getPath());
            for (var path : (List<String>) manifest.get("runtimeModules")) paths.add(new File(root, path).getPath());
            // The complete fixture hashes were verified above. Production selection
            // validates the exact reachable closure without decoding unused bodies.
            var helpers = thc.CoreCbdFixtures.selectedRoots(paths, (String) proof.get("box"), (String) proof.get("project"));
            CoreModules.reachable(helpers, List.of((String) proof.get("box"), (String) proof.get("project")), true);
            helpers.put("selectedForeignExceptionBridge", proof);
            providers = new NativeProviders(List.copyOf(admissions.values()), target, helpers);
        }
        return providers;
    }
    /** Genuine installed declarations contribute checked links, never substitute Haskell bodies. */
    public static Map<String, Object> nativeModules(Map<String, Object> module) throws Exception {
        var providers = nativeProviders();
        var merger = new CoreModules.Merger();
        providers.admissions().forEach(merger::addPackageProvenance);
        merger.add(providers.runtime());
        merger.add(module);
        var merged = with(merger.finish(), "targetLayout", providers.target());
        for (var key : List.of("foreignExceptionBridges", "foreignExceptionBridgeUnit", "selectedForeignExceptionBridge"))
            merged.put(key, providers.runtime().get(key));
        if (module.containsKey("instrument")) merged.put("instrument", module.get("instrument"));
        for (var link : (List<thc.PackageScalarLink>) merged.get("packageScalarLinks"))
            thc.Language.currentState().getPackageCbits().link(link);
        return merged;
    }
    /** Invoke genuine Core through the existing host entry and typed input boundary. */
    public static Object invoke(ExecutableProgram program, String entry, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(entry), arguments});
    }
    public static final List<String> names = List.of("originalWrite", "originalSafeWrite", "originalWriteErrno", "originalSafeWriteErrno");
    public static final byte[] payload = new byte[256];
    static { for (int i = 0; i < payload.length; i++) payload[i] = (byte) i; }
    private record Slice(int offset, int count) {}
    private static final List<Slice> slices = List.of(new Slice(0, 0), new Slice(256, 0), new Slice(0, 1),
        new Slice(1, 8), new Slice(10, 1), new Slice(127, 3), new Slice(128, 128), new Slice(255, 1), new Slice(0, 256));

    // Ordered nullable documents keep the fixture builders and negative mutations readable.
    public static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    public static Map<String, Object> with(Map<?, ?> original, Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : original.entrySet()) result.put((String) entry.getKey(), entry.getValue());
        result.putAll(map(fields)); return result;
    }
    public static Map<String, Object> without(Map<?, ?> original, Object... fields) {
        var result = with(original); for (var field : fields) result.remove(field); return result;
    }
    public static List<Object> list(Object... values) { return Arrays.asList(values); }
    public static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    public static List<Map<String, Object>> expectedRows() throws IOException {
        long badDescriptor = StdioHostAbi.load().error(4);
        var result = new ArrayList<Map<String, Object>>();
        for (String name : names) for (long fd : new long[] {Integer.MIN_VALUE, -1, 1, 2}) for (var slice : slices) {
            boolean failed = fd < 0;
            long value = name.endsWith("Errno") ? (failed ? badDescriptor : -(long) slice.count - 2) :
                (failed ? -1L : slice.count);
            var bytes = hex(Arrays.copyOfRange(payload, slice.offset, slice.offset + slice.count));
            result.add(map("entry", name, "arguments", List.of(fd, (long) slice.offset, (long) slice.count), "result", value,
                "stdoutHex", fd == 1 ? bytes : "", "stderrHex", fd == 2 ? bytes : ""));
        }
        return result;
    }
    public static List<Map<String, Object>> rows(Object actual) throws IOException {
        var expected = expectedRows();
        assertTrue(actual instanceof List<?>, "native rows must be a list");
        var rows = (List<?>) actual;
        assertEquals(expected.size(), rows.size(), "missing/duplicate native rows");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), rows.get(i), "native row " + i + " differs from Java model");
        return expected;
    }
    public static void hashes(File root, Object value, Set<String> required) throws Exception { hashes(root, value, required, null); }
    public static void hashes(File root, Object value, Set<String> required, String prefix) throws Exception {
        var base = root.getCanonicalFile();
        var hashes = (Map<String, String>) value;
        var missing = new LinkedHashSet<>(required); missing.removeAll(hashes.keySet());
        assertTrue(hashes.keySet().containsAll(required), "missing provenance: " + missing);
        for (var entry : hashes.entrySet()) {
            var path = entry.getKey(); var expected = entry.getValue(); var file = new File(base, path);
            assertFalse(new File(path).isAbsolute(), path);
            assertEquals(file.getAbsoluteFile(), file.getCanonicalFile(), "noncanonical provenance path: " + path);
            assertTrue(file.toPath().startsWith(base.toPath()), "external provenance: " + path);
            if (prefix != null) assertTrue(path.startsWith(prefix), "unexpected artifact: " + path);
            assertTrue(expected.matches("[0-9a-f]{64}"), path);
            assertEquals(expected, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "stale fixture: " + path);
        }
    }
    public static List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(nodes(child));
        else if (value instanceof List<?> list) {
            result.add((List<Object>) list);
            for (var child : list) result.addAll(nodes(child));
        }
        return result;
    }
    private static Object rep(Object expression) { return ((Map<?, ?>) ((List<?>) expression).getLast()).get("rep"); }
    public static List<List<Object>> foreignCalls(Object value) {
        var result = new ArrayList<List<Object>>();
        for (var node : nodes(value)) if (!node.isEmpty() && Objects.equals(node.getFirst(), "app") &&
            node.getLast() instanceof Map<?, ?> metadata && metadata.containsKey("foreignCall")) result.add(node);
        return result;
    }
    public static Map<String, Object> rawModule(List<?> original, Map<String, Object> source) { return rawModule(original, source, null); }
    /** Reuse one genuine FCall for raw-carrier negative controls, not provenance. */
    public static Map<String, Object> rawModule(List<?> original, Map<String, Object> source, Integer storedMutation) {
        var call = (List<Object>) Json.parse(Json.stringify(original));
        var descriptor = (Map<?, ?>) ((Map<?, ?>) call.get(6)).get("foreignCall");
        var reps = new ArrayList<Map<String, Object>>();
        for (var argument : (List<Map<String, Object>>) descriptor.get("argumentReps")) reps.add(with(argument, "evaluated", true));
        var output = (Map<String, Object>) descriptor.get("resultRep");
        var components = (List<Map<String, Object>>) output.get("components");
        var result = components.size() > 1 ? components.get(1) : OriginalStdioFixtures.scalar("IntRep");
        call.set(1, list("var", "foreign", map("rep", OriginalStdioFixtures.closure())));
        var arguments = new ArrayList<Object>(); var formals = new ArrayList<Object>();
        for (int i = 0; i < reps.size(); i++) {
            var rep = reps.get(i);
            arguments.add(list("var", "p" + i, map("rep", rep)));
            formals.add(map("id", "p" + i, "name", "p" + i, "lifted", false,
                "rep", Objects.equals(storedMutation, i) ? OriginalStdioFixtures.scalar("IntRep") : rep));
        }
        call.set(2, arguments);
        var ids = components.size() == 1 ? List.of("s") : List.of("s", "value");
        var returned = components.size() == 1 ? list("lit", "int", "0", map("rep", result)) : list("var", "value", map("rep", result));
        var binders = new ArrayList<Object>();
        for (int i = 0; i < Math.min(ids.size(), components.size()); i++) binders.add(map("id", ids.get(i), "lifted", false, "rep", components.get(i)));
        var body = list("case", call, "pair", list(list("data", "T" + components.size(), ids, returned, map("binders", binders))),
            map("rep", result, "binder", map("id", "pair", "lifted", false, "rep", output)));
        var constructors = new ArrayList<Object>();
        if (source.get("constructors") instanceof List<?> existing)
            for (var constructor : existing)
                if (!Objects.equals(((Map<?, ?>) constructor).get("id"), "T" + components.size())) constructors.add(constructor);
        constructors.add(map("id", "T" + components.size(), "kind", "unboxed-tuple", "arity", components.size(), "tag", 1));
        var bindings = new ArrayList<Object>();
        if (source.get("selectedForeignExceptionBridge") instanceof Map<?, ?> bridge)
            bindings.addAll((List<?>) CoreModules.reachable(source,
                List.of((String) bridge.get("box"), (String) bridge.get("project")), true).get("bindings"));
        bindings.add(map("id", "entry", "name", "entry", "arity", reps.size(), "lifted", true, "rep", OriginalStdioFixtures.closure(),
            "expr", list("lam", formals, body, map("rep", OriginalStdioFixtures.closure(), "resultRep", result))));
        return with(source, "instrument", true, "constructors", constructors, "bindings", bindings);
    }
    private static void binders(Object value, Set<Object> bound) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("id")) bound.add(map.get("id"));
            for (var child : map.values()) binders(child, bound);
        } else if (value instanceof List<?> list) for (var child : list) binders(child, bound);
    }
    /** Check the real exported consumer, including the immediate runRW State lambda. */
    public static Map<String, List<String>> module(Map<String, Object> module) {
        var bindings = (List<Map<String, Object>>) module.get("bindings");
        var roots = new ArrayList<Map<String, Object>>(); var rootNames = new LinkedHashSet<Object>();
        for (var binding : bindings) for (var name : names) if (Objects.equals(binding.get("id"), "main:OriginalStdioAudit." + name)) { roots.add(binding); rootNames.add(name); }
        assertEquals(new HashSet<>(names), rootNames); assertEquals(4, roots.size());
        var bound = new HashSet<Object>(); binders(module, bound);
        var result = new LinkedHashMap<String, List<String>>();
        for (var binding : roots) {
            var name = (String) binding.get("id"); var body = (List<?>) binding.get("expr");
            assertEquals(4L, binding.get("arity")); assertEquals(OriginalStdioFixtures.closure(), binding.get("rep"));
            assertEquals("lam", body.get(0), name);
            assertEquals(OriginalStdioFixtures.scalar("IntRep", false), ((Map<?, ?>) body.getLast()).get("resultRep"));
            var formals = (List<Map<String, Object>>) body.get(1);
            assertEquals(4, formals.size()); var primitives = List.of("IntRep", "AddrRep", "IntRep", "WordRep");
            for (int i = 0; i < Math.min(formals.size(), primitives.size()); i++) {
                var formal = formals.get(i);
                assertEquals(OriginalStdioFixtures.scalar(primitives.get(i)), formal.get("rep"), name);
                assertEquals(false, formal.get("lifted")); assertEquals(false, formal.get("coercion"));
            }
            int lambdas = 0;
            for (var node : nodes(body)) if (!node.isEmpty() && Objects.equals(node.getFirst(), "lam")) lambdas++;
            assertEquals(2, lambdas, name);
            var run = (List<?>) body.get(2);
            assertEquals("app", run.get(0)); assertEquals(List.of(false), run.get(3));
            assertEquals(OriginalStdioFixtures.scalar("IntRep", false), rep(run));
            var lambda = (List<?>) run.get(1); assertEquals("lam", lambda.get(0));
            var states = (List<Map<String, Object>>) lambda.get(1); assertEquals(1, states.size()); var state = states.getFirst();
            assertEquals("State# RealWorld", state.get("type")); assertEquals(OriginalStdioFixtures.scalar(null), state.get("rep"));
            assertEquals(false, state.get("lifted")); assertEquals(false, state.get("coercion"));
            var arguments = (List<?>) run.get(2); assertEquals(1, arguments.size()); var argument = (List<?>) arguments.getFirst();
            assertEquals("void", argument.get(0)); assertEquals(OriginalStdioFixtures.scalar(null), rep(argument));
            var expected = new ArrayList<String>(); expected.add(name.contains("Safe") ? "safe_write" : "unsafe_write");
            if (name.endsWith("Errno")) expected.add("errno");
            var symbols = new ArrayList<String>();
            for (var app : foreignCalls(body)) {
                assertEquals(7, app.size()); var head = (List<Object>) app.get(1);
                CoreOriginalStdio.validateHead(head, bound.contains(head.get(1)));
                var argumentReps = new ArrayList<Object>(); for (var arg : (List<?>) app.get(2)) argumentReps.add(rep(arg));
                var operation = CoreOriginalStdio.validate(app.get(6), argumentReps, (List<?>) app.get(3), rep(app));
                assertNotNull(operation, "unsupported original foreign call in " + name); symbols.add(operation.getSymbol());
            }
            var expectedSymbols = new ArrayList<String>(); for (var expectedName : expected) expectedSymbols.add(Objects.requireNonNull(OriginalStdioFixtures.symbols.get(expectedName)));
            assertEquals(expectedSymbols, symbols, name); result.put((String) binding.get("id"), symbols);
        }
        assertEquals(6, foreignCalls(module).size(), "extra/missing FCall copies outside the four consumers"); return result;
    }
    public static void audit(Map<String, Object> report, String owner, List<String> symbols) {
        assertEquals(true, report.get("accepted")); assertEquals(List.of(owner), report.get("roots"));
        var reachable = new ArrayList<Object>();
        for (var binding : (List<Map<String, Object>>) report.get("reachableBindings")) reachable.add(binding.get("id"));
        assertEquals(List.of(owner), reachable);
        for (var field : List.of("issues", "missingGlobals", "runtimeExternals")) assertEquals(List.of(), report.get(field), field);
        var calls = (List<Map<String, Object>>) report.get("foreignCalls");
        var actualSymbols = new ArrayList<String>(); for (var call : calls) actualSymbols.add((String) call.get("symbol"));
        var expectedSymbols = new ArrayList<>(symbols); Collections.sort(expectedSymbols); Collections.sort(actualSymbols);
        assertEquals(expectedSymbols, actualSymbols);
        boolean sameOwner = true; for (var call : calls) sameOwner &= Objects.equals(call.get("owner"), owner);
        assertTrue(sameOwner);
    }
    public static <T> T single(Iterable<T> values, java.util.function.Predicate<? super T> predicate) {
        T result = null; boolean found = false;
        for (T value : values) if (predicate.test(value)) {
            if (found) throw new IllegalArgumentException("Collection contains more than one matching element.");
            result = value; found = true;
        }
        if (!found) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return result;
    }
    /** Discover direct guest callees before their parent, for exact installed-root assertions. */
    public static List<com.oracle.truffle.api.RootCallTarget> targets(com.oracle.truffle.api.RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<com.oracle.truffle.api.RootCallTarget, Boolean>());
        var found = new ArrayList<com.oracle.truffle.api.RootCallTarget>(); visitTarget(entry, seen, found); return found;
    }
    private static void visitTarget(com.oracle.truffle.api.RootCallTarget target, Set<com.oracle.truffle.api.RootCallTarget> seen,
            List<com.oracle.truffle.api.RootCallTarget> found) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<com.oracle.truffle.api.nodes.Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == com.oracle.truffle.api.bytecode.Instruction.Argument.Kind.NODE_PROFILE) {
                var node = argument.asCachedNode(); if (node != null) nodes.add(node);
            }
        for (var node : nodes) for (var call : com.oracle.truffle.api.nodes.NodeUtil.findAllNodeInstances(node, com.oracle.truffle.api.nodes.DirectCallNode.class)) {
            if (call.getCurrentCallTarget() instanceof com.oracle.truffle.api.RootCallTarget callee && callee.getRootNode() instanceof GuestRoot)
                visitTarget(callee, seen, found);
        }
        found.add(target);
    }
}
