// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine selected GHC Core, original RTS error payload, and first installed guest waits. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class FileWaitFullCoreTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root, "build/file-wait");
    private final Map<String, Boolean> entries = new LinkedHashMap<>();
    public FileWaitFullCoreTest() { entries.put("waitReadRoot", false); entries.put("waitWriteRoot", true); }
    private Map<String, Object> document(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(fixture, path).toPath(), StandardCharsets.UTF_8)); }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target), target.getRootNode().getName()); }
    private Object invoke(RootCallTarget target, long fd) { return Calls.target(target, new Object[] {0L, fd, kotlin.Unit.INSTANCE}); }
    @Test public void originalWaitsMatchNativeAndRetainTheExactLazyBadFdPayload() throws Exception {
        var manifest = document("manifest.json"); assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(new ArrayList<>(entries.keySet()), manifest.get("entries"));
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of("compiler/test-fixtures/FileWaitAudit.hs", "compiler/test-fixtures/FileWaitNative.hs",
            "test/haskell-fixtures/FileWaitFixtures.hs", "scripts/core-capabilities.json"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"), Set.of("build/file-wait/oracle.txt", "build/file-wait/pre/core/FileWaitAudit.json", "build/file-wait/post/core/FileWaitAudit.json"), null);
        assertEquals("read-ready\nwrite-ready\noriginal-bad-fd\n", Files.readString(new File(root, (String) manifest.get("oracle")).toPath(), StandardCharsets.UTF_8));
        var originals = new ArrayList<Map<String, Object>>(); var targetLayout = CorePackageManifest.visitModules(new File(root, (String) manifest.get("packageManifest")).getPath(), (module, path) -> originals.add(module)).getTargetLayout(); assertNotNull(targetLayout);
        var stages = (Map<String, String>) manifest.get("stages"); assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var modules = new ArrayList<>(originals); modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, stage.getValue()).toPath(), StandardCharsets.UTF_8)));
            var combined = new LinkedHashMap<>(CoreModules.merge(modules)); combined.put("targetLayout", targetLayout);
            var closure = document(stage.getKey() + "/core/THC.InterfaceClosure.json"); var discovered = new ArrayList<Object>();
            for (String key : List.of("bindings", "missingDefinitions")) for (var binding : (List<Map<String, Object>>) closure.get(key)) discovered.add(binding.get("id"));
            assertTrue(discovered.contains(CoreFileWait.badFd), stage.getKey() + " must discover the original implicit payload");
            for (var entry : entries.entrySet()) {
                boolean writing = entry.getValue(); var audit = document(stage.getKey() + "/" + entry.getKey() + "-audit.json");
                assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("missingGlobals")); boolean found = false;
                for (var primitive : (List<Map<String, Object>>) audit.get("primitives")) if ((writing ? "waitWrite#" : "waitRead#").equals(primitive.get("name"))) found = true; assertTrue(found);
                for (String backend : List.of("ast", "bytecode")) try (var context = NativeFileProvider.Companion.createContext$org_intelligence_thc(Set.of(), ContextProfile.SYNCHRONOUS_TEST, FfiMode.NATIVE, false)) {
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var files = Language.currentState().getFiles();
                        var file = directory.resolve(stage.getKey() + "-" + backend + "-" + entry.getKey()); Files.writeString(file, "ready");
                        byte[] raw = file.toString().getBytes(StandardCharsets.UTF_8); var address = ManagedAddress.Companion.fromByteArray(Arrays.copyOf(raw, raw.length + 1));
                        long descriptor = files.open(address, writing ? 2L : 0L, ForeignSafety.UNSAFE); assertTrue(descriptor >= 3L);
                        var linked = new LinkedHashMap<>(CoreModules.reachable(combined, entry.getKey(), true)); linked.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var target = program.entryTarget(entry.getKey());
                        for (int i = 0; i < 6; i++) assertSame(kotlin.Unit.INSTANCE, invoke(target, descriptor));
                        var original = program.entryValue(CoreFileWait.badFd);
                        for (int i = 0; i < 2; i++) {
                            var failure = assertThrows(GuestException.class, () -> invoke(target, -1L)); assertSame(original, failure.getPayload());
                            if (original instanceof Thunk thunk) assertEquals(0, thunk.getState(), "original CAF remains lazy");
                        }
                        compile(target); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertSame(kotlin.Unit.INSTANCE, invoke(target, descriptor));
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertTrue(valid(target));
                        for (long bad : new long[] {-1L, descriptor}) {
                            if (bad == descriptor) assertEquals(0L, files.close(descriptor, ForeignSafety.UNSAFE)); var failure = assertThrows(GuestException.class, () -> invoke(target, bad)); assertSame(original, failure.getPayload());
                            if (original instanceof Thunk thunk) assertEquals(0, thunk.getState(), "original CAF remains lazy");
                        }
                        assertTrue(valid(target), stage.getKey() + "/" + backend + "/" + entry.getKey() + " remains installed after bad FD");
                    } finally { context.leave(); }
                }
            }
        }
    }
}
