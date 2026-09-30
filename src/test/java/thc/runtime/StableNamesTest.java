// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class StableNamesTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("sameLifted", "sameUnlifted", "differentUnlifted", "unevaluatedName");
    private final List<Long> inputs = List.of(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE);
    private long expected(String entry, long input) {
        return switch (entry) { case "sameLifted", "sameUnlifted" -> 2L; case "differentUnlifted" -> 0L; default -> input; };
    }
    @Test public void namesUseIdentityWithoutInvokingReferentsAndRemainContextOwned() {
        class Hostile {
            @Override public boolean equals(Object other) { throw new IllegalStateException("Referent equality evaluated"); }
            @Override public int hashCode() { throw new IllegalStateException("Referent hash evaluated"); }
        }
        var registry = new StableNames(); var other = new StableNames(); var first = new Hostile();
        var name = registry.make(first);
        assertSame(name, registry.make(first)); assertNotSame(name, registry.make(new Hostile()));
        assertEquals(registry.hash(name), registry.hash(registry.make(first)));
        assertThrows(RuntimeFault.class, () -> other.hash(name));
        assertThrows(RuntimeFault.class, () -> registry.hash(first));
        assertThrows(RuntimeFault.class, () -> registry.make(null));
        registry.close();
        assertThrows(RuntimeFault.class, () -> registry.hash(name));
        assertThrows(RuntimeFault.class, () -> registry.make(first));
        other.close();
    }
    @Test public void concurrentNamingKeepsOneCanonicalToken() throws Exception {
        var registry = new StableNames(); var value = new Object(); var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<Object>>();
            for (int i = 0; i < 64; i++) work.add(() -> registry.make(value));
            var names = new ArrayList<Object>(); for (var future : pool.invokeAll(work)) names.add(future.get());
            for (var name : names) assertSame(names.getFirst(), name);
        } finally { pool.shutdownNow(); registry.close(); }
    }
    @Test public void originalCorePreservesSharingHashesAndLazinessInBothBackends() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/stable-names/manifest.json").toPath()));
        assertEquals(entries, manifest.get("entries"));
        for (var group : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(group)).entrySet()) {
            var path = (String) item.getKey(); var bytes = Files.readAllBytes(new File(root, path).toPath());
            assertEquals(item.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), path);
        }
        var nativeValues = new ArrayList<Long>(); for (long input : inputs) for (var entry : entries) nativeValues.add(expected(entry, input));
        assertEquals(nativeValues, manifest.get("native"));
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var entry : entries) {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var module = (Map<String, Object>) thc.CoreCbdFixtures.read(new File(root, "build/stable-names/" + stage + "/core/StableNames.cbd").toPath());
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var source = new LinkedHashMap<>(CoreModules.reachable(module, "main:StableNames." + entry)); source.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                    var function = context.asValue(new EntryValue(program, "main:StableNames." + entry, 1));
                    for (long input : inputs) assertEquals(expected(entry, input), function.execute(input).asLong(), stage + "/" + backend + "/" + entry + "/" + input);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected(entry, 17), function.execute(17L).asLong(), stage + "/" + backend + "/" + entry + " first installed call");
                    var evidence = new ArrayCoreEvidence(module, "main:StableNames." + entry);
                    // Unforced bottom is a separate CAF, not an executed root.
                    evidence.stateLambda(evidence.getRoot().get("expr"));
                    assertEquals((long) evidence.loweredStateLambdas(evidence.getRoot().get("expr")).size(),
                        ((Number) program.diagnostics().get("compiledEntries")).longValue() - before,
                        stage + "/" + backend + "/" + entry + " original entry with in-frame State# body");
                    ThreadInventoryCoreEvidence.released(language);
                } finally { context.leave(); }
            }
        }
    }
}
