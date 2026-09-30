// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class UncaughtSelfNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    public void publicSelfThrowWithoutCatchIsGuestFailure(String backend) throws Exception {
        var receipt = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/uncaught-self/manifest.json").toPath()));
        assertEquals("9.14.1", receipt.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes")) {
            for (var item : ((Map<String, String>) receipt.get(kind)).entrySet()) {
                var bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath()));
                assertEquals(item.getValue(), HexFormat.of().formatHex(bytes), "Stale " + item.getKey());
            }
        }
        // Real native SomeException; opaque guest payload excludes rendering ABI dependencies.
        var nativeProcess = new ProcessBuilder(new File(root, "build/uncaught-self/native/oracle").getPath(), "+RTS", "-N2", "-RTS").start();
        try {
            assertTrue(nativeProcess.waitFor(5, TimeUnit.SECONDS), "Native self throw did not terminate");
            assertEquals(1, nativeProcess.exitValue(), "Uncaught native self throw must fail");
            assertFalse(new String(nativeProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("99"));
            assertTrue(new String(nativeProcess.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("thread killed"));
        } finally { nativeProcess.destroyForcibly(); }
        for (var stage : List.of("pre", "post")) {
            for (var report : List.of("audit.json", "io-audit.json")) {
                var audit = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/uncaught-self/" + stage + "/" + report).toPath()));
                assertEquals(true, audit.get("accepted"), stage + " " + report + " strict Core audit");
                assertEquals(List.of(), audit.get("missingGlobals")); assertEquals(List.of(), audit.get("issues"));
            }
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
                var source = new File(root, "build/uncaught-self/" + stage + "/core/UncaughtSelfAudit.cbd");
                var entry = context.eval("thc", CoreModules.request(List.of(source.getPath()), "main:UncaughtSelfAudit.selfUncaught", true, false, backend, true, false, null, true));
                var failure = assertThrows(PolyglotException.class, () -> entry.execute(0L));
                assertGuestFailure(failure, backend + " " + stage);
                assertTrue(entry.invokeMember("compile").asBoolean());
                var before = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
                var installed = assertThrows(PolyglotException.class, () -> entry.execute(1L));
                assertGuestFailure(installed, backend + " " + stage + " compiled entry");
                var after = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
                assertTrue(((Number) after.get("compiledEntries")).longValue() > ((Number) before.get("compiledEntries")).longValue(), backend + " " + stage + " installed guest code");
                var io = context.eval("thc", CoreModules.request(List.of(source.getPath()), "main:UncaughtSelfAudit.selfUncaughtIO", true, false, backend, true, true, null, true));
                assertFalse(io.canExecute(), stage + " IO action must use runIO");
                assertTrue(io.canInvokeMember("runIO"));
                var ioFailure = assertThrows(PolyglotException.class, () -> io.invokeMember("runIO"));
                assertGuestFailure(ioFailure, backend + " " + stage + " uncaught runIO");
            }
        }
    }
    private void assertGuestFailure(PolyglotException failure, String label) {
        assertTrue(failure.isGuestException(), label + " must expose a guest exception");
        var message = failure.getMessage() == null ? "" : failure.getMessage();
        assertFalse(message.contains("Internal ") || message.toLowerCase(Locale.ROOT).contains("continuation"), label + " must not expose a continuation or internal suspension");
    }
}
