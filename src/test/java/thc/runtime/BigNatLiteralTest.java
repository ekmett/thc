// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class BigNatLiteralTest {
    @Test void literalsUseTheInvokingStoragePolicy() {
        for (String storage : List.of("heap", "native")) for (String backend : List.of("ast", "bytecode"))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowNativeAccess(true)
                    .option("thc.ByteArrayStorage", storage).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (String decimal : List.of("0", "18446744073709551617", "340282366920938463472597979468622987265")) {
                        var body = literal(decimal, exact());
                        var source = map("constructors", list(), "bindings", list(
                            map("id", "value", "name", "value", "lifted", false, "rep", exact(), "expr", body),
                            map("id", "inline", "name", "inline", "lifted", true, "arity", 1,
                                "expr", list("lam", list(map("id", "unused", "lifted", false)), body))));
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                        Object global = program.entryValue("value");
                        assertSame(global, program.entryValue("value"));
                        Object inline = Calls.target(program.entryTarget("inline"), new Object[]{0L, 0L});
                        assertNotSame(global, inline);
                        for (Object value : List.of(global, inline)) {
                            var number = new BigInteger(decimal);
                            int length = (number.bitLength() + 63) / 64 * 8;
                            assertEquals(length, ManagedByteArray.sizeGuest(value));
                            for (int index = 0; index < length; index++) {
                                int bit = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : index / 8 * 8 + 7 - index % 8) * 8;
                                assertEquals(number.shiftRight(bit).and(BigInteger.valueOf(255)).intValue(), ManagedByteArray.readGuest(value, index, true));
                            }
                            var address = ManagedAddress.fromGuestByteArray(value);
                            if (storage.equals("native")) {
                                var allocation = assertInstanceOf(ManagedAllocation.class, value);
                                assertTrue(allocation.hasNativeStorage()); assertFalse(allocation.isPinned());
                                assertEquals(allocation.nativeSegment().address(), address.toNativeBits());
                            } else {
                                assertInstanceOf(byte[].class, value);
                                assertThrows(RuntimeFault.class, address::toNativeBits);
                            }
                            assertSame(value, ManagedByteArray.freezeGuest(value));
                            if (length != 0) {
                                ManagedByteArray.writeGuest(ManagedByteArray.freezeGuest(value), 0, 91);
                                assertEquals(91, address.readWord8(0));
                            }
                        }
                    }
                } finally { context.leave(); }
            }
    }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final List<String> ENTRIES = list("integerRoundTrip", "naturalRoundTrip", "integerLiteral", "naturalLiteral", "magnitudeSize", "magnitudeByte", "magnitudeWord", "magnitudeSign");
    private static final List<String> ARITHMETIC = list("integerAddFrontier", "naturalAddFrontier"), MODULES = list("BigNat", "Integer", "Natural");
    private static final String DIRECTORY = "build/bignat-literals";
    private static final List<BigInteger> VALUES = list("0", "1", "-1", "9223372036854775807", "9223372036854775808", "18446744073709551615", "18446744073709551616", "18446744073709551617",
        "170141183460469231731687303715884105727", "170141183460469231731687303715884105728", "340282366920938463463374607431768211456", "340282366920938463472597979468622987265",
        "-340282366920938463481821351505477763071", "6277101735386680763835789423207666416102355444464034512895", "6277101735386680763835789423207666416102355444464034512897",
        "57896044618658097711785492504343953926975274699741220483192166611388333031427").stream().map(BigInteger::new).toList();
    private static final List<Long> SEEDS = seeds();
    private static List<Long> seeds() { var seeds = new ArrayList<>(list(Long.MIN_VALUE, Long.MAX_VALUE, -1000L, -17L, -1L)); for (long i = 0; i <= 16; i++) seeds.add(i); seeds.addAll(list(31L, 1L << 32)); return seeds; }
    private static List<String> vendorSources() {
        var result = new ArrayList<String>(); for (var name : MODULES) for (var suffix : list(".hs", ".hs-boot")) result.add("nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Bignum/" + name + suffix);
        result.addAll(list("nih/pinned/ghc-9.14.1/libraries/ghc-internal/include/WordSize.h", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE")); return result;
    }
    private static <T> List<T> concat(List<T> first, List<T> second) { var result = new ArrayList<>(first); result.addAll(second); return result; }
    private Map<String, Object> evidence() throws Exception { return report(DIRECTORY + "/manifest.json"); }
    private Map<String, Object> report(String path) throws Exception { return object(Json.parse(Files.readString(new File(root, path).toPath()))); }
    private Map<String, Object> core(String path) throws Exception { return thc.CoreCbdFixtures.read(new File(root, path).toPath()); }
    private void verifyEvidence(Map<String, Object> manifest) throws Exception {
        assertEquals(1L, manifest.get("schema")); assertEquals(699L, manifest.get("nativeRows")); assertEquals(64L, manifest.get("wordBits"));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(ENTRIES, manifest.get("entries")); assertEquals(ARITHMETIC, manifest.get("arithmeticControls")); assertEquals(list("integerAddFrontier"), manifest.get("frontiers"));
        assertEquals(VALUES.stream().map(BigInteger::toString).toList(), manifest.get("values")); assertEquals(SEEDS, manifest.get("seeds"));
        var stages = new LinkedHashMap<String, List<String>>();
        for (var stage : list("pre", "post")) { var paths = new ArrayList<>(list(DIRECTORY + "/" + stage + "-core/BigNatLiteralAudit.cbd")); for (var module : MODULES) paths.add(DIRECTORY + "/boot/core/GHC.Internal.Bignum." + module + ".cbd"); stages.put(stage, paths); }
        assertEquals(stages, manifest.get("stages"));
        var sources = concat(vendorSources(), list("t/fixtures/compiler/BigNatLiteralAudit.hs", "t/fixtures/compiler/BigNatLiteralAuditNative.hs", "t/haskell-fixtures/BigNatLiteralFixtures.hs",
            "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-boot.py", "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
            "bin/audit-core.py", "bin/core-capabilities.json", "src/tools/primops/PrimopTools.hs", "src/main/resources/thc/scalar-primop-signatures.json"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) sources.add(root.toPath().relativize(file.toPath()).toString());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) sources.add(root.toPath().relativize(file.toPath()).toString());
        var commands = new ArrayList<>(list("plugin-build", "boot-export", "native-build", "native-oracle"));
        var auditNames = concat(concat(ENTRIES, ARITHMETIC), list("missing-source"));
        var artifacts = new ArrayList<>(list(DIRECTORY + "/requests.tsv", DIRECTORY + "/oracle.tsv", DIRECTORY + "/boot/boot-provenance.json"));
        for (var module : MODULES) for (var suffix : list(".cbd", ".json")) artifacts.add(DIRECTORY + "/boot/core/GHC.Internal.Bignum." + module + suffix);
        for (var name : list("bignat-literal-oracle", "Main.hi", "Main.o", "BigNatLiteralAudit.hi", "BigNatLiteralAudit.o")) artifacts.add(DIRECTORY + "/native/" + name);
        for (var stage : list("pre", "post")) {
            commands.add(stage + "-export"); for (var name : auditNames) commands.add(stage + "-" + name + "-audit");
            for (var module : list("BigNatLiteralAudit", "THC.InterfaceClosure")) artifacts.add(DIRECTORY + "/" + stage + "-core/" + module + ".cbd");
            for (var name : auditNames) artifacts.add(DIRECTORY + "/" + stage + "-" + name + ".audit.json");
        }
        for (var command : commands) for (var suffix : list("stdout", "stderr", "command.json")) artifacts.add(DIRECTORY + "/commands/" + command + "." + suffix);
        for (var kind : list("sources", "artifacts")) {
            var records = objects(manifest.get(kind));
            assertEquals((kind.equals("sources") ? sources : artifacts).stream().sorted().toList(), records.stream().map(item -> (String) item.get("path")).sorted().toList(), kind + " exact inventory");
            for (var item : records) assertEquals(item.get("sha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, (String) item.get("path")).toPath()))), "Stale BigNat preparation: " + item.get("path"));
        }
        var bootSources = objects(report(DIRECTORY + "/boot/boot-provenance.json").get("sources"));
        assertEquals(new HashSet<>(vendorSources()), bootSources.stream().map(item -> item.get("path")).collect(Collectors.toSet()));
        for (var source : bootSources) assertTrue(objects(manifest.get("sources")).stream().anyMatch(item -> Objects.equals(item.get("path"), source.get("path")) && Objects.equals(item.get("sha256"), source.get("sha256"))));
        var counts = object(manifest.get("sourceBindings")); assertEquals(new HashSet<>(MODULES), counts.keySet()); var originalIds = new HashSet<Object>();
        for (var name : MODULES) {
            var original = report(DIRECTORY + "/boot/core/GHC.Internal.Bignum." + name + ".json"); assertEquals("9.14.1", original.get("ghc"));
            assertEquals("optimized-Core-after-Tidy-before-CorePrep", original.get("boundary")); assertNotNull(original.get("sourceCore")); assertNotNull(original.get("sourceSpans"));
            var bindings = objects(core(DIRECTORY + "/boot/core/GHC.Internal.Bignum." + name + ".cbd").get("bindings")); assertEquals(counts.get(name), (long) bindings.size()); for (var binding : bindings) originalIds.add(binding.get("id"));
        }
        var coverage = object(manifest.get("coverage")); assertEquals(Set.of("pre", "post"), coverage.keySet());
        for (var stage : list("pre", "post")) {
            var publicModule = core(DIRECTORY + "/" + stage + "-core/BigNatLiteralAudit.cbd"); assertEquals("9.14.1", publicModule.get("ghc"));
            assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", publicModule.get("boundary"));
            var closure = objects(core(DIRECTORY + "/" + stage + "-core/THC.InterfaceClosure.cbd").get("bindings")); assertTrue(originalIds.containsAll(closure.stream().map(binding -> binding.get("id")).toList()));
            assertEquals(new HashSet<>(concat(ENTRIES, ARITHMETIC)), object(coverage.get(stage)).keySet());
            for (var name : concat(ENTRIES, ARITHMETIC)) {
                var audit = report(DIRECTORY + "/" + stage + "-" + name + ".audit.json"); verifyAudit(name, audit);
                assertEquals(map("accepted", audit.get("accepted"), "reachable", (long) expression(audit.get("reachableBindings")).size(), "issues", (long) expression(audit.get("issues")).size(), "missing", (long) expression(audit.get("missingGlobals")).size()), object(coverage.get(stage)).get(name));
            }
            verifyAudit("missing-source", report(DIRECTORY + "/" + stage + "-missing-source.audit.json"));
        }
    }
    private static void verifyAudit(String name, Map<String, Object> audit) {
        assertEquals(list(), audit.get("issues")); var missing = objects(audit.get("missingGlobals")).stream().map(binding -> binding.get("id")).sorted(Comparator.comparing(Object::toString)).toList();
        if (name.equals("missing-source")) {
            assertEquals(false, audit.get("accepted"));
            assertEquals(list("BigNat.bigNatZero", "Integer.integerToInt#", "Natural.naturalToWord#").stream().map(id -> "ghc-internal:GHC.Internal.Bignum." + id).toList(), missing);
        } else {
            assertEquals(!name.equals("integerAddFrontier"), audit.get("accepted")); assertEquals(name.equals("integerAddFrontier") ? list("ghc-internal:GHC.Internal.Prim.Exception.raiseUnderflow") : list(), missing);
            if (ARITHMETIC.contains(name)) {
                var wanted = new HashMap<Object, Integer>(); wanted.put("__gmpn_add", 2); wanted.put("__gmpn_add_1", 1);
                if (name.equals("integerAddFrontier")) { wanted.put("__gmpn_cmp", 1); wanted.put("__gmpn_sub", 1); }
                var actual = new HashMap<Object, Integer>(); for (var call : objects(audit.get("foreignCalls"))) actual.merge(call.get("symbol"), 1, Integer::sum); assertEquals(wanted, actual);
                assertEquals(name.equals("integerAddFrontier") ? 7 : 5, objects(audit.get("primitives")).stream().filter(prim -> "shrinkMutableByteArray#".equals(prim.get("name"))).mapToInt(prim -> expression(prim.get("uses")).size()).sum());
            }
        }
    }
    @Test void evidenceRejectsMissingHashesDomainsAndChangedFrontiers() throws Exception {
        var good = evidence(); verifyEvidence(good);
        var changes = map("nativeRows", 698L, "values", list(), "seeds", list(0L), "entries", ENTRIES.subList(0, ENTRIES.size() - 1), "stages", map(), "sourceBindings", map(), "coverage", map(), "frontiers", list(), "arithmeticControls", list());
        for (var change : changes.entrySet()) assertThrows(AssertionError.class, () -> verifyEvidence(with(good, change.getKey(), change.getValue())), change.getKey());
        for (var kind : list("sources", "artifacts")) {
            var records = objects(good.get(kind));
            for (var record : records) { var fewer = new ArrayList<>(records); fewer.remove(record); assertThrows(AssertionError.class, () -> verifyEvidence(with(good, kind, fewer)), kind + "/" + record.get("path")); }
            var corrupt = new ArrayList<>(records); corrupt.set(0, with(records.getFirst(), "sha256", "0".repeat(64))); assertThrows(AssertionError.class, () -> verifyEvidence(with(good, kind, corrupt)));
        }
        for (var stage : list("pre", "post")) for (var name : concat(ARITHMETIC, list("missing-source"))) {
            var audit = report(DIRECTORY + "/" + stage + "-" + name + ".audit.json");
            for (var change : map("accepted", !(Boolean) audit.get("accepted"), "issues", list("unexpected")).entrySet()) assertThrows(AssertionError.class, () -> verifyAudit(name, with(audit, change.getKey(), change.getValue())));
            if (!name.equals("naturalAddFrontier")) assertThrows(AssertionError.class, () -> verifyAudit(name, with(audit, "missingGlobals", list())));
            if (ARITHMETIC.contains(name)) for (var key : list("foreignCalls", "primitives")) assertThrows(AssertionError.class, () -> verifyAudit(name, with(audit, key, list())));
        }
    }
    private static Context context(boolean inlining) { return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private static void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private static void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "initial installation"); }
    private static void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(String name, long seed, long index, long expected) {}
    private static long expected(String name, long seed, long index, List<BigInteger> values) {
        var signed = values.get((int) (seed & 15)); var magnitude = signed.abs(); int length = ((magnitude.bitLength() + 63) / 64) * 8;
        return switch (name) {
            case "integerRoundTrip", "naturalRoundTrip" -> seed;
            case "integerLiteral" -> signed.longValue(); case "naturalLiteral" -> magnitude.longValue(); case "magnitudeSize" -> length;
            case "magnitudeSign" -> signed.signum() < 0 ? 1L : 0L;
            case "magnitudeWord" -> index < 0 || index >= length / 8 ? -1 : magnitude.shiftRight((int) index * 64).longValue();
            case "magnitudeByte" -> {
                if (index < 0 || index >= length) yield -1;
                long position = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : (index / 8) * 8 + 7 - index % 8;
                yield magnitude.shiftRight((int) position * 8).and(BigInteger.valueOf(255)).longValue();
            }
            default -> throw new IllegalStateException(name);
        };
    }
    @Test void originalIntegerNaturalConversionsWithInlining() throws Exception { nativeResults(true); }
    @Test void originalIntegerNaturalConversionsAcrossResidualCalls() throws Exception { nativeResults(false); }
    private void nativeResults(boolean inlining) throws Exception {
        var manifest = evidence(); verifyEvidence(manifest); var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, DIRECTORY + "/oracle.tsv").toPath())) { var p = line.split("\t"); rows.add(new Row(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]))); }
        var modeled = new ArrayList<Row>();
        for (var name : ENTRIES) for (long seed : SEEDS) {
            int length = ((VALUES.get((int) (seed & 15)).abs().bitLength() + 63) / 64) * 8;
            long first = name.equals("magnitudeByte") || name.equals("magnitudeWord") ? -1 : 0, last = name.equals("magnitudeByte") ? length : name.equals("magnitudeWord") ? length / 8 : 0;
            for (long index = first; index <= last; index++) modeled.add(new Row(name, seed, index, expected(name, seed, index, VALUES)));
        }
        assertEquals(modeled, rows, "Every size, sign, limb, byte, sentinel and wrapped public conversion"); assertEquals(699, new HashSet<>(rows).size()); assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        for (var stagePaths : object(manifest.get("stages")).entrySet()) {
            var stage = stagePaths.getKey(); var sources = new ArrayList<Map<String, Object>>(); for (var path : expression(stagePaths.getValue())) sources.add(core((String) path)); var module = CoreModules.merge(sources);
            for (var name : ENTRIES) {
                var audit = report(DIRECTORY + "/" + stage + "-" + name + ".audit.json"); assertEquals(true, audit.get("accepted")); var selected = rows.stream().filter(row -> row.name.equals(name)).toList();
                for (var backend : list("ast", "bytecode")) try (var context = context(inlining)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var instrumented = with(CoreModules.reachable(module, "main:BigNatLiteralAudit." + name), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, instrumented) : new BytecodeProgram(language, instrumented);
                        var function = context.asValue(new EntryValue(program, "main:BigNatLiteralAudit." + name, 2)); var host = program.hostEntryTarget(2); var original = program.entryTarget("main:BigNatLiteralAudit." + name);
                        var worker = "ghc-internal:GHC.Internal.Bignum." + (name.startsWith("integer") ? "Integer.integerToInt#" : name.startsWith("natural") ? "Natural.naturalToWord#" : "Integer.integerToBigNatSign#");
                        assertTrue(objects(audit.get("reachableBindings")).stream().anyMatch(binding -> worker.equals(binding.get("id")))); var workerTarget = program.entryTarget(worker);
                        var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                        CheckedConsumer<Row> check = row -> { assertEquals(row.expected, function.execute(row.seed, row.index).asLong(), label + "/" + row.seed + "/" + row.index); released(language); };
                        // Warm the unchanged native corpus once; no settling or retries.
                        for (var row : selected) check.accept(row); var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " adopted guest path");
                        for (var target : targets) if (target != host) compile(target); compile(workerTarget); assertTrue(function.invokeMember("compile").asBoolean());
                        long allocations = language.getHandoffState().get().getResults().getAllocations();
                        for (var row : selected.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(row);
                            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label + " actual compiled entry"); assertEquals(targets, activeTargets(host), label + " active identities");
                            valid(original, label + " original"); valid(workerTarget, label + " original worker"); for (var target : targets) valid(target, label + " active");
                        }
                        assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(), label + " result slabs reused");
                        for (var counter : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                        System.out.println("BigNatLiteral PASS " + label + " rows=" + selected.size() + " activeTargets=" + targets.size());
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void canonicalPrivateBytesAndCheckedSizeMatchGhcLimbLayout() {
        for (int bits : new int[]{0, 1, 63, 64, 65, 127, 128, 129, 192, 256}) {
            var number = bits == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).add(BigInteger.ONE);
            var first = BigNatLiterals.decode(number.toString()); var second = BigNatLiterals.decode(number.toString()); assertNotSame(first, second); assertArrayEquals(first, second);
            assertEquals(((number.bitLength() + 63) / 64) * 8, first.length); var reconstructed = BigInteger.ZERO;
            for (int index = 0; index < first.length; index++) {
                int position = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : (index / 8) * 8 + 7 - index % 8;
                reconstructed = reconstructed.or(BigInteger.valueOf(first[index] & 255).shiftLeft(position * 8));
            }
            assertEquals(number, reconstructed); if (first.length != 0) { first[0] = (byte) (first[0] ^ 255); assertFalse(Arrays.equals(first, second)); }
        }
        assertEquals(0, BigNatLiterals.byteSize(0)); assertEquals(2147483640, BigNatLiterals.byteSize(17179869120L));
        for (long bits : new long[]{-1L, 17179869121L, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> BigNatLiterals.byteSize(bits));
        for (var value : list("", "-1", "+1", "00", "01", " 1", "1 ", "1.0", "0x10", "١")) assertThrows(RuntimeFault.class, () -> BigNatLiterals.decode(value));
    }
    private static Map<String, Object> model(List<Object> body) {
        var binding = map("id", "main:BigNatControl.root", "name", "root", "lifted", true, "arity", 1, "expr", list("lam", list(map("id", "x", "lifted", false)), body, map("rep", map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true), "resultRep", unknown())));
        return map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "BigNatControl", "boundary", "test-model", "constructors", list(), "bindings", list(binding));
    }
    private static String request(Path temporary, String backend, List<Object> body) throws Exception {
        var source = thc.CoreCbdFixtures.write(Files.createTempFile(temporary, "control-", ".cbd"), model(body));
        return CoreModules.request(list(source.toString()), "main:BigNatControl.root", true, false, backend);
    }
    private static Map<String, Object> exact() { return map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true); }
    private static Map<String, Object> unknown() { return map("kind", "unknown", "primReps", null, "evaluated", false); }
    private static List<Object> literal(String value, Map<String, Object> proof) { return list("lit", "bignat", value, map("rep", proof == null ? unknown() : proof)); }
    private static List<Object> size(List<Object> literal) { return list("app", list("prim", "sizeofByteArray#", map("rep", unknown())), list(literal), list(false), false, false, map("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true))); }
    private static List<Map<String, Object>> aggregateForgeries() {
        var voidRep = map("kind", "void", "primReps", list(), "evaluated", true);
        return list(voidRep, map("kind", "unknown", "primReps", list(), "evaluated", true, "aggregate", "unboxed-tuple", "components", list()),
            map("kind", "unknown", "primReps", list("WordRep"), "evaluated", true, "aggregate", "unboxed-sum", "tagSlot", 0, "alternativeSlots", list(list(), list()), "alternatives", list(voidRep, map("kind", "void", "primReps", list(), "evaluated", true))),
            map("kind", "vector", "primReps", list("VecRep 2 Int64ElemRep"), "evaluated", true, "vector", map("lanes", 2, "element", "Int64ElemRep")));
    }
    private void audit(Path temporary, int serial, List<Object> body, boolean accepted, String issue) throws Exception {
        var name = "control-" + serial;
        var source = temporary.resolve(name + ".cbd"); thc.CoreCbdFixtures.write(source, model(body)); var output = temporary.resolve(name + "-report.json");
        var process = new ProcessBuilder("python3", "bin/audit-core.py", source.toString(), "--entry", "main:BigNatControl.root", "--output", output.toString()).directory(root)
            .redirectOutput(temporary.resolve(name + ".stdout").toFile()).redirectError(temporary.resolve(name + ".stderr").toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Shared BigNat auditor timed out: " + name); }
        var report = Files.readString(output); assertEquals(accepted ? 0 : 1, process.exitValue(), name + ": " + report); var actual = object(Json.parse(report)); assertEquals(accepted, actual.get("accepted"), name);
        if (accepted) { assertEquals(list(), actual.get("issues")); assertEquals(list(), actual.get("missingGlobals")); }
        else { var issues = objects(actual.get("issues")); assertFalse(issues.isEmpty(), name); if (issue != null) assertTrue(issues.stream().anyMatch(item -> issue.equals(item.get("code"))), name + "/" + issue); }
    }
    @Test void sharedAuditorRetainsCanonicalIntrinsicAndMalformedControls(@TempDir Path temporary) throws Exception {
        var exact = exact(); var unknown = unknown(); int serial = 0;
        for (var value : list("0", "1", VALUES.getLast().toString())) for (var proof : list(exact, null, unknown)) {
            audit(temporary, serial++, literal(value, proof), true, null);
            // CLI recovers ByteArray# kind/PrimRep; auditor API separately checks evaluated=true.
            audit(temporary, serial++, size(literal(value, proof)), true, null);
        }
        // The ten malformed decimal spellings remain negative controls in the
        // managed decoder above and the owning Python auditor API. CBD stores
        // a numeric magnitude, not an unvalidated diagnostic spelling.
        var bad = new ArrayList<Map<String, Object>>();
        for (var pair : list(list("long", "IntRep"), list("long", "WordRep"), list("object", "BoxedRep (Just Lifted)"), list("object", "BoxedRep Nothing"), list("unknown", "BoxedRep (Just Unlifted)"), list("data", "BoxedRep (Just Unlifted)"), list("closure", "BoxedRep (Just Unlifted)")))
            bad.add(map("kind", pair.get(0), "primReps", list(pair.get(1)), "evaluated", true));
        bad.addAll(aggregateForgeries()); for (var proof : bad) audit(temporary, serial++, literal("1", proof), false, null);
        for (var proof : list(exact, null, unknown)) audit(temporary, serial++, size(literal("18446744073709551616", proof)), true, null);
        for (var kind : list("data", "closure", "unknown")) audit(temporary, serial++, size(literal("18446744073709551616", with(exact, "kind", kind))), false, null);
        for (var rep : list("BoxedRep (Just Lifted)", "BoxedRep Nothing", "IntRep", "WordRep")) audit(temporary, serial++, size(literal("18446744073709551616", with(exact, "primReps", list(rep)))), false, null);
        audit(temporary, serial++, list("case", list("lit", "int", "0", map()), "scrutinee", list(list("lit", list("bignat", "1"), list(), list("lit", "int", "1", map()), map("binders", list())), list("default", null, list(), list("lit", "int", "0", map()), map("binders", list()))), map()), false, "alternative-kind");
        assertEquals(40, serial);
    }
    @Test void everyModelByteReconstructsMagnitudeAndSentinels() {
        for (int seed = 0; seed < VALUES.size(); seed++) {
            var value = VALUES.get(seed); int bytes = ((value.abs().bitLength() + 63) / 64) * 8; var rebuilt = BigInteger.ZERO;
            for (int index = 0; index < bytes; index++) {
                int position = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : index / 8 * 8 + 7 - index % 8;
                rebuilt = rebuilt.or(BigInteger.valueOf(expected("magnitudeByte", seed, index, VALUES)).shiftLeft(position * 8));
            }
            assertEquals(value.abs(), rebuilt); assertEquals(-1L, expected("magnitudeByte", seed, -1, VALUES)); assertEquals(-1L, expected("magnitudeByte", seed, bytes, VALUES));
        }
        assertEquals(0L, expected("magnitudeSize", 0, 0, VALUES));
    }
    private static List<Object> size(Map<String, Object> proof, boolean bind) {
        var exact = exact(); var scalar = map("kind", "long", "primReps", list("IntRep"), "evaluated", true); var literal = literal("18446744073709551616", proof);
        // Direct calls recover the intrinsic proof; exact case binder is an independent control.
        var operand = bind ? list("var", "bytes", map("rep", exact)) : literal; var read = size(operand);
        return !bind ? read : list("case", literal, "bytes", list(list("default", null, list(), read, map("binders", list()))), map("rep", scalar, "binder", map("id", "bytes", "lifted", false, "rep", exact)));
    }
    @Test void exactUnliftedLiteralProofRejectsScalarAggregateAndBoxedForgeries(@TempDir Path temporary) throws Exception {
        var exact = exact(); var bad = new ArrayList<>(list(with(exact, "primReps", list("BoxedRep (Just Lifted)")), with(exact, "primReps", list("BoxedRep Nothing")), with(exact, "kind", "unknown"), with(exact, "kind", "data"), with(exact, "kind", "closure"),
            map("kind", "long", "primReps", list("IntRep"), "evaluated", true), map("kind", "long", "primReps", list("WordRep"), "evaluated", true)));
        bad.addAll(aggregateForgeries());
        for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            for (boolean bind : new boolean[]{false, true}) {
                for (var proof : list(exact, null, unknown())) assertEquals(16L, context.eval("thc", request(temporary, backend, size(proof, bind))).execute(0L).asLong());
                for (var proof : bad) assertThrows(PolyglotException.class, () -> context.eval("thc", request(temporary, backend, size(proof, bind))));
            }
        }
    }
    @Test void bignatLiteralAlternativesRemainForbidden(@TempDir Path temporary) throws Exception {
        var body = list("case", list("lit", "int", "0", map()), "scrutinee", list(list("lit", list("bignat", "0"), list(), list("lit", "int", "1", map()), map("binders", list())), list("default", null, list(), list("lit", "int", "0", map()), map("binders", list()))), map());
        for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(temporary, backend, body)));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("BigNat/rubbish literal alternatives are invalid GHC Core"), failure.getMessage());
        }
    }
}
