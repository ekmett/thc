// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
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

@SuppressWarnings("unchecked")
public class GetEntropyTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/getentropy");
    private Context context() { return withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private Map<String, Object> json(String file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(new File(directory, file).toPath(), StandardCharsets.UTF_8));
    }
    private void verify() throws Exception {
        var manifest = json("manifest.json"); assertEquals(9L, manifest.get("nativeRows"));
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("sourceHashes"), Set.of(
            "build/getentropy/sources/splitmix-0.1.3.2/splitmix.cabal", "build/getentropy/sources/splitmix-0.1.3.2/cbits-unix/init.c",
            "build/getentropy/sources/splitmix-0.1.3.2/src/System/Random/SplitMix/Init.hs"), "build/getentropy/sources/");
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of(
            "compiler/test-fixtures/NativeGetEntropy.c", "compiler/test-fixtures/OriginalSplitmixNative.hs", "compiler/test-fixtures/OriginalSplitmixEntry.hs",
            "test/haskell-fixtures/GetEntropyFixtures.hs", "src/THC/Driver/PackageNative.hs", "src/THC/Driver/NativeLibrarySources.hs"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"), Set.of(
            "build/getentropy/System.Random.SplitMix.Init.json", "build/getentropy/System.Random.SplitMix.json",
            "build/getentropy/entry/units/u-original-splitmix-entry/OriginalSplitmixEntry.json", "build/getentropy/audit.json",
            "build/getentropy/native.tsv", "build/getentropy/splitmix-native.tsv", "build/getentropy/control.so"), "build/getentropy/");
        assertEquals(true, json("audit.json").get("accepted"));
    }
    private PackageScalarLink control() throws Exception {
        byte[] bytes = Files.readAllBytes(new File(directory, "control.so").toPath());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var signatures = List.of(
            new PackageScalarSignature("entropy_guard", "entropy_guard", List.of("WordRep"), "Int32Rep"),
            new PackageScalarSignature("entropy_null", "entropy_null", List.of("WordRep"), "Int32Rep"),
            new PackageScalarSignature("entropy_write", "entropy_write", List.of("AddrRep", "WordRep"), "Int32Rep"));
        return new PackageScalarLink("getentropy-controls", "x86_64-unknown-linux-gnu", sha, sha, bytes, signatures, "llvm-embedded-elf");
    }
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            // All three declared C control results are Int32Rep; widen only for the native-row comparison.
            return (long) access.executeInt(frame.getArguments(), kotlin.Unit.INSTANCE);
        }
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    @Test public void nativeStackBoundsAndErrorStatusesMatchOriginalLibc() throws Exception {
        verify();
        var rows = Files.readAllLines(new File(directory, "native.tsv").toPath(), StandardCharsets.UTF_8); assertEquals(9, rows.size());
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var link = control();
                Language.currentState().getPackageCbits().link(link);
                var entries = new LinkedHashMap<String, com.oracle.truffle.api.RootCallTarget>();
                for (var signature : link.getAbi()) entries.put(signature.getSymbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget());
                for (String line : rows) {
                    String[] row = line.split("\t", -1); String operation = row[0], count = row[1]; long n = Long.parseUnsignedLong(count);
                    long model = operation.equals("null") ? n == 0 ? 0 : -1 : Long.compareUnsigned(n, 256L) <= 0 ? 3 : 1;
                    assertEquals(model, Long.parseLong(row[2]), "independent status/bounds model");
                    assertEquals(model, entries.get("entropy_" + operation).call(n), operation + "/" + count); released(language);
                }
            } finally { context.leave(); }
        }
    }
    @Test public void pinnedBufferIsWrittenInPlaceAndManagedHeapIsNotCopied() throws Exception {
        verify();
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var link = control();
                Language.currentState().getPackageCbits().link(link);
                PackageScalarSignature signature = null;
                for (var candidate : link.getAbi()) if (candidate.getSymbol().equals("entropy_write")) { assertNull(signature); signature = candidate; }
                assertNotNull(signature); var target = new Entry(language, new PackageScalarCall(link, signature)).getCallTarget();
                var pinned = PinnedMemory.allocate(258, 64); long bits = pinned.nativeSegment().address();
                for (long i = 0; i <= 257; i++) pinned.writeByte(i, 0xa5);
                var address = ManagedAddress.fromAllocation(pinned).plus(1);
                assertEquals(0L, target.call(address, 256L)); assertEquals(bits, pinned.nativeSegment().address());
                assertEquals(0xa5L, pinned.readByte(0)); assertEquals(0xa5L, pinned.readByte(257));
                var actual = new ArrayList<Long>(); boolean changed = false;
                for (long i = 1; i <= 256; i++) { long value = pinned.readByte(i); actual.add(value); if (value != 0xa5L) changed = true; }
                assertTrue(changed, "real entropy overwrites the same persistent native storage");
                assertEquals(-1L, target.call(address, 257L));
                var after = new ArrayList<Long>(); for (long i = 1; i <= 256; i++) after.add(pinned.readByte(i));
                assertEquals(actual, after, "oversized request leaves bytes untouched");
                var heap = ManagedAllocation.mutable(8, 8);
                assertThrows(Exception.class, () -> target.call(ManagedAddress.fromAllocation(heap), 8L));
                assertNull(heap.nativeSegment(), "ordinary heap storage must not gain a native copy");
                for (long i = 0; i < 8; i++) assertEquals(0L, heap.readByte(i)); released(language);
            } finally { context.leave(); }
        }
    }
    @Test public void originalSplitmixSafeInitializerExecutesInBothFirstInstalledBackends() throws Exception {
        verify();
        var nativeRows = Files.readAllLines(new File(directory, "splitmix-native.tsv").toPath(), StandardCharsets.UTF_8);
        var nativeValues = new LinkedHashSet<Long>(); for (String value : nativeRows) nativeValues.add(Long.parseUnsignedLong(value));
        assertEquals(8, nativeRows.size()); assertTrue(nativeValues.size() > 1, "original native public API observes fresh seeds");
        var original = json("System.Random.SplitMix.Init.json"); var proof = (Map<?, ?>) original.get("packageNativeLink");
        assertEquals("llvm-embedded-elf", proof.get("format"));
        var libraries = (List<Map<?, ?>>) ((Map<?, ?>) proof.get("buildInputs")).get("nativeLibraries");
        var providers = new ArrayList<Object>(); for (var library : libraries) providers.add(library.get("provider"));
        assertEquals(List.of("native-libc-getentropy-v1"), providers);
        var modules = new ArrayList<Map<String, Object>>(); modules.add(original);
        for (String name : List.of("System.Random.SplitMix.json", "System.Random.SplitMix32.json", "entry/units/u-original-splitmix-entry/OriginalSplitmixEntry.json")) modules.add(json(name));
        var merged = CoreModules.merge(modules); var links = (List<PackageScalarLink>) merged.get("packageScalarLinks");
        assertEquals(1, links.size()); var link = links.getFirst();
        var symbols = new ArrayList<String>(); for (var abi : link.getAbi()) {
            symbols.add(abi.getSymbol()); assertTrue(abi.getSafety().equals("safe") && abi.getArguments().isEmpty() && abi.getResult().equals("Word64Rep"));
        }
        assertEquals(List.of("splitmix_init"), symbols);
        String entry = "original-splitmix-entry:OriginalSplitmixEntry.sample";
        var source = new LinkedHashMap<>(CoreModules.reachable(merged, entry)); source.put("instrument", true);
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState();
                owner.getPackageCbits().link(link);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                var target = program.entryTarget(entry); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var observations = new LinkedHashSet<Long>();
                    for (int i = 0; i < 4; i++) { observations.add((Long) Calls.target(target, new Object[] {0L, kotlin.Unit.INSTANCE})); released(language); }
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    for (int i = 0; i < 4; i++) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        observations.add((Long) Calls.target(target, new Object[] {0L, kotlin.Unit.INSTANCE})); released(language);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend);
                    }
                    assertTrue(observations.size() > 1, "original initializer is not replaced by deterministic entropy");
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
