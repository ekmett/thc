// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.ContextProfile;
import thc.CoreCbdFixtures;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.Main;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("unchecked")
class WindowsSharedCAFTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("pendingDelays", "ioManagerThread", "prodding");
    private final List<SharedCAFStore> stores = List.of(SharedCAFStore.WINDOWS_PENDING_DELAYS,
        SharedCAFStore.WINDOWS_IO_MANAGER_THREAD, SharedCAFStore.WINDOWS_PRODDING);
    private String entryId(String name) { return "main:WindowsSharedCAFCalls." + name; }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.toPath().resolve(path)));
    }
    private Map<String, Object> receipt() throws Exception {
        return json("build/windows-shared-caf/manifest.json");
    }
    private Map<String, Object> source(String logs, String stage) throws Exception {
        return CoreCbdFixtures.read(root.toPath().resolve(logs + "/" + stage + ".cbd"));
    }
    private List<Object> original(Map<String, Object> module, String name) {
        var calls = OriginalStdioChecks.foreignCalls(CoreModules.reachable(module, entryId(name), false));
        assertEquals(1, calls.size(), name);
        return calls.getFirst();
    }
    private List<Object> arguments(List<Object> call) {
        return ((List<List<Object>>) call.get(2)).stream().map(value ->
            CoreRepresentations.metadata(value).get("rep")).toList();
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName());
    }

    @Test void genuinePinnedCallsMatchNativeIdentityAndInstallOnTheirFirstCompiledEntry() throws Exception {
        var proof = receipt();
        var logs = proof.get("logs").toString().replace('\\', '/');
        assertEquals("9.14.1", proof.get("ghc"));
        assertEquals(true, proof.get("originalFCallIds"));
        assertEquals(entries, proof.get("entries"));
        assertEquals("902339d332fb4ce2b3c87dcac1ee6495d41ad886", ((Map<?, ?>) proof.get("upstream")).get("revision"));
        OriginalStdioChecks.hashes(root, proof.get("inputHashes"), Set.of("t/fixtures/compiler/WindowsSharedCAFCalls.hs",
            "t/haskell-fixtures/WindowsCodePageFixtures.hs", "src/main/java/thc/runtime/SharedCAFStore.java",
            "src/main/java/thc/runtime/StablePointers.java", "bin/core_original_foreign.py"));
        OriginalStdioChecks.hashes(root, proof.get("artifactHashes"),
            Set.of(logs + "/pre.cbd", logs + "/post.cbd", logs + "/oracle.json"), logs + "/");
        var upstream = new LinkedHashMap<String, String>();
        for (var hash : ((Map<String, String>) proof.get("sourceHashes")).entrySet())
            upstream.put(root.getCanonicalFile().toPath().relativize(new File(hash.getKey()).getCanonicalFile().toPath())
                .toString().replace('\\', '/'), hash.getValue());
        OriginalStdioChecks.hashes(root, upstream, Set.of("nih/pinned/ghc-9.14.1/rts/Globals.c",
            "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Conc/POSIX.hs"), "nih/pinned/");
        for (var command : (List<Map<String, Object>>) proof.get("commands"))
            assertEquals(command.get("expectedExit"), command.get("exit"));
        var nativeStores = (List<Map<String, Object>>) json(logs + "/oracle.json").get("stores");
        assertEquals(entries, nativeStores.stream().map(row -> row.get("name")).toList());
        for (var row : nativeStores) {
            assertEquals(true, row.get("repeatMatches"));
            assertEquals(true, row.get("queryMatches"));
            assertEquals(false, row.get("loserForced"));
            if (Boolean.FALSE.equals(row.get("initiallyEmpty"))) assertEquals(false, row.get("firstWon"));
        }
        for (var stage : List.of("pre", "post")) {
            var module = new LinkedHashMap<>(source(logs, stage));
            module.put("instrument", true);
            for (var name : entries) {
                var audit = json(logs + "/" + stage + "-" + name + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            }
            for (var backend : List.of("ast", "bytecode")) for (var compiled : List.of(false, true)) {
                StablePointers roots;
                var foreign = new StablePointers();
                var winners = new ArrayList<ManagedAddress>();
                try (var context = Main.withContextProfile(Context.newBuilder("thc")
                        .allowNativeAccess(false).allowIO(IOAccess.NONE), ContextProfile.SYNCHRONOUS_TEST).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        roots = Language.currentState(null).getStablePointers();
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                        for (int index = 0; index < entries.size(); index++) {
                            var name = entries.get(index);
                            var target = program.entryTarget(entryId(name));
                            if (compiled) {
                                var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                                targetClass.getMethod("compile", boolean.class).invoke(target, true);
                                valid(target);
                                var runtime = Truffle.getRuntime();
                                runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target);
                            }
                            class Invoke {
                                ManagedAddress call(ManagedAddress candidate) throws Exception {
                                    if (compiled) valid(target);
                                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                    var result = (ManagedAddress) ScalarTestCalls.callScalarTestTarget(target, new Object[] {0L, candidate});
                                    if (compiled) {
                                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(),
                                            stage + "/" + backend + "/" + name + " first compiled call");
                                        valid(target);
                                    }
                                    var handoff = language.getHandoffState().get();
                                    assertEquals(0, handoff.getArguments().getDepth());
                                    assertEquals(0, handoff.getResults().getDepth());
                                    assertEquals(0, handoff.getArguments().retainedReferences$org_intelligence_thc());
                                    assertEquals(0, handoff.getResults().retainedReferences());
                                    return result;
                                }
                            }
                            var invoke = new Invoke();
                            var none = ManagedAddress.nullAddress();
                            if (!compiled) assertSame(none, invoke.call(none));
                            var referent = new Object(); var winner = roots.make(referent); var loser = roots.make(new Object());
                            // Fresh context: installing the root is the first compiled call, without a warmup.
                            assertTrue(roots.equal(winner, invoke.call(winner)));
                            assertTrue(roots.equal(winner, invoke.call(loser)));
                            assertTrue(roots.equal(winner, invoke.call(none)));
                            assertSame(referent, roots.dereference(winner));
                            for (var previous : winners) assertFalse(roots.equal(previous, winner));
                            winners.add(winner);
                            roots.free(loser);
                            assertThrows(RuntimeFault.class, () -> ScalarTestCalls.callScalarTestTarget(target, new Object[] {0L, loser}));
                            var other = foreign.make(new Object());
                            assertThrows(RuntimeFault.class, () -> ScalarTestCalls.callScalarTestTarget(target, new Object[] {0L, other}));
                            assertThrows(RuntimeFault.class, () -> ScalarTestCalls.callScalarTestTarget(target,
                                new Object[] {0L, ManagedAddress.fromByteArray(new byte[8])}));
                            assertThrows(RuntimeFault.class, () -> ScalarTestCalls.callScalarTestTarget(target, new Object[] {0L, 1L}));
                            assertThrows(RuntimeFault.class, () -> roots.free(winner));
                            assertTrue(roots.equal(winner, roots.getOrSetSharedCAF(stores.get(index), none)));
                            var handoff = language.getHandoffState().get();
                            assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences$org_intelligence_thc());
                            assertEquals(0, handoff.getResults().retainedReferences());
                        }
                        assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                    } finally { context.leave(); }
                } finally { foreign.close(); }
                for (var winner : winners) assertThrows(RuntimeFault.class, () -> roots.dereference(winner));
            }
        }
    }

    @Test void genuineSharedCAFDeclarationsRejectRelabelingAndRetyping() throws Exception {
        var logs = receipt().get("logs").toString().replace('\\', '/');
        for (var stage : List.of("pre", "post")) for (int index = 0; index < entries.size(); index++) {
            var call = original(source(logs, stage), entries.get(index));
            var metadata = (Map<String, Object>) call.get(6);
            var declared = (Map<String, Object>) metadata.get("foreignCall");
            var reps = arguments(call); var flags = (List<?>) call.get(3); var result = metadata.get("rep");
            assertEquals(stores.get(index), CoreSharedCAFStores.validate(metadata, reps, flags, result));
            assertSame(CoreForeignOverride.SHARED_CAF, CoreForeignOverride.select(metadata));
            CoreSharedCAFStores.validateHead((List<?>) call.get(1), false);
            assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validateHead((List<?>) call.get(1), true));
            for (var mutation : List.of(OriginalStdioChecks.with(declared, "safety", "safe"),
                    OriginalStdioChecks.with(declared, "convention", "stdcall"),
                    OriginalStdioChecks.with(declared, "arity", 1L),
                    OriginalStdioChecks.with(declared, "target", OriginalStdioChecks.with((Map<?, ?>) declared.get("target"), "unit", "main"))))
                assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validate(
                    OriginalStdioChecks.with(metadata, "foreignCall", mutation), reps, flags, result));
            assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validate(metadata, reps.reversed(), flags, result));
            assertThrows(RuntimeFault.class, () -> CoreSharedCAFStores.validate(metadata, reps, List.of(true, false), result));
        }
    }

}
