// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Original formatter/source-overlay proofs, not the JVM renderer or full stack decoder. */
@SuppressWarnings("unchecked")
public class OriginalStackFormatterTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-stack-formatter/";
    private final String sourceRoot = "compiler/pinned-ghc-internal/";
    private Map<String, String> pinned;
    private Set<String> requiredInputs;
    private final List<String> labels = OriginalStackFormatterCommands.labels;
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hash(File file) throws Exception { return hash(Files.readAllBytes(file.toPath())); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Failed requirement."); }
    private synchronized Map<String, String> pinned() throws Exception {
        if (pinned == null) {
            var source = Files.readString(contained("src/THC/Driver/Wired.hs", true).toPath()); int begin = source.indexOf("sourceHashes ="); var text = begin < 0 ? source : source.substring(begin + "sourceHashes =".length()); int end = text.indexOf("\ndata WiredArtifacts"); if (end >= 0) text = text.substring(0, end);
            var matcher = Pattern.compile("\\(\"([^\"]+)\", \"([0-9a-f]{64})\"\\)").matcher(text); var entries = new ArrayList<Map.Entry<String, String>>();
            while (matcher.find()) entries.add(Map.entry(matcher.group(1), matcher.group(2))); var names = new HashSet<String>(); for (var entry : entries) names.add(entry.getKey()); require(entries.size() == 70 && names.size() == 70);
            var sorted = new ArrayList<>(entries); sorted.sort(Map.Entry.comparingByKey()); var catalog = new StringBuilder(); for (var entry : sorted) catalog.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
            require(hash(catalog.toString().getBytes(StandardCharsets.UTF_8)).equals("e49b897efc3f06fd3967f4ddd700298c4949fd8918a0142eabad54abc5ba454e"));
            var result = new LinkedHashMap<String, String>(); for (var entry : entries) result.put(sourceRoot + entry.getKey(), entry.getValue()); pinned = result;
        } return pinned;
    }
    private synchronized Set<String> requiredInputs() throws Exception {
        if (requiredInputs == null) {
            var result = new LinkedHashSet<>(pinned().keySet()); result.addAll(List.of("compiler/test-fixtures/OriginalStackFormatter.hs", "compiler/test-fixtures/OriginalStackFormatterNative.hs", "test/haskell-fixtures/StackFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "src/THC/Driver/Wired.hs", "compiler/target-layout.c", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py", "compiler/THC/Plugin.hs", "compiler/THC/CBV.hs", "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs", "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json", "scripts/core_data_tags.py", "scripts/core_managed_files.py", "scripts/core_md5_foreign.py", "scripts/core_original_foreign.py", "scripts/core_package_manifest.py", "scripts/core_sums.py", "scripts/core_tuple_inputs.py", "scripts/core_vector_memory.py", "scripts/core_vectors.py")); requiredInputs = result;
        } return requiredInputs;
    }
    private File contained(String path, boolean input) throws Exception {
        require(!new File(path).isAbsolute()); for (var component : path.split("/", -1)) require(!Set.of("", ".", "..").contains(component)); require(input || path.startsWith(prefix));
        var base = root.getCanonicalFile().toPath(); var file = new File(root, path).getCanonicalFile(); require(file.toPath().startsWith(input ? base : base.resolve(prefix))); return file;
    }
    private final List<List<String>> triples = List.of(List.of("entry", "Main", "Fixture.hs:12:3-12:17"), List.of("", "", ""), List.of("$wdecode", "GHC.Internal.Stack.Decode", "<source unavailable>"), List.of("λ雪😀", "Módulo.例", "路径.hs:1:2"), List.of("a.b (c)", "M\tN", "line\nspan"), List.of("\u0000last", "Null", "zero\u0000location"));
    private int[] expected(int row) { var triple = triples.get(row); return (triple.get(1) + "." + triple.get(0) + " (" + triple.get(2) + ")").codePoints().toArray(); }
    private Map<String, Object> manifest() throws Exception { return checked(json(prefix + "manifest.json")); }
    private Map<String, Object> checked(Map<String, Object> value) throws Exception {
        require(value.keySet().equals(Set.of("format", "schema", "ghc", "installedArtifactsHashed", "originals", "stages", "nativeOutput", "audits", "inputHashes", "artifactHashes", "commands", "limit")));
        require(Objects.equals(value.get("format"), "thc-original-stack-formatter-fixture") && Objects.equals(value.get("schema"), 1L)); require(Objects.equals(value.get("ghc"), "9.14.1") && Objects.equals(value.get("installedArtifactsHashed"), false));
        require(Objects.equals(value.get("limit"), "Original prettyStackEntry only; not full original stack decoding or native-frame equivalence.")); var inputs = (Map<String, String>) value.get("inputHashes"); require(inputs.keySet().equals(requiredInputs())); for (var entry : pinned().entrySet()) require(Objects.equals(inputs.get(entry.getKey()), entry.getValue()));
        var output = (String) value.get("nativeOutput"); require(output.matches("build/original-stack-formatter/run-[1-9][0-9]*/logs/native-observations\\.stdout")); var attempt = output.substring(0, output.indexOf("/logs/"));
        var sources = new ArrayList<String>(); for (var path : pinned().keySet()) { var source = path.substring(sourceRoot.length()); if (source.endsWith(".hs") || source.endsWith(".hsc")) sources.add(source); }
        var expectedOriginals = new ArrayList<String>(); var generated = new ArrayList<String>(); for (var source : sources) { expectedOriginals.add(attempt + "/originals/core/" + source.substring(0, source.lastIndexOf('.')).replace('/', '.') + ".json"); if (source.endsWith(".hsc")) generated.add(attempt + "/originals/generated/" + source.substring(0, source.length() - 4) + ".hs"); } Collections.sort(expectedOriginals);
        var expectedStages = new LinkedHashMap<String, String>(); var expectedAudits = new ArrayList<String>(); for (var stage : List.of("pre", "post")) { expectedStages.put(stage, attempt + "/" + stage + "-core/OriginalStackFormatter.json"); expectedAudits.add(attempt + "/" + stage + "-audit.json"); }
        var expectedArtifacts = new LinkedHashSet<>(expectedOriginals); expectedArtifacts.addAll(expectedStages.values()); expectedArtifacts.addAll(expectedAudits); expectedArtifacts.addAll(generated); expectedArtifacts.addAll(List.of(attempt + "/native/formatter", attempt + "/originals/generated.json", attempt + "/originals/target-layout.json"));
        for (var label : labels) for (var suffix : List.of("stdout", "stderr", "command.json")) expectedArtifacts.add(attempt + "/logs/" + label + "." + suffix);
        require(Objects.equals(value.get("originals"), expectedOriginals) && Objects.equals(value.get("stages"), expectedStages) && Objects.equals(value.get("audits"), expectedAudits)); var artifacts = (Map<String, String>) value.get("artifactHashes"); require(artifacts.keySet().equals(expectedArtifacts)); var commands = OriginalStackFormatterCommands.checked(value.get("commands"));
        for (var kind : List.of("input", "artifact")) for (var entry : (kind.equals("input") ? inputs : artifacts).entrySet()) { require(entry.getValue().matches("[0-9a-f]{64}")); if (!hash(contained(entry.getKey(), kind.equals("input"))).equals(entry.getValue())) throw new IllegalArgumentException("Stale " + kind + ": " + entry.getKey()); }
        for (var audit : expectedAudits) require(Objects.equals(json(audit).get("accepted"), true)); for (int i = 0; i < labels.size(); i++) require(json(attempt + "/logs/" + labels.get(i) + ".command.json").equals(commands.get(i))); return value;
    }
    private record Row(long row, long index, long result) {}
    private record Position(long row, long index) {}
    private List<Row> rows(Map<String, Object> manifest) throws Exception {
        var rows = new ArrayList<Row>(); var positions = new ArrayList<Position>();
        for (var line : Files.readAllLines(new File(root, (String) manifest.get("nativeOutput")).toPath())) { var fields = line.split("\t", -1); var values = new ArrayList<Long>(); for (var field : fields) values.add(Long.parseLong(field)); require(values.size() == 3); rows.add(new Row(values.get(0), values.get(1), values.get(2))); positions.add(new Position(values.get(0), values.get(1))); }
        var domain = new ArrayList<Position>(); for (long row = 0; row <= 5; row++) for (long index = 0; index <= 100; index++) domain.add(new Position(row, index)); require(positions.equals(domain));
        for (var row : rows) { var expected = expected((int) row.row()); int index = (int) row.index(); assertEquals(index >= 0 && index < expected.length ? (long) expected[index] : -1L, row.result()); } return rows;
    }
    private static <T> T single(Collection<T> values, java.util.function.Predicate<T> predicate) { T result = null; int count = 0; for (var value : values) if (predicate.test(value)) { result = value; count++; } if (count != 1) throw new IllegalArgumentException("Expected a single matching value"); return result; }
    private static Map<String, Object> plus(Map<String, ?> source, String key, Object value) { var result = new LinkedHashMap<String, Object>(source); result.put(key, value); return result; }
    private static Map<String, String> minus(Map<String, String> source, String key) { var result = new LinkedHashMap<>(source); result.remove(key); return result; }
    private static List<Map<String, Object>> bindings(Map<String, Object> module) { return (List<Map<String, Object>>) module.get("bindings"); }
    private static Map<String, Object> binding(Map<String, Object> module, String id) { return single(bindings(module), value -> Objects.equals(value.get("id"), id)); }
    private static List<?> take(Object value, int count) { var list = (List<?>) value; return list.subList(0, Math.min(count, list.size())); }
    private static int references(Object value, String id) {
        int count = 0; if (value instanceof List<?> list) { if (list.size() > 1 && Objects.equals(list.get(0), "var") && Objects.equals(list.get(1), id)) return 1; for (var child : list) count += references(child, id); }
        else if (value instanceof Map<?, ?> map) for (var child : map.values()) count += references(child, id); return count;
    }
    private Map<String, Object> linked(Map<String, Object> manifest, String stage, String consumer) throws Exception {
        var report = json(single((List<String>) manifest.get("audits"), value -> value.endsWith("/" + stage + "-audit.json"))); var reached = (List<Map<String, Object>>) report.get("reachableBindings"); var ids = new LinkedHashSet<Object>(); var paths = new LinkedHashSet<String>(); for (var value : reached) { ids.add(value.get("id")); paths.add((String) value.get("source")); }
        for (var path : paths) require(path.equals(consumer) || ((List<?>) manifest.get("originals")).contains(path)); var originals = new ArrayList<Map<String, Object>>();
        for (var path : paths) { var full = json(path); var selected = new ArrayList<Map<String, Object>>(); for (var value : bindings(full)) if (ids.contains(value.get("id"))) selected.add(value); originals.add(plus(full, "bindings", selected)); }
        var module = CoreModules.merge(originals); var linked = CoreModules.reachable(module, "formatOriginal", true); var original = binding(linked, "ghc-internal:GHC.Internal.Stack.Decode.prettyStackEntry"); var full = single(originals, value -> Objects.equals(value.get("module"), "GHC.Internal.Stack.Decode")); assertEquals(binding(full, (String) original.get("id")), original);
        boolean worker = false, decoder = false; for (var value : bindings(linked)) { if (Objects.equals(value.get("id"), "ghc-internal:GHC.Internal.Stack.Decode.$wprettyStackEntry")) worker = true; if (((String) value.get("id")).contains("decodeStack")) decoder = true; } assertTrue(worker); assertFalse(decoder); return plus(linked, "instrument", true);
    }
    private Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static final class DeferredAction extends GuestRoot {
        private final TupleShape shape; private final int valueSlot; int calls;
        DeferredAction(Language language, TupleShape shape) { this(language, shape, new FrameLayout()); }
        private DeferredAction(Language language, TupleShape shape, FrameLayout layout) { this(language, shape, layout, layout.bind("value")); }
        private DeferredAction(Language language, TupleShape shape, FrameLayout layout, int valueSlot) { super(language, layout.build()); this.shape = shape; this.valueSlot = valueSlot; configureEntry(new boolean[]{false}, false); configureTupleResult(shape); }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) { calls++; FrameAccess.write(frame, valueSlot, "deferred action result"); return shape.finish(frame, new int[]{valueSlot}); }
    }
    @Test void freshOriginalInfoTableWorkerHasExactCallersAndTargetGeneratedSource() throws Exception {
        var receipt = manifest(); var paths = (List<String>) receipt.get("originals"); var full = json(single(paths, value -> value.endsWith("/GHC.Internal.Heap.InfoTable.Types.json"))); var id = "ghc-internal:GHC.Internal.Heap.InfoTable.Types.$w$cshowsPrec"; var original = binding(full, id); assertEquals("$w$cshowsPrec", original.get("name"));
        var lambda = (List<?>) original.get("expr"); assertEquals("lam", lambda.get(0), "The original worker must be a body, not a name alias"); var formals = (List<Map<String, Object>>) lambda.get(1); var kinds = new ArrayList<Object>(); var lifted = new ArrayList<Object>(); var reps = new ArrayList<Object>();
        for (var formal : formals) { var rep = (Map<?, ?>) formal.get("rep"); kinds.add(rep.get("kind")); lifted.add(formal.get("lifted")); reps.add(rep.get("primReps")); }
        assertEquals(List.of("long", "data", "object", "object", "data", "object", "data", "data"), kinds); var expectedLifted = new ArrayList<Boolean>(); expectedLifted.add(false); expectedLifted.addAll(Collections.nCopies(7, true)); assertEquals(expectedLifted, lifted);
        var expectedReps = new ArrayList<List<String>>(); expectedReps.add(List.of("IntRep")); expectedReps.addAll(Collections.nCopies(7, List.of("BoxedRep (Just Lifted)"))); assertEquals(expectedReps, reps);
        var closures = json(single(paths, value -> value.endsWith("/GHC.Internal.Heap.Closures.json"))); var callers = new LinkedHashMap<String, Integer>(); for (var value : bindings(closures)) { int count = references(value.get("expr"), id); if (count != 0) callers.put((String) value.get("id"), count); }
        assertEquals(Map.of("ghc-internal:GHC.Internal.Heap.Closures.$w$cshowsPrec4", 1, "ghc-internal:GHC.Internal.Heap.Closures.$w$cshowsPrec3", 13, "ghc-internal:GHC.Internal.Heap.Closures.$w$cshowsPrec1", 22), callers);
        var output = (String) receipt.get("nativeOutput"); var attempt = output.substring(0, output.indexOf("/logs/")); var source = "GHC/Internal/Heap/InfoTable/Types.hsc"; var generated = attempt + "/originals/generated/" + source.substring(0, source.length() - 4) + ".hs";
        assertTrue(((List<?>) json(attempt + "/originals/generated.json").get("sources")).contains(List.of(source, generated))); var layout = json(attempt + "/originals/target-layout.json"); int wordBytes = ((Number) layout.get("wordBytes")).intValue(); assertTrue(List.of(4, 8).contains(wordBytes)); assertEquals(wordBytes / 2, ((Number) layout.get("infoTablePtrsBytes")).intValue());
        var generatedText = Files.readString(contained(generated, false).toPath()); assertTrue(generatedText.startsWith("{-# LINE 1 \"" + source + "\" #-}")); var typeLines = new ArrayList<String>(); for (var line : generatedText.split("\\r\\n|\\n|\\r", -1)) if (line.startsWith("type HalfWord' = ")) typeLines.add(line); assertEquals(List.of("type HalfWord' = Word" + wordBytes * 4), typeLines);
        // Source identity/target preprocessing only, not the full Show closure.
    }
    @Test void freshOriginalEncodingSourcesResolveTheirExactStackCallers() throws Exception {
        var originals = (List<String>) manifest().get("originals");
        for (var row : new String[][]{{"GHC.Internal.Foreign.C.String.Encoding", "$wpeekCString", "GHC.Internal.InfoProv.Types.$wpeekInfoProv"}, {"GHC.Internal.IO.Encoding.UTF8", "utf2", "GHC.Internal.InfoProv.Types.$wpeekInfoProv"}, {"GHC.Internal.IO.Encoding", "getForeignEncoding", "GHC.Internal.ExecutionStack.Internal.stackFrames"}, {"GHC.Internal.ForeignPtr", "$winsertCFinalizer", "GHC.Internal.ExecutionStack.Internal.stackFrames"}}) {
            var full = json(single(originals, value -> value.endsWith("/" + row[0] + ".json"))); var id = "ghc-internal:" + row[0] + "." + row[1]; var original = binding(full, id); assertEquals(row[1], original.get("name")); var callerModuleName = row[2].substring(0, row[2].lastIndexOf('.'));
            var callerModule = json(single(originals, value -> value.endsWith("/" + callerModuleName + ".json"))); var caller = binding(callerModule, "ghc-internal:" + row[2]); assertTrue(references(caller.get("expr"), id) > 0, "Fresh original " + row[2] + " must reference " + id); assertEquals(original, binding(CoreModules.merge(List.of(full, callerModule)), id));
        }
        // Availability is not execution/admission of encoding or full error closures.
    }
    private Map<String, Map<String, Object>> modules(List<String> paths, List<String> names) throws Exception { var result = new LinkedHashMap<String, Map<String, Object>>(); for (var name : names) result.put(name, json(single(paths, value -> value.endsWith("/GHC.Internal." + name + ".json")))); return result; }
    private static Map<String, Map<String, Object>> mergedBindings(Map<String, Map<String, Object>> modules) { var result = new LinkedHashMap<String, Map<String, Object>>(); for (var value : bindings(CoreModules.merge(new ArrayList<>(modules.values())))) result.put((String) value.get("id"), value); return result; }
    @Test void freshOriginalEncodingTypesAndFailureWorkersResolveTheirExactCallers() throws Exception {
        var modules = modules((List<String>) manifest().get("originals"), List.of("IO.Encoding.Types", "IO.Encoding.Failure", "IO.Encoding.UTF8", "IO.Encoding", "Foreign.C.String.Encoding")); var bindings = mergedBindings(modules);
        for (var row : new String[][]{{"Types","close#","Foreign.C.String.Encoding.$wpeekCString"},{"Failure","recoverDecode5","IO.Encoding.UTF8.utf3"},{"Failure","recoverDecode3","IO.Encoding.UTF8.utf3"},{"Failure","recoverDecode2","IO.Encoding.UTF8.utf3"},{"Failure","recoverDecode#","IO.Encoding.UTF8.mkUTF8"},{"Failure","recoverEncode#","IO.Encoding.UTF8.mkUTF8"},{"Failure","codingFailureModeSuffix5","IO.Encoding.mkTextEncoding19"},{"Failure","codingFailureModeSuffix3","IO.Encoding.mkTextEncoding19"},{"Failure","codingFailureModeSuffix1","IO.Encoding.mkTextEncoding19"}}) {
            var occurrence = row[0].equals("Types") ? "$fld:BufferCodec#:" + row[1] : row[1]; var id = "ghc-internal:GHC.Internal.IO.Encoding." + row[0] + "." + occurrence; var original = binding(modules.get("IO.Encoding." + row[0]), id); assertEquals(row[1], original.get("name")); assertEquals(original, bindings.get(id), "Merge must preserve the exact source binding");
            var caller = Objects.requireNonNull(bindings.get("ghc-internal:GHC.Internal." + row[2])); assertEquals(1, references(caller.get("expr"), id), "Fresh original " + row[2] + " must reference " + id);
        }
        // Source identity and resolution, not recovery/error execution or admission.
    }
    @Test void freshOriginalEnumWorkerResolvesClosureTypeErrorWithoutAlias() throws Exception {
        var receipt = manifest(); assertEquals("e4dcf86915b01dcc732ed319fe02759858aea1534c68427826ba5f9c6908860f", hash(contained(sourceRoot + "GHC/Internal/Enum.hs", true))); var originals = (List<String>) receipt.get("originals"); var full = json(single(originals, value -> value.endsWith("/GHC.Internal.Enum.json"))); var id = "ghc-internal:GHC.Internal.Enum.$wtoEnumError";
        var original = binding(full, id); assertEquals("$wtoEnumError", original.get("name")); var callerModule = json(single(originals, value -> value.endsWith("/GHC.Internal.ClosureTypes.json"))); var caller = binding(callerModule, "ghc-internal:GHC.Internal.ClosureTypes.$wlvl"); assertEquals(1, references(caller.get("expr"), id), "Fresh original caller must resolve the exact Enum worker"); assertEquals(original, binding(CoreModules.merge(List.of(full, callerModule)), id));
        // Does not admit the cold ErrorCall/Typeable/backtrace dependency graph.
    }
    @Test void freshOriginalShowHelpersResolveExactHeapInfoTableReferences() throws Exception {
        var modules = modules((List<String>) manifest().get("originals"), List.of("Ptr", "Data.Either", "Word", "Heap.InfoTable.Types")); var merged = mergedBindings(modules);
        for (var row : new String[][]{{"Ptr","$fShowFunPtr","$w$cshowsPrec"},{"Data.Either","$fShowEither","$fShowStgInfoTable3"},{"Word","$fShowWord32","$fShowStgInfoTable4"},{"Word","$fShowWord8","$fShowStgInfoTable5"}}) {
            var id = "ghc-internal:GHC.Internal." + row[0] + "." + row[1]; var original = binding(modules.get(row[0]), id); assertEquals(row[1], original.get("name")); assertEquals(original, merged.get(id), "Merge must preserve the actual source binding"); var proof = (Map<?, ?>) original.get("rep"); assertEquals(List.of("BoxedRep (Just Lifted)"), proof.get("primReps")); assertEquals(row[0].equals("Data.Either") ? "closure" : "data", proof.get("kind")); var expression = (List<?>) original.get("expr"); assertEquals(row[0].equals("Data.Either") ? "lam" : "app", expression.get(0));
            if (!row[0].equals("Data.Either")) assertEquals(List.of("con", "ghc-internal:GHC.Internal.Show.C:Show"), take(expression.get(1), 2)); var caller = Objects.requireNonNull(merged.get("ghc-internal:GHC.Internal.Heap.InfoTable.Types." + row[2])); assertEquals(1, references(caller.get("expr"), id), "Original caller must name the exact helper: " + id);
        }
        // Exact identities resolve; whole Show/error execution is not claimed.
    }
    @Test void freshOriginalNumericHelpersResolveExactPointerShowReferences() throws Exception {
        var modules = modules((List<String>) manifest().get("originals"), List.of("Bignum.Integer", "Real", "Numeric", "Ptr")); var bindings = mergedBindings(modules); var caller = Objects.requireNonNull(bindings.get("ghc-internal:GHC.Internal.Ptr.$w$cshowsPrec"));
        for (var row : new String[][]{{"Bignum.Integer","integerFromWord#"},{"Real","$fIntegralInteger"},{"Numeric","showHex1"},{"Numeric","showIntAtBase"}}) {
            var id = "ghc-internal:GHC.Internal." + row[0] + "." + row[1]; var original = binding(modules.get(row[0]), id); assertEquals(row[1], original.get("name")); assertEquals(original, bindings.get(id)); assertEquals(1, references(caller.get("expr"), id), "Original pointer formatter must name " + id);
        }
        var conversion = (List<?>) bindings.get("ghc-internal:GHC.Internal.Bignum.Integer.integerFromWord#").get("expr"); assertEquals("lam", conversion.get(0)); var word = single((List<Map<String, Object>>) conversion.get(1), value -> true); assertEquals(false, word.get("lifted")); assertEquals(Map.of("kind", "long", "primReps", List.of("WordRep"), "evaluated", true), word.get("rep"));
        var base = (List<?>) bindings.get("ghc-internal:GHC.Internal.Numeric.showHex1").get("expr"); assertEquals("app", base.get(0)); assertEquals(List.of("con", "ghc-internal:GHC.Internal.Bignum.Integer.IS"), take(base.get(1), 2)); assertEquals(List.of("lit", "int", "16"), take(single((List<?>) base.get(2), value -> true), 3));
        var integral = (List<?>) bindings.get("ghc-internal:GHC.Internal.Real.$fIntegralInteger").get("expr"); assertEquals("app", integral.get(0)); assertEquals(List.of("con", "ghc-internal:GHC.Internal.Real.C:Integral"), take(integral.get(1), 2)); assertEquals(9, ((List<?>) integral.get(2)).size());
        // Not admission of the general BigNat/backend/error dependency closure.
    }
    @Test void freshOriginalClassesAndNumResolveExactIntegerCallerReferences() throws Exception {
        var modules = modules((List<String>) manifest().get("originals"), List.of("Classes", "Num", "Bignum.Integer", "Real")); var bindings = mergedBindings(modules);
        for (var row : new String[][]{{"Classes","compareInt#","Bignum.Integer.integerCompare"},{"Num","$fNumInteger","Real.$fRealInteger"}}) {
            var id = "ghc-internal:GHC.Internal." + row[0] + "." + row[1]; var original = binding(modules.get(row[0]), id); assertEquals(row[1], original.get("name")); assertEquals(original, bindings.get(id), "Merge must preserve the actual original binding"); var caller = Objects.requireNonNull(bindings.get("ghc-internal:GHC.Internal." + row[2])); assertEquals(1, references(caller.get("expr"), id), "Fresh original " + row[2] + " must still reference " + id);
        }
        var comparison = (List<?>) bindings.get("ghc-internal:GHC.Internal.Classes.compareInt#").get("expr"); assertEquals("lam", comparison.get(0)); var formals = (List<Map<String, Object>>) comparison.get(1); assertEquals(2, formals.size()); for (var formal : formals) { assertEquals(false, formal.get("lifted")); assertEquals(Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true), formal.get("rep")); }
        var dictionary = bindings.get("ghc-internal:GHC.Internal.Num.$fNumInteger"); assertEquals(Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true), dictionary.get("rep")); var construction = (List<?>) dictionary.get("expr"); assertEquals("app", construction.get(0)); assertEquals(List.of("con", "ghc-internal:GHC.Internal.Num.C:Num"), take(construction.get(1), 2)); assertEquals(7, ((List<?>) construction.get(2)).size());
        // Does not admit the full Integer dictionary, BigNat backend or error closure.
    }
    private static Map<String, Object> binder(String name, Map<String, Object> proof, boolean lifted) { return Map.of("id", name, "name", name, "rep", proof, "lifted", lifted); }
    private static List<?> variable(String name, Map<String, Object> proof) { return List.of("var", name, Map.of("rep", proof)); }
    @Test void freshOriginalUnsafeWorkerLinksWithoutAliasAndDefersItsAction() throws Exception {
        var receipt = manifest(); var paths = (List<String>) receipt.get("originals"); var full = json(single(paths, value -> value.endsWith("/GHC.Internal.IO.Unsafe.json"))); var id = "ghc-internal:GHC.Internal.IO.Unsafe.unsafeDupableInterleaveIO1"; var original = binding(full, id); assertEquals("unsafeDupableInterleaveIO1", original.get("name"));
        var executionStack = json(single(paths, value -> value.endsWith("/GHC.Internal.ExecutionStack.Internal.json"))); var caller = binding(executionStack, "ghc-internal:GHC.Internal.ExecutionStack.Internal.stackFrames"); assertEquals(2, references(caller.get("expr"), id), "Fresh original caller must resolve the exact worker, not an alias");
        var lambda = (List<?>) original.get("expr"); var formals = (List<Map<String, Object>>) lambda.get(1); var result = (Map<String, Object>) ((Map<?, ?>) lambda.get(3)).get("resultRep"); var fields = (List<Map<String, Object>>) result.get("components"); var closure = (Map<String, Object>) original.get("rep"); Map<String, Object> integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true); var scalar = List.of("lit", "int", "17", Map.of("rep", integer));
        for (boolean force : new boolean[]{false, true}) {
            // Only scalar consumers are synthetic; original worker/source/tuple/reachable bodies stay unchanged.
            var call = List.of("app", variable(id, closure), List.of(variable("action", (Map<String, Object>) formals.get(0).get("rep")), List.of("void", Map.of("rep", fields.get(0)))), List.of(true, false), false, false, Map.of("rep", result));
            Object answer = !force ? scalar : List.of("case", variable("value", fields.get(1)), "forced", List.of(Arrays.asList("default", null, List.of(), scalar)), Map.of("rep", integer, "binder", binder("forced", fields.get(1), true)));
            var body = List.of("case", call, "pair", List.of(List.of("data", "ghc-internal:GHC.Internal.Types.(#,#)", List.of("state", "value"), answer, Map.of("binders", List.of(binder("state", fields.get(0), false), binder("value", fields.get(1), true))))), Map.of("rep", integer, "binder", binder("pair", result, false)));
            var name = force ? "demand" : "discard"; Map<String, Object> entry = Map.of("id", name, "name", name, "lifted", true, "rep", closure, "expr", List.of("lam", List.of(plus(plus(formals.get(0), "id", "action"), "name", "action")), body, Map.of("rep", closure, "resultRep", integer)));
            var module = plus(full, "bindings", List.of(original, entry)); assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(plus(module, "bindings", List.of(entry)), (String) entry.get("id"), true)); var linked = plus(CoreModules.reachable(module, (String) entry.get("id"), true), "instrument", true); assertEquals(original, binding(linked, id));
            for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var action = new DeferredAction(language, new TupleShape(CoreRepresentations.parse(result), language)); var argument = new Closure(null, 1, action.getCallTarget()); var target = program.entryTarget((String) entry.get("id"));
                    class Check { void run(boolean compiled) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); int calls = action.calls; assertEquals(17L, Calls.target(target, new Object[]{0L, argument})); assertEquals(calls + (force ? 1 : 0), action.calls);
                        if (compiled) { assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); valid(target, backend + "/" + force + "/inlining=" + inlining); }
                        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                    } }
                    var check = new Check(); for (int i = 0; i < 2; i++) check.run(false); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, backend + " installation"); for (int i = 0; i < 2; i++) check.run(true);
                } finally { context.leave(); }
            }
        }
    }
    @Test void manifestRejectsMissingOrForgedProvenanceBeforeConsumingArtifacts() throws Exception {
        var value = manifest(); var inputs = (Map<String, String>) value.get("inputHashes"); var artifacts = (Map<String, String>) value.get("artifactHashes"); assertEquals(99, requiredInputs().size()); assertEquals(95, artifacts.size());
        for (var path : requiredInputs()) assertThrows(IllegalArgumentException.class, () -> checked(plus(value, "inputHashes", minus(inputs, path))), path);
        for (var path : artifacts.keySet()) assertThrows(IllegalArgumentException.class, () -> checked(plus(value, "artifactHashes", minus(artifacts, path))), path);
        for (var path : pinned().keySet()) assertThrows(IllegalArgumentException.class, () -> checked(plus(value, "inputHashes", plus(inputs, path, "0".repeat(64)))), path);
        for (var row : new Object[][]{{"fresh",true},{"installedArtifactsHashed",true},{"schema",2L},{"ghc","other"},{"limit","Full original decoder supported"}}) assertThrows(IllegalArgumentException.class, () -> checked(plus(value, (String) row[0], row[1])));
        for (var field : List.of("inputHashes", "artifactHashes")) { var hashes = field.equals("inputHashes") ? inputs : artifacts; assertThrows(IllegalArgumentException.class, () -> checked(plus(value, field, plus(hashes, "/installed/ghc", "0".repeat(64))))); assertThrows(IllegalArgumentException.class, () -> checked(plus(value, field, plus(hashes, hashes.keySet().iterator().next(), "0".repeat(64))))); }
        var commands = (List<Map<String, Object>>) value.get("commands"); assertThrows(IllegalArgumentException.class, () -> { var changed = new ArrayList<>(commands); changed.set(0, plus(commands.get(0), "exit", 1L)); checked(plus(value, "commands", changed)); });
        assertThrows(IllegalArgumentException.class, () -> { var originals = (List<?>) value.get("originals"); checked(plus(value, "originals", originals.subList(0, originals.size() - 1))); });
    }
    @Test void artifactAndInputSymlinksCannotEscapeBeforeHashing() throws Exception {
        var outside = Files.createTempDirectory("stack-formatter-outside-"); var directory = Files.createTempDirectory(new File(root, prefix).toPath(), "containment-"); var link = directory.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside); var path = root.toPath().relativize(link).toString().replace(File.separatorChar, '/') + "/unread-file";
            for (boolean input : new boolean[]{false, true}) assertThrows(IllegalArgumentException.class, () -> contained(path, input)); for (var bad : List.of("/installed/ghc", prefix + "../outside", prefix + "./outside")) assertThrows(IllegalArgumentException.class, () -> contained(bad, false));
        } finally { Files.deleteIfExists(link); Files.delete(directory); Files.delete(outside); }
    }
    @Test void freshOriginalFormatterMatchesNativeCodePointsBeforeAndAfterExplicitCompilation() throws Exception {
        var manifest = manifest(); var rows = rows(manifest);
        for (var item : ((Map<String, String>) manifest.get("stages")).entrySet()) {
            var stage = item.getKey(); var module = linked(manifest, stage, item.getValue());
            for (var backend : List.of("ast", "bytecode")) for (boolean inlining : new boolean[]{false, true}) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); var target = program.entryTarget("formatOriginal");
                    class Check { boolean compiled; void run(Row row) throws Exception {
                        var label = stage + "/" + backend + "/inlining=" + inlining + "/" + row.row() + "/" + row.index(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(row.result(), Calls.target(target, new Object[]{0L, row.row(), row.index()}), label);
                        if (compiled) { assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label); valid(target, label); }
                        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
                    } }
                    var check = new Check(); for (int i = 0; i < 2; i++) for (var row : rows) check.run(row); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, stage + "/" + backend + " installation"); check.compiled = true;
                    for (var row : rows) check.run(row); for (var row : rows.reversed()) check.run(row); // First installed call has no settling execution.
                } finally { context.leave(); }
            }
        }
    }
}
