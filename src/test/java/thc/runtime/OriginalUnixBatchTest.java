// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.ContextProfile;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.OriginalStdioFixtures.scalar;
import static thc.runtime.OriginalStdioFixtures.closure;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Shared transport checks, deliberately not a libc semantic test suite. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalUnixBatchTest {
    @TempDir Path directory;
    private static ManagedAddress string(String value) {
        return ManagedAddress.fromByteArray((value + "\0").getBytes(StandardCharsets.UTF_8));
    }
    private static List<Object> call(OriginalStdioOp operation) {
        var args = new ArrayList<Object>();
        for (int i = 0; i < operation.getArguments().size(); i++)
            args.add(list("var", "p" + i, map("rep", scalar(operation.getArguments().get(i)))));
        var result = map("kind", "unknown", "primReps", List.of(operation.getResult()),
            "aggregate", "unboxed-tuple", "components", List.of(scalar(null), scalar(operation.getResult())), "evaluated", false);
        var descriptor = map("schema", 1, "target", map("kind", "static", "symbol", operation.getSymbol(),
            "unit", "unix-2.8.8.0-inplace", "isFunction", true), "convention", operation.getConvention(),
            "safety", operation.getSafety(), "arity", args.size(), "suppliedArity", args.size(),
            "argumentReps", operation.getArguments().stream().map(rep -> scalar(rep, false)).toList(), "resultRep", result);
        return list("app", list("var", "original", map("rep", closure())), args,
            Collections.nCopies(args.size(), false), false, false, map("rep", result, "foreignCall", descriptor));
    }
    private static ExecutableProgram program(Language language, String backend, OriginalStdioOp operation) {
        var module = rawModule(call(operation), Map.of());
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private static long run(RootCallTarget target, Object... arguments) {
        var actual = new Object[arguments.length + 2]; actual[0] = 0L;
        System.arraycopy(arguments, 0, actual, 1, arguments.length);
        actual[actual.length - 1] = Unit.INSTANCE;
        return ((Number) callScalarTestTarget(target, actual)).longValue();
    }
    private static long nativeCall(OriginalStdioOp operation, Object... arguments) {
        return NativeUnix.execute(operation, arguments);
    }

    @Test void sharedWidthsImagesAndEffectsReachBothBackendsAndFirstCompiledCalls() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST)) {
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var path = directory.resolve(backend); Files.writeString(path, "abcdef");
                var stdio = Language.currentState().getStdio();
                assertEquals(0, stdio.changeDirectory(string(directory.toString())));
                var nativeSet = new byte[144]; Arrays.fill(nativeSet, (byte) 0x5a);
                var signalSet = ManagedAddress.fromByteArray(nativeSet).plus(8);
                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_SIGFILLSET, signalSet));
                var cases = List.of(OriginalStdioOp.UNIX_TRUNCATE, OriginalStdioOp.UNIX_SIGDELSET,
                    OriginalStdioOp.UNIX_SYSCONF, OriginalStdioOp.UNIX_TIME,
                    OriginalStdioOp.UNIX_MAKEDEV, OriginalStdioOp.UNIX_MKNOD);
                for (var operation : cases) {
                    var executable = program(language, backend, operation);
                    var target = executable.entryTarget("entry");
                    for (boolean compiled : new boolean[]{false, true}) {
                        if (compiled) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + operation + "/before");
                        }
                        long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue();
                        switch (operation) {
                            case UNIX_TRUNCATE -> {
                                Files.writeString(path, "abcdef");
                                assertEquals(0, run(target, string(backend), 3L));
                                assertEquals("abc", Files.readString(path));
                            }
                            case UNIX_SIGDELSET -> {
                                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_SIGFILLSET, signalSet));
                                assertEquals(0, run(target, signalSet, 2));
                                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_SIGISMEMBER, signalSet, 2L));
                                assertEquals(1, nativeCall(OriginalStdioOp.UNIX_SIGISMEMBER, signalSet, 3L));
                                assertEquals(0x5a, nativeSet[0]); assertEquals(0x5a, nativeSet[143]);
                            }
                            case UNIX_SYSCONF -> assertTrue(run(target, 30) >= 4096); // Linux _SC_PAGESIZE.
                            case UNIX_MAKEDEV -> assertEquals(Long.parseUnsignedLong("9223389629039771903"),
                                run(target, 0x80000000, -1)); // OriginalUnixBatchNative.hs, high unsigned bits in both inputs/output.
                            case UNIX_MKNOD -> {
                                String name = backend + "-created-" + compiled;
                                assertEquals(0, run(target, string(name), 0100600, 0L));
                                assertTrue(Files.isRegularFile(directory.resolve(name)));
                                // A repeated native creation would return EEXIST, so success
                                // also checks that this compiled effect ran exactly once.
                            }
                            case UNIX_TIME -> {
                                var image = ManagedAddress.fromByteArray(new byte[16]).plus(8);
                                long seconds = run(target, image);
                                assertEquals(seconds, ManagedAddressRead.WORD64.read(image, 0));
                                assertTrue(Math.abs(seconds - System.currentTimeMillis() / 1000) < 10);
                            }
                            default -> throw new AssertionError(operation);
                        }
                        if (compiled) {
                            assertEquals(before + 1, ((Number) executable.diagnostics().get("compiledEntries")).longValue());
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + operation + "/after");
                        }
                    }
                }
                var handoff = language.getHandoffState().get();
                assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
            } finally { context.leave(); }
        }
    }

    @Test void ownedDescriptorsCwdErrnoAndMemoryBoundsStayAtTheThcBoundary() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.writeString(first.resolve("file"), "first"); Files.writeString(second.resolve("file"), "second");
        try (var outer = NativeFileProvider.createContext(Set.of())) {
            outer.enter();
            try {
                var stdio = Language.currentState().getStdio();
                assertEquals(0, stdio.changeDirectory(string(first.toString())));
                long fd = stdio.open(string("file"), stdio.flagConstant(OriginalStdioOp.O_RDWR), 0);
                assertTrue(fd >= 3);
                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_FCHMOD, fd, 0600L));
                assertEquals(0600, ((Number) Files.getAttribute(first.resolve("file"), "unix:mode")).intValue() & 0777);
                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_FSYNC, fd));
                assertEquals(0, stdio.close(fd));
                assertEquals(-1, nativeCall(OriginalStdioOp.UNIX_FCHMOD, fd, 0644L));
                assertEquals(9, stdio.errno());
                assertEquals(9, nativeCall(OriginalStdioOp.UNIX_FADVISE, -1L, 0L, 0L, 0L));
                assertEquals(-1, nativeCall(OriginalStdioOp.UNIX_SYSCONF, -1L));
                assertEquals(22, stdio.errno());
                assertTrue(nativeCall(OriginalStdioOp.UNIX_SYSCONF, 30L) > 0);
                assertEquals(22, stdio.errno());
                assertThrows(RuntimeFault.class, () -> nativeCall(OriginalStdioOp.UNIX_UNAME, ManagedAddress.fromByteArray(new byte[8])));
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var target = program(language, "bytecode", OriginalStdioOp.UNIX_TRUNCATE).entryTarget("entry");
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, string("file"), 0L, 7L}));
                assertEquals("first", Files.readString(first.resolve("file")));
                try (var inner = NativeFileProvider.createContext(Set.of())) {
                    inner.enter();
                    try {
                        assertEquals(0, Language.currentState().getStdio().changeDirectory(string(second.toString())));
                        assertEquals(0, nativeCall(OriginalStdioOp.UNIX_TRUNCATE, string("file"), 2L));
                    } finally { inner.leave(); }
                }
                assertEquals(0, nativeCall(OriginalStdioOp.UNIX_TRUNCATE, string("file"), 3L));
                assertEquals("fir", Files.readString(first.resolve("file")));
                assertEquals("se", Files.readString(second.resolve("file")));
            } finally { outer.leave(); }
        }
    }
}
