// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.Unit.INSTANCE;

@SuppressWarnings("unchecked")
public class PinnedAddressTest {
    private Expr literal(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final String directory = "build/pinned-addresses";
    private final Map<String, Long> entries = ordered(
        "pinnedBytes", 3L, "alignedBytes", 4L, "keepAliveWord8", 1L, "keepAliveLazy", 1L, "fingerprintByte", 3L);
    private final Map<String, Long> frontiers = ordered("publicFingerprintByte", 3L, "publicFingerprintRoundtrip", 3L);
    private final Map<String, Long> guestCalls =
        Map.of("pinnedBytes", 4L, "alignedBytes", 4L, "keepAliveWord8", 3L, "keepAliveLazy", 3L, "fingerprintByte", 3L);
    private final List<Long> values = List.of(
        Long.MIN_VALUE, -257L, -256L, -1L, 0L, 1L, 127L, 128L, 255L, 256L, 257L, 0x0123456789abcdefL, Long.MAX_VALUE);
    private final List<Long> words =
        List.of(Long.MIN_VALUE, -1L, 0L, 1L, 0x0123456789abcdefL, 0x7f0080ff0102fe03L, Long.MAX_VALUE);
    private final List<Long> sizes = List.of(0L, 1L, 2L, 3L, 8L, 16L, 17L, 31L, 64L);
    private final List<String> negatives = List.of("read-word-not-word8", "write-word-not-word8",
        "read-address-is-word", "read-state-is-int", "read-offset-is-word", "contents-lifted-array",
        "contents-result-is-word", "allocation-size-is-word", "allocation-state-is-int", "aligned-alignment-is-word",
        "keepalive-state-is-int", "keepalive-result-word-not-word8");
    private static <T> Map<String, T> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, T>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (T) pairs[i + 1]);
        return map;
    }
    private static Map<String, Object> plus(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }
    private Set<String> allEntries() {
        var all = new LinkedHashSet<>(entries.keySet());
        all.addAll(frontiers.keySet());
        return all;
    }
    private List<String> auditEntries() {
        var all = new ArrayList<>(allEntries());
        for (String negative : negatives) all.add("negative-" + negative);
        return all;
    }
    private Map<String, Object> report(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private record Case(String name, List<Long> arguments) {}
    private List<Case> domain() {
        var result = new ArrayList<Case>();
        for (long size : sizes)
            for (long offset = 0; offset < (size == 0 ? 1 : size); offset++)
                for (long raw : values) {
                    result.add(new Case("pinnedBytes", List.of(size, offset, raw)));
                    for (long alignment : new long[] {8, 16})
                        result.add(new Case("alignedBytes", List.of(size, alignment, offset, raw)));
                }
        for (String name : List.of("keepAliveWord8", "keepAliveLazy"))
            for (long raw : values) result.add(new Case(name, List.of(raw)));
        for (long high : words)
            for (long low : words) {
                for (long index = 0; index <= 15; index++)
                    for (String name : List.of("fingerprintByte", "publicFingerprintByte"))
                        result.add(new Case(name, List.of(high, low, index)));
                for (long index = 0; index <= 1; index++)
                    result.add(new Case("publicFingerprintRoundtrip", List.of(high, low, index)));
            }
        return result;
    }
    private long expected(String name, List<?> arguments) {
        assertTrue(entries.containsKey(name) || frontiers.containsKey(name), "Unknown entry");
        long arity = entries.containsKey(name) ? entries.get(name) : frontiers.get(name);
        assertEquals(arity, (long) arguments.size(), "Host arity");
        assertTrue(arguments.stream().allMatch(it -> it instanceof Long), "Native machine Int domain");
        var args = (List<Long>) arguments;
        if (List.of("pinnedBytes", "alignedBytes").contains(name)) {
            long size = args.get(0), offset = args.get(args.size() - 2), raw = args.getLast();
            if (name.equals("alignedBytes"))
                assertTrue(List.of(8L, 16L).contains(args.get(1)), "Native alignment domain");
            assertTrue(size >= 0 && size <= 64 && (size == 0 ? offset == 0 : offset >= 0 && offset < size),
                "Native bounds domain");
            if (size == 0)
                return 0;
            long before = Math.floorMod(raw, 256L), after = before < 128 ? before + 128 : before - 128,
                 first = offset == 0 ? after : 11L, last = offset == size - 1 ? after : 13L;
            return size * 19 + before * 257 + after * 65537 + first * 17 + last * 23;
        }
        if (List.of("keepAliveWord8", "keepAliveLazy").contains(name)) {
            long before = Math.floorMod(args.get(0), 256L);
            int delta = name.equals("keepAliveWord8") ? 7 : 11;
            return before * 257 + (before + delta) % 256;
        }
        long index = args.get(2);
        if (name.equals("publicFingerprintRoundtrip")) {
            assertTrue(index >= 0 && index <= 1);
            return args.get((int) index);
        }
        assertTrue(index >= 0 && index <= 15, "Byte selector domain");
        return ByteBuffer.allocate(16)
                   .order(ByteOrder.BIG_ENDIAN)
                   .putLong(args.get(0))
                   .putLong(args.get(1))
                   .array()[(int) index]
            & 255L;
    }
    private List<List<String>> checkedRows(String text) {
        return checkedRows(text, domain());
    }
    private List<List<String>> checkedRows(String text, List<Case> cases) {
        assertEquals(7269, cases.size());
        assertEquals(cases.size(), new HashSet<>(cases).size());
        var rows = Arrays.stream(text.split("\\R", -1))
                       .filter(it -> !it.isEmpty())
                       .map(it -> List.of(it.split("\t", -1)))
                       .toList();
        assertEquals(cases.size(), rows.size(), "Exact native corpus size");
        var seen = new HashSet<Case>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            assertTrue(row.size() >= 3);
            var actual = new Case(row.get(0), row.subList(1, row.size() - 1).stream().map(Long::valueOf).toList());
            assertTrue(seen.add(actual), "Duplicate native row");
            assertEquals(cases.get(index), actual, "Exact ordered native domain");
            assertEquals(expected(actual.name, actual.arguments), Long.parseLong(row.getLast()),
                "Native/model mismatch: " + row);
        }
        return rows;
    }
    private Map<String, Set<String>> requiredHashes() throws Exception {
        var sources = new LinkedHashSet<>(List.of("t/fixtures/compiler/PinnedAddressAudit.hs",
            "t/fixtures/compiler/PinnedAddressAuditNative.hs", "t/haskell-fixtures/PinnedAddressFixtures.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal",
            "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
            "bin/audit-core.py", "bin/core-capabilities.json", "src/tools/primops/PrimopTools.hs",
            "src/main/resources/thc/scalar-primop-signatures.json"));
        try (var files = Files.list(root.resolve("src/compiler/THC"))) {
            files.filter(it -> it.toString().endsWith(".hs"))
                .map(root::relativize)
                .map(Path::toString)
                .forEach(sources::add);
        }
        try (var files = Files.list(root.resolve("bin"))) {
            files.filter(it -> it.getFileName().toString().startsWith("core_") && it.toString().endsWith(".py"))
                .map(root::relativize)
                .map(Path::toString)
                .forEach(sources::add);
        }
        var commands = new ArrayList<>(List.of("native-build", "native-oracle"));
        for (String stage : List.of("pre", "post")) {
            commands.add(stage + "-export");
            for (String name : auditEntries()) commands.add(stage + "-" + name + "-audit");
        }
        var artifacts = new LinkedHashSet<String>();
        for (String name : List.of("requests.tsv", "expected.tsv", "oracle.tsv", "structure-controls.json"))
            artifacts.add(directory + "/" + name);
        for (String name :
            List.of("pinned-address-oracle", "Main.hi", "Main.o", "PinnedAddressAudit.hi", "PinnedAddressAudit.o"))
            artifacts.add(directory + "/native/" + name);
        for (String stage : List.of("pre", "post")) {
            artifacts.addAll(List.of(directory + "/" + stage + "/core/PinnedAddressAudit.cbd",
                directory + "/" + stage + "/core/THC.InterfaceClosure.cbd",
                directory + "/" + stage + "/core/PinnedAddressAudit.json",
                directory + "/" + stage + "/core/THC.InterfaceClosure.json",
                directory + "/" + stage + "/negative-proofs.json"));
            for (String name : auditEntries()) artifacts.add(directory + "/" + stage + "/" + name + ".audit.json");
            for (String name : negatives)
                for (int i = 0; i <= 1; i++)
                    artifacts.add(directory + "/" + stage + "/negative/" + name + "-" + i + ".cbd");
        }
        for (String command : commands)
            for (String suffix : List.of("stdout", "stderr", "command.json"))
                artifacts.add(directory + "/commands/" + command + "." + suffix);
        return Map.of("inputHashes", sources, "artifactHashes", artifacts);
    }
    private void verifyEvidence(Map<String, Object> manifest) throws Exception {
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals(true, manifest.get("strictAccepted"));
        assertEquals("full", manifest.get("mode"));
        assertEquals(7269L, manifest.get("nativeRows"));
        assertEquals(7269L, manifest.get("modelRows"));
        assertEquals(entries, manifest.get("entries"));
        assertEquals(frontiers, manifest.get("publicFrontiers"));
        assertEquals(guestCalls, manifest.get("expectedGuestCallsByEntry"));
        assertEquals("big", manifest.get("fingerprintByteOrder"));
        assertEquals(
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("nativeByteOrder"));
        var stages = new LinkedHashMap<String, List<String>>();
        for (String stage : List.of("pre", "post"))
            stages.put(stage,
                List.of(directory + "/" + stage + "/core/PinnedAddressAudit.cbd",
                    directory + "/" + stage + "/core/THC.InterfaceClosure.cbd"));
        assertEquals(stages, manifest.get("stages"));
        for (var item : requiredHashes().entrySet()) {
            var hashes = (Map<String, String>) manifest.get(item.getKey());
            assertEquals(item.getValue(), hashes.keySet(), item.getKey() + " exact inventory");
        }
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), actual, "Stale pinned-address input " + hash.getKey());
            }
        var cases = domain();
        assertEquals(Map.of("pinnedBytes", 1859L, "alignedBytes", 3718L, "keepAliveWord8", 13L, "keepAliveLazy", 13L,
                         "fingerprintByte", 784L, "publicFingerprintByte", 784L, "publicFingerprintRoundtrip", 98L),
            manifest.get("rowCounts"));
        assertEquals(
            cases.stream()
                .map(it
                    -> Map.of("entry", it.name, "arguments", it.arguments, "expected", expected(it.name, it.arguments)))
                .toList(),
            manifest.get("rows"));
        var controls = report(directory + "/structure-controls.json");
        assertEquals(3L, controls.get("acceptedBaselineGuestCalls"));
        assertEquals(List.of("host-arity", "state-rep", "state-flag", "continuation-rep", "result", "hidden-lambda",
                         "global", "rejected-proof-baseline"),
            controls.get("rejected"));
        var checkedStructures = (Map<String, Map<String, Object>>) manifest.get("checkedGuestStructureByStage");
        assertEquals(stages.keySet()
                         .stream()
                         .flatMap(stage -> entries.keySet().stream().map(it -> stage + "/" + it))
                         .collect(Collectors.toSet()),
            checkedStructures.keySet());
        var recordedAudits = (Map<String, Map<String, Map<String, Object>>>) manifest.get("audits");
        var negativeProofs = (Map<String, Map<String, Map<String, Object>>>) manifest.get("negativeProofs");
        var keepSites = (Map<String, List<Map<String, Object>>>) manifest.get("keepAliveSites");
        assertEquals(stages.keySet(), recordedAudits.keySet());
        assertEquals(stages.keySet(), negativeProofs.keySet());
        assertEquals(stages.keySet(), keepSites.keySet());
        for (String stage : stages.keySet()) {
            var core = thc.CoreCbdFixtures.read(root.resolve(stages.get(stage).getFirst()));
            assertEquals("9.14.1", core.get("ghc"));
            assertEquals(
                stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep",
                core.get("boundary"));
            assertEquals(allEntries(), recordedAudits.get(stage).keySet());
            assertFalse(keepSites.get(stage).isEmpty());
            for (String name : allEntries()) {
                var audit = report(directory + "/" + stage + "/" + name + ".audit.json");
                assertEquals(audit, recordedAudits.get(stage).get(name));
                verifyAudit(name, audit);
                if (entries.containsKey(name)) {
                    var structure = checkedStructures.get(stage + "/" + name);
                    assertEquals(guestCalls.get(name), structure.get("guestCalls"));
                    assertEquals(
                        guestCalls.get(name).longValue(), (long) ((List<?>) structure.get("lambdaFormals")).size());
                    assertEquals(
                        name.equals("keepAliveLazy") ? "keptBottom" : null, structure.get("lazyUncalledGlobal"));
                }
            }
            assertEquals(new HashSet<>(negatives), negativeProofs.get(stage).keySet());
            assertEquals(negativeProofs.get(stage), report(directory + "/" + stage + "/negative-proofs.json"));
            for (String label : negatives) {
                var audit = report(directory + "/" + stage + "/negative-" + label + ".audit.json");
                assertEquals(false, audit.get("accepted"));
                assertTrue(!((List<?>) audit.get("issues")).isEmpty());
                assertEquals(audit.get("issues"), negativeProofs.get(stage).get(label).get("issues"));
                assertEquals(1L,
                    report(directory + "/commands/" + stage + "-negative-" + label + "-audit.command.json")
                        .get("exit"));
            }
        }
    }
    private void verifyAudit(String name, Map<String, Object> audit) {
        assertEquals(entries.containsKey(name), audit.get("accepted"));
        var missing = ((List<Map<String, Object>>) audit.get("missingGlobals"))
                          .stream()
                          .map(it -> {
                              String id = (String) it.get("id");
                              return id.substring(id.indexOf(':') + 1);
                          })
                          .collect(Collectors.toSet());
        if (entries.containsKey(name)) {
            assertTrue(missing.isEmpty());
            assertEquals(List.of(), audit.get("issues"));
        } else {
            var required = new HashSet<>(Set.of("GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$wpokeW64"));
            if (name.equals("publicFingerprintRoundtrip"))
                required.add("GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$wpeekW64");
            assertTrue(missing.containsAll(required), "Original public Storable frontier");
        }
    }
    @Test
    public void independentModelChecksEveryNativeByteAndStrictDomains() throws Exception {
        verifyEvidence(manifest());
        var cases = domain();
        String oracle = Files.readString(root.resolve(directory + "/oracle.tsv"));
        checkedRows(oracle);
        assertEquals(oracle, Files.readString(root.resolve(directory + "/expected.tsv")));
        assertEquals(cases.stream()
                         .map(it
                             -> it.name + "\t"
                                 + it.arguments.stream().map(String::valueOf).collect(Collectors.joining("\t")) + "\n")
                         .collect(Collectors.joining()),
            Files.readString(root.resolve(directory + "/requests.tsv")));
        for (long high : words)
            for (long low : words)
                for (long index = 0; index <= 15; index++)
                    for (String name : List.of("fingerprintByte", "publicFingerprintByte"))
                        assertEquals(((index < 8 ? high : low) >>> (8 * (7 - (int) index % 8))) & 255,
                            expected(name, List.of(high, low, index)));
        for (String name : List.of("keepAliveWord8", "keepAliveLazy")) {
            long delta = name.equals("keepAliveWord8") ? 7 : 11;
            for (long x = 0; x <= 255; x++) {
                long result = expected(name, List.of(x));
                assertEquals(x * 257 + (x + delta) % 256, result);
                assertNotEquals(x * 258, result);
                assertNotEquals(((x + delta) % 256) * 257 + (x + 2 * delta) % 256, result);
            }
        }
        var invalid = List.of(Map.entry("pinnedBytes", List.of(-1L, 0L, 0L)),
            Map.entry("pinnedBytes", List.of(0L, 1L, 0L)), Map.entry("pinnedBytes", List.of(1L, 1L, 0L)),
            Map.entry("alignedBytes", List.of(1L, 3L, 0L, 0L)), Map.entry("alignedBytes", List.of(1L, 0L, 0L, 0L)),
            Map.entry("fingerprintByte", List.of(0L, 0L, 16L)), Map.entry("fingerprintByte", List.of(0L, 0L, -1L)),
            Map.entry("publicFingerprintRoundtrip", List.of(0L, 0L, 2L)),
            Map.entry("keepAliveWord8", List.of(BigInteger.ONE.shiftLeft(63))),
            Map.entry("keepAliveLazy", List.of(true)), Map.entry("unknown", List.of(0L)),
            Map.entry("keepAliveWord8", List.of()));
        for (var item : invalid) assertThrows(AssertionError.class, () -> expected(item.getKey(), item.getValue()));
    }
    @Test
    public void corpusRejectsMissingDuplicateUnknownReorderedAndMismatchedRows() throws Exception {
        String text = Files.readString(root.resolve(directory + "/oracle.tsv"));
        var lines = Arrays.stream(text.split("\\R", -1)).filter(it -> !it.isEmpty()).toList();
        checkedRows(text);
        String tail = String.join("\n", lines.subList(1, lines.size()));
        var swapped = new ArrayList<>(lines);
        Collections.swap(swapped, 0, 1);
        for (String bad :
            List.of(text + lines.getFirst() + "\n", tail, "unknown\t0\t0\n", lines.getLast() + "\n" + tail,
                "unknown\t" + lines.getFirst().substring(lines.getFirst().indexOf('\t') + 1) + "\n" + tail,
                String.join("\n", swapped),
                lines.getFirst().substring(0, lines.getFirst().lastIndexOf('\t')) + "\t99999\n" + tail))
            assertThrows(AssertionError.class, () -> checkedRows(bad));
        var reordered = new ArrayList<>(domain().subList(1, domain().size()));
        reordered.add(domain().getFirst());
        assertThrows(AssertionError.class, () -> checkedRows(text, reordered));
    }
    @Test
    public void evidenceRejectsMissingHashesAndChangedCoverage() throws Exception {
        var good = manifest();
        verifyEvidence(good);
        Map<String, Object> mutations = ordered("nativeRows", 7268L, "modelRows", 0L, "strictAccepted", false, "mode",
            "native-only", "entries", Map.of(), "publicFrontiers", Map.of(), "expectedGuestCallsByEntry", Map.of(),
            "stages", Map.of(), "checkedGuestStructureByStage", Map.of(), "negativeProofs", Map.of(), "audits",
            Map.of(), "keepAliveSites", Map.of(), "rows", List.of(), "rowCounts", Map.of());
        for (var item : mutations.entrySet())
            assertThrows(
                AssertionError.class, () -> verifyEvidence(plus(good, item.getKey(), item.getValue())), item.getKey());
        for (String kind : List.of("inputHashes", "artifactHashes")) {
            var records = (Map<String, String>) good.get(kind);
            for (String path : records.keySet()) {
                var changed = new LinkedHashMap<>(records);
                changed.remove(path);
                assertThrows(AssertionError.class, () -> verifyEvidence(plus(good, kind, changed)), path);
            }
            var changed = new LinkedHashMap<>(records);
            changed.put(records.keySet().iterator().next(), "0".repeat(64));
            assertThrows(AssertionError.class, () -> verifyEvidence(plus(good, kind, changed)));
        }
        for (String stage : List.of("pre", "post"))
            for (String name : frontiers.keySet()) {
                var audit = report(directory + "/" + stage + "/" + name + ".audit.json");
                assertThrows(AssertionError.class, () -> verifyAudit(name, plus(audit, "accepted", true)));
                assertThrows(AssertionError.class, () -> verifyAudit(name, plus(audit, "missingGlobals", List.of())));
            }
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .build();
    }
    private Map<String, Object> manifest() throws Exception {
        return report("build/pinned-addresses/manifest.json");
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (String path : paths) modules.add(thc.CoreCbdFixtures.read(root.resolve(path)));
        return CoreModules.merge(modules);
    }
    private int inlinedStateApplications(Object value) {
        int count = 0;
        if (value instanceof Map<?, ?> map) {
            for (var child : map.values()) count += inlinedStateApplications(child);
        } else if (value instanceof List<?> node) {
            var inline = CoreStateApplications.inline(node);
            if (inline != null) {
                var function = (List<?>) node.get(1);
                var formals = (List<Map<String, Object>>) function.get(1);
                assertEquals(1, formals.size());
                var formal = formals.getFirst();
                assertEquals("State# RealWorld", formal.get("type"));
                assertEquals(false, formal.get("lifted"));
                assertFalse(Boolean.TRUE.equals(formal.get("coercion")));
                var proof = CoreRepresentations.binder(formal);
                assertEquals(CoreKind.VOID, proof.getKind()); assertFalse(proof.isAggregate());
                assertEquals(List.of(), proof.getPrimReps());
                var arguments = (List<?>) node.get(2); assertEquals(1, arguments.size());
                var state = (List<?>) arguments.getFirst(); assertEquals("void", state.getFirst());
                var actual = CoreRepresentations.expression(state);
                assertEquals(CoreKind.VOID, actual.getKind()); assertFalse(actual.isAggregate());
                assertEquals(List.of(), actual.getPrimReps()); assertEquals(List.of(false), node.get(3));
                assertEquals("case", inline.getFirst()); assertSame(state, inline.get(1));
                assertEquals(formal.get("id"), inline.get(2));
                var metadata = CoreRepresentations.metadata(inline);
                assertSame(formal, metadata.get("binder"));
                CoreRepresentations.metadata(node).forEach((key, original) -> assertSame(original, metadata.get(key), key));
                var alternative = (List<?>) ((List<?>) inline.get(3)).getFirst();
                assertEquals("default", alternative.getFirst()); assertSame(function.get(2), alternative.get(3));
                count++;
            }
            for (var child : node) count += inlinedStateApplications(child);
        }
        return count;
    }
    private void joinIds(Object value, Set<String> ids) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("joinValueArity") instanceof Number arity && arity.longValue() > 0) {
                assertEquals("lam", ((List<?>) map.get("expr")).getFirst());
                assertTrue(ids.add((String) map.get("id")), "Duplicate source join binder");
            }
            for (var child : map.values()) joinIds(child, ids);
        } else if (value instanceof List<?> list) for (var child : list) joinIds(child, ids);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "installed");
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var result = new ArrayList<RootCallTarget>();
        visit(entry, seen, result);
        return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target))
            return;
        var node = target.getRootNode();
        var roots = new ArrayList<Node>();
        roots.add(node);
        if (node instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var cached = argument.asCachedNode();
                        if (cached != null)
                            roots.add(cached);
                    }
        for (var root : roots)
            for (var call : NodeUtil.findAllNodeInstances(root, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, result);
        result.add(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    @Test
    public void nativePinnedAddressesAndLazyKeepAliveWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativePinnedAddressesAndLazyKeepAliveAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        verifyEvidence(manifest);
        var rows = checkedRows(Files.readString(root.resolve("build/pinned-addresses/oracle.tsv")))
                       .stream()
                       .collect(Collectors.groupingBy(it -> it.get(0), LinkedHashMap::new, Collectors.toList()));
        var allNames = new HashSet<>(entries.keySet());
        allNames.addAll(((Map<String, ?>) manifest.get("publicFrontiers")).keySet());
        assertEquals(allNames, rows.keySet());
        assertEquals(
            ((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (var named : entries.entrySet()) {
                String name = named.getKey();
                long arity = named.getValue();
                var cases = rows.get(name);
                var audit = report("build/pinned-addresses/" + stage.getKey() + "/" + name + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = CoreModules.reachable(merged(stage.getValue()), "main:PinnedAddressAudit." + name);
                            var binding = ((List<Map<String, Object>>) linked.get("bindings")).stream()
                                .filter(value -> ("main:PinnedAddressAudit." + name).equals(value.get("id"))).findFirst().orElseThrow();
                            var body = (List<?>) ((List<?>) binding.get("expr")).get(2);
                            assertNotNull(CoreStateApplications.inline(body), "The immediate State# action is reduced in its owning scope");
                            int reductions = inlinedStateApplications(linked.get("bindings"));
                            assertEquals(1, reductions, "Exactly the immediate State# action is inlined");
                            long guestEntries = guestCalls.get(name) - reductions;
                            var joinIds = new HashSet<String>(); joinIds(linked.get("bindings"), joinIds);
                            var structures = (Map<String, Map<String, Object>>) manifest.get("checkedGuestStructureByStage");
                            assertEquals(((Number) structures.get(stage.getKey() + "/" + name).get("localJoinPrefixes")).intValue(),
                                joinIds.size(), "Original source join prefix inventory");
                            var program = program(language, plus(linked, "instrument", true), backend);
                            var function = context.asValue(new EntryValue(program, "main:PinnedAddressAudit." + name, (int) arity));
                            var host = program.hostEntryTarget((int) arity);
                            var original = program.entryTarget("main:PinnedAddressAudit." + name);
                            String label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
                            java.util.function.Consumer<List<String>> check = row -> {
                                var arguments = row.subList(1, row.size() - 1).stream().map(Long::valueOf).toArray();
                                assertEquals(Long.parseLong(row.getLast()), function.execute(arguments).asLong(),
                                    label + "/" + row);
                                released(language);
                            };
                            cases.forEach(check);
                            var targets = activeTargets(host);
                            var regionAnchors = new HashSet<String>();
                            var regionOwners = new HashSet<BytecodeRoot>();
                            for (var target : targets) {
                                var root = target.getRootNode(); var rootName = root.getName();
                                if (rootName.startsWith("join region ")) {
                                    assertEquals("bytecode", backend); assertInstanceOf(BytecodeRoot.class, root);
                                    var anchor = rootName.substring("join region ".length());
                                    assertTrue(joinIds.contains(anchor), "Region must own a genuine source join");
                                    assertTrue(regionAnchors.add(anchor), "Duplicate physical join region");
                                    assertEquals(0L, ((BytecodeRoot) root).entryMask(), "Prepared region passes through its logical entry");
                                    var owners = new ArrayList<BytecodeRoot>();
                                    for (var candidate : targets)
                                        for (var region : NodeUtil.findAllNodeInstances(candidate.getRootNode(), BytecodeCaseRegion.class))
                                            for (var call : NodeUtil.findAllNodeInstances(region, DirectCallNode.class))
                                                if (call.getCurrentCallTarget() == target)
                                                    owners.add(assertInstanceOf(BytecodeRoot.class, region.getRootNode()));
                                    assertEquals(1, owners.size(), "Each prepared join region has one original inline owner");
                                    var owner = owners.getFirst(); assertTrue(targets.contains(owner.getCallTarget()));
                                    assertTrue(owner.useInlineCaseRegions(), "Original join body remains on its inline path");
                                    regionOwners.add(owner);
                                }
                            }
                            long physicalGuestEntries = guestEntries + regionAnchors.size();
                            assertSame(host, targets.getLast()); assertTrue(targets.contains(original));
                            assertSame(original, program.entryTarget("main:PinnedAddressAudit." + name));
                            assertEquals(physicalGuestEntries + 1, targets.size(),
                                label + " concrete guest roots plus host " + targets.stream().map(target -> target.getRootNode().getName()).toList());
                            for (var target : targets)
                                if (target != host)
                                    compile(target);
                            assertTrue(function.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                for (var owner : regionOwners) assertTrue(owner.useInlineCaseRegions(), label + " inline region assumption");
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                check.accept(row);
                                assertEquals(before + guestEntries,
                                    ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                                    label + " exact executed compiled guest entries");
                                for (var owner : regionOwners) assertTrue(owner.useInlineCaseRegions(), label + " retained inline region assumption");
                                assertEquals(targets, activeTargets(host), label + " active identities");
                                valid(original, label);
                                for (var target : targets) valid(target, label);
                            }
                            for (String counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label);
                            System.out.println("PinnedAddress PASS " + label + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
            }
    }
    @Test
    public void allocationChecksFullWidthSizeAndPowerOfTwoAlignment() {
        for (long size : new long[] {0, 1, 16, 129})
            for (long alignment : new long[] {1, 2, 8, 64, 4096}) {
                var allocation = PinnedMemory.allocate(size, alignment);
                assertEquals(size, allocation.getSize());
                assertTrue(allocation.isPinned());
                assertEquals(0L, Objects.requireNonNull(allocation.nativeSegment()).address() & (alignment - 1));
            }
        for (long size : new long[] {Long.MIN_VALUE, -1, (long) Integer.MAX_VALUE + 1, 1L << 32, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PinnedMemory.allocate(size, 8));
        for (long alignment : new long[] {Long.MIN_VALUE, -1, 0, 3, 7, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PinnedMemory.allocate(0, alignment));
    }
    private Expr operand(List<String> events, String name, Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return value;
            }
        };
    }
    @Test
    public void statePrecedesMemoryEffectsAndFailedReadsDoNotPublish() {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Object, null, null);
        var descriptor = builder.build();
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], descriptor);
        for (var operation :
            List.of(PinnedMemoryOp.NEW, PinnedMemoryOp.NEW_ALIGNED, PinnedMemoryOp.READ, PinnedMemoryOp.WRITE))
            for (boolean fail : new boolean[] {false, true}) {
                byte[] array = {11, 22};
                var address = ManagedAddress.fromByteArray(array);
                var sentinel = new Object();
                FrameAccess.write(frame, 0, sentinel);
                var events = new ArrayList<String>();
                var state = new Expr() {
                    @Override
                    public Object execute(VirtualFrame frame) {
                        events.add("state");
                        assertSame(sentinel, FrameAccess.read(frame, 0));
                        assertArrayEquals(new byte[] {11, 22}, array);
                        if (fail)
                            throw new RuntimeFault("State failed");
                        return INSTANCE;
                    }
                };
                Expr[] operands = switch (operation) {
                    case NEW -> new Expr[] {operand(events, "size", 2L), state};
                    case NEW_ALIGNED ->
                        new Expr[] {operand(events, "size", 2L), operand(events, "alignment", 8L), state};
                    case READ -> new Expr[] {operand(events, "address", address), operand(events, "offset", 1L), state};
                    default ->
                        new Expr[] {operand(events, "address", address), operand(events, "offset", 1L),
                            operand(events, "value", 511), state};
                };
                var expression =
                    new PinnedMemoryExpression(operation, CoreRepresentation.UNKNOWN, operands, false);
                Runnable run = () -> {
                    if (operation.getTuple())
                        expression.executeTuple(frame, new int[] {0}, 0);
                    else
                        expression.execute(frame);
                };
                if (fail) {
                    assertThrows(RuntimeFault.class, run::run);
                    assertSame(sentinel, FrameAccess.read(frame, 0));
                    assertArrayEquals(new byte[] {11, 22}, array);
                } else {
                    run.run();
                    switch (operation) {
                        case NEW, NEW_ALIGNED ->
                            assertEquals(2L, ((ManagedAllocation) FrameAccess.read(frame, 0)).getSize());
                        case READ -> assertEquals(22, FrameAccess.read(frame, 0));
                        default -> assertArrayEquals(new byte[] {11, -1}, array);
                    }
                }
                assertEquals(switch (operation) {
                    case NEW -> List.of("size", "state");
                    case NEW_ALIGNED -> List.of("size", "alignment", "state");
                    case READ -> List.of("address", "offset", "state");
                    default -> List.of("address", "offset", "value", "state");
                }, events);
            }
        for (long index : new long[] {-1, Long.MIN_VALUE, 2, 1L << 32, Long.MAX_VALUE}) {
            var sentinel = new Object();
            FrameAccess.write(frame, 0, sentinel);
            var expression = new PinnedMemoryExpression(PinnedMemoryOp.READ, CoreRepresentation.UNKNOWN,
                new Expr[] {literal(ManagedAddress.fromByteArray(new byte[] {3, 4})), literal(index),
                    literal(INSTANCE)},
                false);
            assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[] {0}, 0));
            assertSame(sentinel, FrameAccess.read(frame, 0));
        }
    }
    private Expr expression(List<String> events, String failure, String name, Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                if (failure.equals(name))
                    throw new RuntimeFault(name);
                return value;
            }
        };
    }
    @Test
    public void keepAliveEvaluatesKeptReferenceAndStateThenRunsActionExactlyOnce() throws UnexpectedResultException {
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        for (String failure : List.of("none", "state", "action")) {
            var events = new ArrayList<String>();
            var kept = new Object();
            var node = new KeepAliveExpression(expression(events, failure, "kept", kept),
                expression(events, failure, "state", INSTANCE), expression(events, failure, "action", 77L),
                CoreRepresentation.UNKNOWN);
            if (failure.equals("none"))
                assertEquals(77L, node.executeLong(frame));
            else
                assertThrows(RuntimeFault.class, () -> node.executeLong(frame));
            assertEquals(
                failure.equals("state") ? List.of("kept", "state") : List.of("kept", "state", "action"), events);
        }
    }
}
