// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.Main.withContextProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Actual package producer output and declared native-library execution. */
@SuppressWarnings("unchecked")
public class PackageNativeArchiveFullCoreTest {
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private PackageScalarLink link(List<PackageScalarLink> links, String unit) {
        PackageScalarLink result = null;
        for (var link : links) if (link.getUnit().equals(unit)) { assertNull(result); result = link; }
        assertNotNull(result); return result;
    }
    private List<String> arguments(PackageScalarLink link, String symbol) {
        PackageScalarSignature found = null;
        for (var abi : link.getAbi()) if (abi.getSymbol().equals(symbol)) { assertNull(found); found = abi; }
        assertNotNull(found); return found.getArguments();
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private long compiled(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void checkHeader(String backend, String name, String staticPointer, int column, RootCallTarget target,
                             ManagedAddress stateAddress, List<Number> row) {
        assertEquals(row.get(2).longValue(), row.get(3).longValue(), "native GHC typed/wide caller agreement");
        Object[] inputs = name.equals(staticPointer) ? new Object[] {0L, row.get(0).longValue(), row.get(1).longValue()}
            : new Object[] {0L, stateAddress, row.get(0).longValue(), row.get(1).longValue()};
        assertEquals(row.get(column).longValue(), Calls.target(target, inputs),
            backend + "/" + name + " original native CAPI/ccall declaration boundary " + row);
    }
    @Test public void supportedMixedImportRunsWhileArchivedImportsFailBeforeEffects() throws Exception {
        File root = new File(System.getProperty("thc.projectRoot")), directory = new File(root, "build/native-archive");
        var manifest = json(new File(directory, "manifest.json"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of(
            "t/fixtures/run-native-archive/mixed/src/Mixed.hs", "t/fixtures/run-native-archive/mixed/native.c",
            "t/fixtures/run-native-archive/mixed/src/Unknown.hs", "t/fixtures/run-native-archive/unresolved/Unresolved.hs",
            "t/fixtures/run-native-archive/mixed/src/Narrow.hs", "t/fixtures/run-native-archive/mixed/src/Wide.hs",
            "t/fixtures/run-native-archive/mixed/src/CapiMix.hs", "t/fixtures/run-native-archive/mixed/include/mixed-header.h",
            "t/fixtures/run-native-archive/unresolved/native.c", "t/haskell-fixtures/PackageNativeArchiveFixtures.hs",
            "src/driver/THC/Driver/PackageNative.hs", "src/driver/THC/Driver/NativeArgumentBridge.hs", "src/driver/THC/Driver/NativeLibrarySources.hs",
            "t/fixtures/run-native-archive/mixed/lifecycle.cpp", "t/fixtures/run-native-archive/mixed/src/Lifecycle.hs",
            "t/fixtures/run-native-archive/poisoned/native.c", "t/fixtures/run-native-archive/poisoned/Poisoned.hs",
            "t/fixtures/run-native-archive/provider/native.c", "t/fixtures/run-native-archive/provider/Provider.hs",
            "bin/core_package_manifest.py", "bin/audit-core.py"), null);
        var paths = (List<String>) manifest.get("modules"); var artifacts = new LinkedHashSet<>(paths);
        artifacts.addAll(List.of("build/native-archive/supported-audit.json", "build/native-archive/rejected-audit.json"));
        var support = new File((String) manifest.get("packageManifest"));
        assertEquals(manifest.get("packageManifestSha256"),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(support.toPath()))));
        var sources = new ArrayList<String>(); sources.add("@" + support.getAbsolutePath());
        for (String path : paths) sources.add(new File(root, path).getAbsolutePath());
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), artifacts, "build/native-archive/");
        var modules = new ArrayList<Map<String, Object>>(); for (String path : paths) modules.add(json(new File(root, path)));
        String mixed = "native-archive-mixed-0.1.0.0-inplace:Mixed.", narrow = "native-archive-mixed-0.1.0.0-inplace:Narrow.";
        String mixedHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.mixedProbe#", staticPointer = "native-archive-mixed-0.1.0.0-inplace:CapiMix.staticPointerProbe#";
        String wideHeader = "native-archive-mixed-0.1.0.0-inplace:CapiMix.wideProbe#", word16Header = "native-archive-mixed-0.1.0.0-inplace:CapiMix.word16Probe#";
        String lifecycle = "native-archive-mixed-0.1.0.0-inplace:Lifecycle.lifecycleProbe#", partial = "native-archive-unresolved-0.1.0.0-inplace:Unresolved.partialProbe#";
        var observations = (List<List<Number>>) manifest.get("mixedHeaderObservations"); assertEquals(36, observations.size());
        var merged = CoreModules.merge(modules); var links = (List<PackageScalarLink>) merged.get("packageScalarLinks"); assertEquals(4, links.size());
        var mixedLink = link(links, "native-archive-mixed-0.1.0.0-inplace"); var ccall = new LinkedHashSet<String>(); int capi = 0;
        for (var abi : mixedLink.getAbi()) { if (abi.getConvention().equals("ccall")) ccall.add(abi.getSymbol()); if (abi.getConvention().equals("capi")) capi++; }
        assertEquals(Set.of("archive_allowed", "archive_count", "archive_header_mix", "archive_header_mix_wide", "archive_header_mix16", "archive_lifecycle"), ccall);
        assertEquals(2, capi); assertEquals(List.of("AddrRep", "Word8Rep", "Word32Rep"), arguments(mixedLink, "archive_header_mix"));
        assertEquals(List.of("AddrRep", "IntRep", "IntRep"), arguments(mixedLink, "archive_header_mix_wide"));
        var unresolvedSymbols = new LinkedHashSet<String>();
        for (var abi : link(links, "native-archive-unresolved-0.1.0.0-inplace").getAbi()) unresolvedSymbols.add(abi.getSymbol());
        assertTrue(unresolvedSymbols.containsAll(Set.of("archive_partial_add", "archive_partial_read", "archive_process", "archive_through_global")));
        Map<String, Object> nativeProof = null;
        for (var module : modules) if (module.get("packageNativeLink") != null) { nativeProof = (Map<String, Object>) module.get("packageNativeLink"); break; }
        assertNotNull(nativeProof); assertEquals("llvm-embedded-elf", nativeProof.get("format"));
        var buildInputs = (Map<String, Object>) nativeProof.get("buildInputs");
        var libraries = (List<Map<String, Object>>) buildInputs.get("nativeLibraries"); assertEquals(1, libraries.size());
        assertEquals("package-declared-native-libraries-v1", libraries.getFirst().get("provider"));
        var bridges = (List<Map<String, Object>>) buildInputs.get("argumentBridges"); assertEquals(1, bridges.size()); var bridge = bridges.getFirst();
        assertEquals("x86_64-c-integer-argument-truncation-v1", bridge.get("profile")); String bridgeSource = (String) bridge.get("source");
        assertEquals(bridge.get("sourceSha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bridgeSource.getBytes(StandardCharsets.UTF_8))));
        assertEquals(2, ((List<?>) bridge.get("definitions")).size()); assertTrue(((String) bridge.get("inputBitcodeSha256")).matches("[0-9a-f]{64}"));
        for (int width : new int[] {8, 16, 32}) assertTrue(bridgeSource.contains(" to i" + width));
        var failures = List.of(mixed + "blocked", "native-archive-mixed-0.1.0.0-inplace:Unknown.other", narrow + "narrow",
            "native-archive-mixed-0.1.0.0-inplace:Wide.wide");
        var nativeEntries = List.of("native-archive-unresolved-0.1.0.0-inplace:Unresolved.process",
            "native-archive-unresolved-0.1.0.0-inplace:Unresolved.throughGlobal",
            "native-archive-poisoned-0.1.0.0-inplace:Poisoned.poisoned");
        String nativeMath = "native-archive-provider-0.1.0.0-inplace:Provider.nativeMath";
        for (String backend : List.of("ast", "bytecode")) try (Context context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.eval("thc", CoreModules.request(sources, mixed + "allowed", true, false, backend, false, false, null, false, false, true)); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState();
                for (var link : links) state.getPackageCbits().link(link);
                var programs = state.getCoreUnitPrograms(); assertEquals(1, programs.size());
                ExecutableProgram program = programs.getFirst();
                state.getThreads().enterCurrent(null, false, true, null);
                try {
                    for (String entry : nativeEntries)
                        assertEquals(ProcessHandle.current().pid(), Calls.target(program.entryTarget(entry), new Object[] {0L, 0L}));
                    assertEquals(0.0, Calls.target(program.entryTarget(nativeMath), new Object[] {0L, 0.0}));
                    var allowed = program.entryTarget(mixed + "allowed"); var count = program.entryTarget(mixed + "count");
                    assertEquals(40L, Calls.target(allowed, new Object[] {0L, 3L}));
                    assertEquals(42L, Calls.target(program.entryTarget(narrow + "allowed"), new Object[] {0L, 5L}));
                    assertEquals(0L, Calls.target(count, new Object[] {0L, 0L}));
                    var shared = program.entryTarget(partial); var partialObservations = (List<Number>) manifest.get("partialObservations");
                    long[] inputs = {3, 5, -2, 1};
                    for (int i = 0; i < inputs.length; i++) assertEquals(partialObservations.get(i).longValue(), Calls.target(shared, new Object[] {0L, inputs[i]}));
                    compile(shared); long beforeShared = compiled(program);
                    assertEquals(partialObservations.get(4).longValue(), Calls.target(shared, new Object[] {0L, 4L}), backend + " partial adapters share one initialized C global");
                    assertEquals(beforeShared + 1, compiled(program)); valid(shared);
                    var initialized = program.entryTarget(lifecycle); assertEquals(47L, Calls.target(initialized, new Object[] {0L, 5L}));
                    compile(initialized); assertEquals(48L, Calls.target(initialized, new Object[] {0L, 6L}), backend + " initialized native C++ state"); valid(initialized);
                    for (String entry : failures) {
                        assertTrue(((Map<?, ?>) merged.get("archiveBindings")).containsKey(entry), "retained archive exclusion: " + entry);
                        // Exception-bridge preflight can reject an excluded ABI
                        // before the final reachable-archive diagnostic.
                        var rejected = assertThrows(RuntimeException.class, () -> CoreModules.reachable(merged, entry, true));
                        assertTrue(rejected.getMessage().contains("archive-only") ||
                            rejected.getMessage().contains("Unlinked or ambiguous package C signature"), rejected.getMessage());
                        assertEquals(0L, Calls.target(count, new Object[] {0L, 0L}), backend + "/" + entry + " must not enter native code");
                    }
                    assertEquals(46L, Calls.target(allowed, new Object[] {0L, 9L}));
                    byte[] stateBytes = new byte[8]; stateBytes[0] = 7; var stateAddress = ManagedAddress.fromByteArray(stateBytes);
                    String[] headers = {mixedHeader, wideHeader, word16Header, staticPointer};
                    for (int i = 0; i < headers.length; i++) {
                        String name = headers[i]; int column = i + 2; var target = program.entryTarget(name);
                        for (var row : observations) checkHeader(backend, name, staticPointer, column, target, stateAddress, row);
                        compile(target);
                        for (var row : observations.reversed()) {
                            long before = compiled(program); checkHeader(backend, name, staticPointer, column, target, stateAddress, row);
                            assertTrue(compiled(program) > before);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + name + " first-installed mixed-header entry retains code");
                        }
                    }
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { state.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
