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
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalUnlinkTest {
    @TempDir Path directory;
    private static ManagedAddress address(byte[] bytes) {
        return ManagedAddress.fromByteArray(Arrays.copyOf(bytes, bytes.length + 1));
    }
    private static ManagedAddress path(Path value) { return address(value.toString().getBytes(StandardCharsets.UTF_8)); }

    @Test void bothInstalledBackendsRemoveNamesAndPreserveOpenedStorage() throws Exception {
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
                            int result = (Integer) Calls.target(target, new Object[]{0L, name, thc.runtime.Unit.INSTANCE});
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
                            // Raw invalid UTF-8 survives relative paths and CWD resolution.
                            String relative = Path.of(state.getEnv().getCurrentWorkingDirectory().getPath()).relativize(root).toString();
                            byte[] prefix = (relative + "/raw-").getBytes(StandardCharsets.UTF_8);
                            byte[] rawBytes = Arrays.copyOf(prefix, prefix.length + 1); rawBytes[prefix.length] = (byte) 0xff;
                            var raw = address(rawBytes);
                            long flags = state.getStdio().flagConstant(OriginalStdioOp.O_CREAT) | state.getStdio().flagConstant(OriginalStdioOp.O_WRONLY);
                            long rawFd = state.getStdio().open(raw, flags, 384);
                            assertTrue(rawFd >= 0); assertEquals(0L, state.getStdio().close(rawFd));
                            assertEquals(0L, remove(raw)); assertEquals(-1L, remove(raw));
                        }
                    }
                    var exercise = new Exercise(); exercise.run();
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    exercise.compiled = true; exercise.run();
                    var survivor = Files.writeString(directory.resolve("survivor-" + backend), "safe");
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, path(survivor), 7L}));
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
