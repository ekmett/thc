// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static thc.Main.withContextProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine Hackage Hashable instances, typed retained CAPI stubs and native GHC results. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class HashableFfiFullCoreTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/hashable-ffi";
    private final List<String> entries = List.of("strictText", "strictBytes", "shortBytes", "lazyText", "lazyBytes");
    private final List<Long> salts = List.of(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE);
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath(), StandardCharsets.UTF_8)); }
    private record Row(String entry, long salt, long choice, long result) {}
    private record Fixture(File packages, String unit, List<Row> rows) {}
    private record Input(String entry, long salt, long choice) {}
    private record Call(Object unit, Object symbol) {}
    private Fixture fixture() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(1L, manifest.get("schema")); assertEquals(true, manifest.get("strictAccepted")); assertEquals(true, manifest.get("runtimeVerified"));
        assertEquals("1.5.1.0", manifest.get("hashableVersion")); assertEquals(false, manifest.get("randomInitialSeed")); assertEquals(false, manifest.get("archNative")); assertEquals(300L, manifest.get("nativeRows"));
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of("test/fixtures/run-hashable-ffi/cabal.project", "test/fixtures/run-hashable-ffi/run-hashable-ffi.cabal",
            "test/fixtures/run-hashable-ffi/src/HashableProbe.hs", "test/fixtures/run-hashable-ffi/app/Main.hs", "test/haskell-fixtures/HashableFfiFixtures.hs", "compiler/THC/Plugin.hs"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"), Set.of(prefix + "/packages.json", prefix + "/acquired/packages.json", prefix + "/acquired/audit.json",
            prefix + "/acquired/native/cache/plan.json", prefix + "/logs/native-oracle.stdout"), prefix + "/");
        var rows = new ArrayList<Row>(); for (var row : (List<Map<String, Object>>) manifest.get("observations")) {
            var arguments = (List<String>) row.get("arguments"); assertEquals(2, arguments.size()); rows.add(new Row((String) row.get("entry"), Long.parseLong(arguments.getFirst()), Long.parseLong(arguments.get(1)), Long.parseLong((String) row.get("result"))));
        }
        var wanted = new ArrayList<Input>(); for (String entry : entries) for (long salt : salts) for (long choice = 0; choice <= 11; choice++) wanted.add(new Input(entry, salt, choice));
        var actual = new ArrayList<Input>(); for (var row : rows) actual.add(new Input(row.entry(), row.salt(), row.choice())); assertEquals(wanted, actual, "complete native input matrix, not selected passing rows");
        var nativeRows = new ArrayList<Row>(); for (String line : Files.readAllLines(new File(root, prefix + "/logs/native-oracle.stdout").toPath(), StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1); assertEquals(4, cells.length); nativeRows.add(new Row(cells[0], Long.parseLong(cells[1]), Long.parseLong(cells[2]), Long.parseLong(cells[3])));
        }
        assertEquals(nativeRows, rows, "expected results must be actual matching native executable output");
        var packages = new File(root, (String) manifest.get("packages")); var modules = new ArrayList<Map<String, Object>>(); CorePackageManifest.visitModules(packages.getPath(), (module, path) -> modules.add(module));
        String hashable = (String) manifest.get("hashableUnit"); var original = new ArrayList<Map<String, Object>>(); for (var module : modules) if (hashable.equals(module.get("unit"))) original.add(module);
        assertFalse(original.isEmpty(), "original dependency Core must remain in the package manifest"); var declarations = new ArrayList<Map<String, Object>>();
        for (var module : original) if (module.get("staticForeignImports") instanceof Map<?, ?> proof) {
            assertEquals("verified", proof.get("status")); for (var declaration : (List<Map<String, Object>>) proof.get("imports")) if ("HsXXHash.h".equals(declaration.get("header"))) declarations.add(declaration);
        }
        var required = Set.of("XXH3_64bits_withSeed", "hs_XXH3_64bits_withSeed_offset", "hs_XXH3_sizeof_state_s", "XXH3_INITSTATE", "XXH3_64bits_reset_withSeed",
            "XXH3_64bits_digest", "XXH3_64bits_update", "hs_XXH3_64bits_update_offset", "hs_XXH3_64bits_update_u64");
        var emitted = new LinkedHashMap<String, String>(); for (var declaration : declarations) emitted.put((String) declaration.get("symbol"), (String) ((Map<?, ?>) declaration.get("emitted")).get("symbol"));
        assertTrue(emitted.keySet().containsAll(required), "all actual XXH3 stages require retained typed declarations"); var links = new ArrayList<Map<String, Object>>();
        for (var module : original) if (module.get("packageNativeLink") instanceof Map<?, ?> link) links.add((Map<String, Object>) link);
        assertFalse(links.isEmpty(), "Hashable must use the general native C-FFI profile"); var abi = new ArrayList<Map<String, Object>>();
        for (var link : links) { assertEquals("thc-package-c-ffi-v1", link.get("profile")); assertEquals(hashable, link.get("unit")); abi.addAll((List<Map<String, Object>>) link.get("abi")); }
        var arguments = new ArrayList<String>(); boolean voidResult = false; for (var signature : abi) { arguments.addAll((List<String>) signature.get("arguments")); if ("void".equals(signature.get("result"))) voidResult = true; }
        assertTrue(arguments.containsAll(Set.of("ByteArray#", "MutableByteArray#", "AddrRep")), "exercise all three real memory argument forms"); assertTrue(voidResult, "state initialization/update must not manufacture scalar results");
        String unit = (String) manifest.get("probeUnit"); var merged = CoreModules.merge(modules);
        for (String name : entries) {
            var linked = CoreModules.reachable(merged, List.of(unit + ":HashableProbe." + name), true); var calls = new LinkedHashSet<Call>();
            for (var app : OriginalStdioChecks.INSTANCE.foreignCalls(linked)) {
                var descriptor = (Map<?, ?>) ((Map<?, ?>) app.getLast()).get("foreignCall"); var target = (Map<?, ?>) descriptor.get("target"); calls.add(new Call(target.get("unit"), target.get("symbol")));
            }
            Set<String> expected = switch (name) {
                case "strictText", "shortBytes" -> Set.of("hs_XXH3_64bits_withSeed_offset"); case "strictBytes" -> Set.of("XXH3_64bits_withSeed");
                default -> Set.of("hs_XXH3_sizeof_state_s", "XXH3_INITSTATE", "XXH3_64bits_reset_withSeed", "XXH3_64bits_digest", "hs_XXH3_64bits_update_u64",
                    name.equals("lazyText") ? "hs_XXH3_64bits_update_offset" : "XXH3_64bits_update");
            };
            for (String symbol : expected) assertTrue(calls.contains(new Call(hashable, Objects.requireNonNull(emitted.get(symbol)))), name + " must really reach " + symbol + " in unchanged dependency Core, not an integer-only stand-in");
        }
        return new Fixture(packages, unit, rows);
    }
    private void released(Context context) {
        context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
            assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
        } finally { context.leave(); }
    }
    private Map<String, Object> diagnostics(Value function) { return (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString()); }
    private void check(Row row, Value function, Context context, String label) { assertEquals(row.result(), function.execute(row.salt(), row.choice()).asLong(), label + "/" + row.salt() + "/" + row.choice()); released(context); }
    @TestFactory public List<DynamicTest> originalHashableMatchesNativeThroughRealByteBackedFfiAndFirstCompiledEntries() throws Exception {
        var fixture = fixture(); var tests = new ArrayList<DynamicTest>();
        for (String backend : List.of("ast", "bytecode")) for (String name : entries) tests.add(DynamicTest.dynamicTest(backend + "/" + name, () -> {
            try (Context context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
                String entry = fixture.unit() + ":HashableProbe." + name; var function = context.eval("thc", CoreModules.request(List.of("@" + fixture.packages().getPath()), entry, true, false, backend));
                var selected = new ArrayList<Row>(); for (var row : fixture.rows()) if (row.entry().equals(name)) selected.add(row);
                for (var row : selected) check(row, function, context, backend + "/" + name); assertTrue(function.invokeMember("compile").asBoolean(), backend + "/" + name + " installs real guest code");
                // The very next invocation is checked, with no settling call,
                // retry, recompilation or discarded first-entry observation.
                for (var row : selected.reversed()) {
                    long before = (Long) diagnostics(function).get("compiledEntries"); check(row, function, context, backend + "/" + name);
                    assertTrue((Long) diagnostics(function).get("compiledEntries") > before, backend + "/" + name + " entered compiled guest code");
                }
                assertEquals(0L, diagnostics(function).get("unsupportedTraps")); System.out.println("HashableFfi PASS " + backend + "/" + name + " nativeRows=" + selected.size() + " firstCompiled=true");
            }
        }));
        return tests;
    }
}
