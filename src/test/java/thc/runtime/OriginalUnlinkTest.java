// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.ContextProfile;
import thc.Language;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Set;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

@EnabledOnOs({OS.LINUX, OS.MAC})
@EnabledIf("nativeUnlinkPlatform")
class OriginalUnlinkTest {
    private static boolean nativeUnlinkPlatform() {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        boolean x86 = arch.equals("amd64") || arch.equals("x86_64");
        return os.equals("Linux") && x86 || os.startsWith("Mac") && (x86 || arch.equals("aarch64") || arch.equals("arm64"));
    }
    private record RawOracle(long create, long createErrno, long firstUnlink, long firstErrno, long secondUnlink, long secondErrno) {}
    private String nativeRun(List<String> command) throws Exception {
        var log = directory.resolve("native-oracle.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native unlink oracle timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }
    private RawOracle rawOracle() throws Exception {
        var source = directory.resolve("raw-unlink-oracle.c");
        var executable = directory.resolve("raw-unlink-oracle");
        Files.writeString(source, """
            #include <errno.h>
            #include <fcntl.h>
            #include <stdio.h>
            #include <unistd.h>
            int main(int argc, char **argv) {
              if (argc != 2) return 1;
              char path[4096];
              int n = snprintf(path, sizeof(path), "%s/raw-%c", argv[1], 255);
              if (n < 0 || (size_t)n >= sizeof(path)) return 2;
              errno = 0; int fd = open(path, O_CREAT | O_WRONLY, 0600);
              int created = fd < 0 ? -1 : 0, create_errno = fd < 0 ? errno : 0;
              if (fd >= 0 && close(fd) != 0) return 3;
              errno = 0; int first = unlink(path), first_errno = first < 0 ? errno : 0;
              errno = 0; int second = unlink(path), second_errno = second < 0 ? errno : 0;
              // Four native-created names prove byte identity at each original
              // AST/bytecode interpreted/installed guest unlink, without replay.
              if (created == 0) for (int backend = 0; backend < 2; backend++) for (int compiled = 0; compiled < 2; compiled++) {
                n = snprintf(path, sizeof(path), "%s/native-%s-%s-raw-%c", argv[1],
                    backend ? "bytecode" : "ast", compiled ? "true" : "false", 255);
                if (n < 0 || (size_t)n >= sizeof(path)) return 2;
                fd = open(path, O_CREAT | O_WRONLY, 0600);
                if (fd < 0 || close(fd) != 0) return 3;
              }
              printf("%d %d %d %d %d %d\\n", created, create_errno, first, first_errno, second, second_errno);
              return 0;
            }
            """);
        nativeRun(List.of(System.getenv().getOrDefault("THC_CLANG", "clang"), "-std=c11", "-Wall", "-Wextra", "-Werror",
            source.toString(), "-o", executable.toString()));
        String output = nativeRun(List.of(executable.toString(), directory.toString())).trim();
        var fields = output.split("\\s+"); assertEquals(6, fields.length, output);
        long[] values = Arrays.stream(fields).mapToLong(Long::parseLong).toArray();
        assertTrue(values[0] == 0 || values[0] == -1, output);
        assertEquals(-1, values[4], "Second native unlink must fail after success or rejected creation");
        System.out.println("Native raw-byte unlink oracle: " + output);
        return new RawOracle(values[0], values[1], values[2], values[3], values[4], values[5]);
    }
    @TempDir Path directory;
    private static ManagedAddress address(byte[] bytes) {
        return ManagedAddress.fromByteArray(Arrays.copyOf(bytes, bytes.length + 1));
    }
    private static ManagedAddress path(Path value) { return address(value.toString().getBytes(StandardCharsets.UTF_8)); }

    @Test void bothInstalledBackendsRemoveNamesAndPreserveOpenedStorage() throws Exception {
        var oracle = rawOracle();
        for (String backend : new String[]{"ast", "bytecode"}) {
            try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = OriginalStdioFixtures.module(java.util.List.of("unlink"));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    var target = program.entryTarget("unlink");
                    var state = Language.currentState();
                    class Exercise {
                        boolean compiled;
                        long remove(ManagedAddress name) throws Exception {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            int result = (Integer) callScalarTestTarget(target, new Object[]{0L, name, thc.runtime.Unit.INSTANCE});
                            assertEquals(before + (compiled ? 1 : 0), ((Number) program.diagnostics().get("compiledEntries")).longValue());
                            if (compiled) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            var handoff = language.getHandoffState().get();
                            assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                            assertNull(handoff.getPending());
                            return result;
                        }
                        void run() throws Exception {
                            var root = Files.createTempDirectory(directory, "case-");
                            var file = Files.writeString(root.resolve("file"), "payload");
                            long open = state.getStdio().open(path(file), 0, 0);
                            assertTrue(open >= 0); assertEquals(0L, remove(path(file))); assertFalse(Files.exists(file));
                            byte[] bytes = new byte[7];
                            assertEquals(7L, state.getStdio().read(open, ManagedAddress.fromByteArray(bytes), 7));
                            assertEquals("payload", new String(bytes, StandardCharsets.UTF_8));
                            assertEquals(0L, state.getStdio().close(open)); assertEquals(-1L, remove(path(file)));
                            long missing = state.getStdio().errno();
                            assertEquals(StdioHostAbi.load().error(1), missing);
                            var targetFile = Files.writeString(root.resolve("target"), "untouched");
                            var link = Files.createSymbolicLink(root.resolve("link"), targetFile);
                            assertEquals(0L, remove(path(link))); assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
                            assertEquals("untouched", Files.readString(targetFile));
                            assertEquals(missing, state.getStdio().errno(), "Success preserves sticky errno");
                            assertEquals(-1L, remove(path(root))); assertTrue(Files.isDirectory(root));
                            assertEquals(-1L, remove(address(new byte[0]))); assertEquals(missing, state.getStdio().errno());
                            // Compare actual native byte-path behavior on this filesystem.
                            // Creation may reject invalid UTF-8; its errno must remain exact.
                            String relative = Path.of(state.getEnv().getCurrentWorkingDirectory().getPath()).relativize(root).toString();
                            byte[] prefix = (relative + "/raw-").getBytes(StandardCharsets.UTF_8);
                            byte[] rawBytes = Arrays.copyOf(prefix, prefix.length + 1); rawBytes[prefix.length] = (byte) 0xff;
                            var raw = address(rawBytes);
                            long flags = state.getStdio().flagConstant(OriginalStdioOp.O_CREAT) | state.getStdio().flagConstant(OriginalStdioOp.O_WRONLY);
                            long rawFd = state.getStdio().open(raw, flags, 384);
                            assertEquals(oracle.create(), rawFd < 0 ? -1L : 0L);
                            if (rawFd >= 0) { assertEquals(0L, state.getStdio().close(rawFd)); assertEquals(missing, state.getStdio().errno()); }
                            else assertEquals(oracle.createErrno(), state.getStdio().errno());
                            long sticky = state.getStdio().errno();
                            assertEquals(oracle.firstUnlink(), remove(raw));
                            assertEquals(oracle.firstUnlink() < 0 ? oracle.firstErrno() : sticky, state.getStdio().errno());
                            sticky = state.getStdio().errno();
                            assertEquals(oracle.secondUnlink(), remove(raw));
                            assertEquals(oracle.secondUnlink() < 0 ? oracle.secondErrno() : sticky, state.getStdio().errno());
                            String nativeRelative = Path.of(state.getEnv().getCurrentWorkingDirectory().getPath()).relativize(directory).toString();
                            byte[] nativePrefix = (nativeRelative + "/native-" + backend + "-" + compiled + "-raw-").getBytes(StandardCharsets.UTF_8);
                            byte[] nativeBytes = Arrays.copyOf(nativePrefix, nativePrefix.length + 1); nativeBytes[nativePrefix.length] = (byte) 0xff;
                            var nativeRaw = address(nativeBytes);
                            sticky = state.getStdio().errno();
                            assertEquals(oracle.firstUnlink(), remove(nativeRaw), "Exact native-created raw name");
                            assertEquals(oracle.firstUnlink() < 0 ? oracle.firstErrno() : sticky, state.getStdio().errno());
                            assertEquals(oracle.secondUnlink(), remove(nativeRaw));
                            assertEquals(oracle.secondErrno(), state.getStdio().errno());
                        }
                    }
                    var exercise = new Exercise(); exercise.run();
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    exercise.compiled = true; exercise.run();
                    var survivor = Files.writeString(directory.resolve("survivor-" + backend), "safe");
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, path(survivor), 7L}));
                    assertTrue(Files.exists(survivor), "Invalid State must reject before deletion");
                    assertThrows(RuntimeFault.class, () -> state.getStdio().unlink(ManagedAddress.fromByteArray(new byte[]{65})));
                } finally { context.leave(); }
            }
        }
    }
    @Test void arbitraryEmbeddingFilesystemDoesNotGainNativeUnlinkAuthority() throws Exception {
        var survivor = Files.writeString(directory.resolve("protected"), "safe");
        for (var io : new IOAccess[]{IOAccess.NONE, IOAccess.ALL}) {
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowIO(io).build()) {
                context.initialize("thc"); context.enter();
                try {
                    assertEquals(-1L, Language.currentState().getStdio().unlink(path(survivor)));
                    assertTrue(Files.exists(survivor));
                } finally { context.leave(); }
            }
        }
    }
}
