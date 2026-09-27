// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.ContextProfile;
import thc.CoreModules;
import thc.Json;
import thc.Language;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("unchecked")
class WindowsDirectoryStreamsTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/windows-directory";
    private final Map<String, OriginalStdioOp> operations = new LinkedHashMap<>();
    {
        operations.put("directoryFirst", OriginalStdioOp.FIND_FIRST);
        operations.put("directoryNext", OriginalStdioOp.FIND_NEXT);
        operations.put("directoryClose", OriginalStdioOp.FIND_CLOSE);
        operations.put("directoryError", OriginalStdioOp.LAST_ERROR);
    }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.toPath().resolve(path)));
    }
    private Map<String, Object> source(String stage) throws Exception { return json(prefix + "/" + stage + ".json"); }
    private Map<String, Object> source() throws Exception { return source("post"); }
    private ManagedAddress path(String value) {
        return ManagedAddress.Companion.fromByteArray((value + '\0').getBytes(StandardCharsets.UTF_16LE));
    }
    private ManagedAddress buffer() {
        return ManagedAddress.Companion.fromAllocation(ManagedAllocation.Companion.mutable(WindowsDirectoryStreams.Abi.getSize(), 8, false, 8));
    }
    private Context context() { return WindowsDirectoryStreams.createContext(ContextProfile.SYNCHRONOUS_TEST); }
    private Language enter(Context context) {
        context.initialize("thc");
        context.enter();
        try { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
        catch (RuntimeException | Error failure) { context.leave(); throw failure; }
    }
    private WindowsDirectoryStreams service() { return Language.currentState(null).getWindowsDirectories$org_intelligence_thc(); }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private List<Long> name(ManagedAddress output) {
        var result = new ArrayList<Long>();
        for (int index = 0; index < WindowsDirectoryStreams.Abi.getNameUnits(); index++) {
            long offset = WindowsDirectoryStreams.Abi.getNameOffset() + index * 2L;
            long value = output.readWord8(offset) | (output.readWord8(offset + 1) << 8);
            if (value == 0L) return result;
            result.add(value);
        }
        throw new IllegalStateException("Unterminated find-data name");
    }
    private List<Object> original(String name) throws Exception {
        var calls = OriginalStdioChecks.INSTANCE.foreignCalls(CoreModules.INSTANCE.reachable(source(), name, false));
        assertEquals(1, calls.size());
        return calls.getFirst();
    }
    private OriginalStdioOp validate(List<Object> call) {
        var reps = ((List<List<Object>>) call.get(2)).stream().map(argument -> {
            var metadata = CoreRepresentations.INSTANCE.metadata(argument);
            return metadata == null ? null : metadata.get("rep");
        }).toList();
        return CoreOriginalStdio.INSTANCE.validate(call.get(6), reps, (List<?>) call.get(3), ((Map<?, ?>) call.get(6)).get("rep"));
    }

    @Test void genuineWindowsCoreMatchesNativeOracleInInterpreterAndEveryFirstCompiledCall() throws Exception {
        var manifest = json(prefix + "/manifest.json");
        assertEquals(new ArrayList<>(operations.keySet()), manifest.get("entries"));
        assertEquals("Win32-2.14.2.1-inplace", manifest.get("win32Unit"));
        assertEquals(true, manifest.get("privateRebuiltWin32"));
        assertEquals(true, manifest.get("originalFCallIds"));
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of(
            "compiler/test-fixtures/WindowsDirectoryAudit.hsc", "test/haskell-fixtures/WindowsDirectoryFixtures.hs",
            "scripts/core_original_foreign.py", "scripts/core-capabilities.json"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"),
            Set.of(prefix + "/pre.json", prefix + "/post.json", prefix + "/oracle.json", prefix + "/win32-source.json"), prefix + "/");
        var receipt = json(prefix + "/win32-source.json");
        assertEquals(true, receipt.get("sourcesUnchangedAfterBuild"));
        assertEquals("69d15a9fb4ef718353aaf8700a64c5885743d4f34a94f7da273fa12584df0315", receipt.get("archiveSha256"));
        var oracle = json(prefix + "/oracle.json");
        assertEquals(true, oracle.get("processCwdUnchanged"));
        assertEquals(List.of(WindowsDirectoryStreams.Abi.getSize(), WindowsDirectoryStreams.Abi.getNameOffset(),
            WindowsDirectoryStreams.Abi.getNameUnits(), WindowsDirectoryStreams.Abi.getNoMoreFiles()), oracle.get("layout"));
        var rows = (List<Map<String, Object>>) oracle.get("rows");
        assertEquals(10, rows.size());
        for (var entry : Map.of("extended", false, "extended-forward-slash", true).entrySet()) {
            var matches = rows.stream().filter(row -> entry.getKey().equals(row.get("case"))).toList();
            assertEquals(1, matches.size());
            assertEquals(entry.getValue(), matches.getFirst().get("invalid"));
        }
        var nativeCwd = Path.of("").toAbsolutePath();
        for (var stage : List.of("pre", "post")) {
            for (var operation : operations.entrySet()) {
                assertEquals(operation.getValue(), validate(original(operation.getKey())));
                var audit = json(prefix + "/" + stage + "-" + operation.getKey() + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            }
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                var language = enter(context);
                try {
                    var base = Files.createTempDirectory(directory, "native-");
                    for (var filename : (List<String>) oracle.get("names")) Files.writeString(base.resolve(filename), "contents");
                    Files.createDirectory(base.resolve("empty"));
                    Files.createDirectory(base.resolve((String) oracle.get("unicodeDirectory")));
                    var env = Language.currentState(null).getEnv();
                    env.setCurrentWorkingDirectory(env.getPublicTruffleFile(base.toString()));
                    var module = new LinkedHashMap<>(source(stage));
                    module.put("instrument", true);
                    var executable = program(language, backend, module);
                    var targets = new LinkedHashMap<String, RootCallTarget>();
                    for (var entry : operations.keySet()) targets.put(entry, executable.entryTarget(entry));
                    class Exercise {
                        boolean compiled;
                        Object invoke(String entry, Object... args) throws Exception {
                            var target = targets.get(entry);
                            if (compiled) valid(target);
                            long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue();
                            var arguments = new Object[args.length + 1];
                            arguments[0] = 0L;
                            System.arraycopy(args, 0, arguments, 1, args.length);
                            var result = Calls.target(target, arguments);
                            if (compiled) {
                                assertEquals(before + 1, ((Number) executable.diagnostics().get("compiledEntries")).longValue(), stage + "/" + backend + "/" + entry);
                                valid(target);
                            }
                            var handoff = language.getHandoffState$org_intelligence_thc().get();
                            assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences$org_intelligence_thc());
                            assertEquals(0, handoff.getResults().retainedReferences());
                            return result;
                        }
                        void run() throws Exception {
                            for (boolean relative : new boolean[] {false, true}) for (var row : rows) {
                                var label = row.get("case").toString();
                                var suffix = switch (label) {
                                    case "populated" -> "*";
                                    case "empty" -> "empty\\*";
                                    case "missing" -> "missing\\*";
                                    case "regular" -> "ordinary.txt\\*";
                                    case "unicode-directory" -> oracle.get("unicodeDirectory") + "\\*";
                                    case "filter" -> "*.txt";
                                    case "no-match" -> "absent-*";
                                    default -> "";
                                };
                                var query = switch (label) {
                                    case "extended" -> "\\\\?\\" + base + "\\*";
                                    case "extended-forward-slash" -> "\\\\?\\" + base + "/*";
                                    default -> relative || suffix.isEmpty() ? suffix : base + "\\" + suffix;
                                };
                                long size = WindowsDirectoryStreams.Abi.getSize();
                                var storage = ManagedAddress.Companion.fromAllocation(ManagedAllocation.Companion.mutable(size + 16, 8, false, 8));
                                storage.fill(size + 16, 165);
                                var output = storage.plus(8);
                                var handle = (ManagedAddress) invoke("directoryFirst", path(query), output);
                                boolean invalid = handle.sameLocation(WindowsDirectoryStreams.invalidHandle());
                                assertEquals(row.get("invalid"), invalid, label);
                                if (invalid) assertEquals(row.get("error"), invoke("directoryError", 0L));
                                else {
                                    var found = new ArrayList<List<Long>>();
                                    try {
                                        found.add(name(output));
                                        int limit = 100;
                                        while (!Long.valueOf(0).equals(invoke("directoryNext", handle, output))) {
                                            assertTrue(--limit > 0);
                                            found.add(name(output));
                                        }
                                        assertEquals(row.get("error"), invoke("directoryError", 0L));
                                        assertEquals(row.get("again"), invoke("directoryNext", handle, output));
                                        assertEquals(row.get("againError"), invoke("directoryError", 0L));
                                        assertEquals(new HashSet<>((List<List<Long>>) row.get("names")), new HashSet<>(found));
                                        assertEquals(((List<?>) row.get("names")).size(), found.size());
                                        if (label.equals("populated")) {
                                            var visible = new HashSet<String>();
                                            for (var chars : found) {
                                                var text = new StringBuilder();
                                                for (long c : chars) text.append((char) c);
                                                if (!text.toString().equals(".") && !text.toString().equals("..")) visible.add(text.toString());
                                            }
                                            assertEquals(new HashSet<>((List<String>) oracle.get("listDirectory")), visible);
                                        }
                                    } finally { assertNotEquals(0L, invoke("directoryClose", handle)); }
                                }
                                for (long i = 0; i <= 7; i++) {
                                    assertEquals(165L, storage.readWord8(i));
                                    assertEquals(165L, storage.readWord8(size + 8 + i));
                                }
                                assertEquals(0, service().liveCount());
                            }
                        }
                    }
                    var exercise = new Exercise();
                    exercise.run();
                    for (var target : targets.values()) {
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        valid(target);
                    }
                    exercise.compiled = true;
                    exercise.run();
                    assertEquals(0L, ((Number) executable.diagnostics().get("unsupportedTraps")).longValue());
                } finally { context.leave(); }
            }
        }
        assertEquals(nativeCwd, Path.of("").toAbsolutePath());
    }

    @Test void badStorageCannotAcquireOrAdvanceAndNativeStorageIsBorrowed() {
        try (var context = context()) {
            enter(context);
            try {
                var streams = service();
                var query = path(directory + "\\*");
                var output = buffer();
                for (var invalid : List.of(ManagedAddress.Companion.nullAddress(), ManagedAddress.Companion.fromByteArray(new byte[2]),
                    ManagedAddress.Companion.fromAllocation(ManagedAllocation.Companion.immutable(new byte[1024], 8, false)))) {
                    assertThrows(RuntimeFault.class, () -> streams.first(query, invalid));
                    assertEquals(0, streams.liveCount());
                }
                var a = streams.first(query, output);
                var reference = streams.first(query, buffer());
                assertThrows(RuntimeFault.class, () -> streams.next(a, ManagedAddress.Companion.fromByteArray(new byte[2])));
                var next = buffer();
                assertEquals(streams.next(reference, next), streams.next(a, output));
                assertEquals(name(next), name(output));
                // Win32's mallocForeignPtrBytes uses pinned byte-array storage.
                // The separate Linux libc malloc provider is not a Windows allocator.
                var owner = ManagedAllocation.Companion.mutable(WindowsDirectoryStreams.Abi.getSize() + 16, 8, true, 8);
                var memory = ManagedAddress.Companion.fromAllocation(owner);
                memory.fill(WindowsDirectoryStreams.Abi.getSize() + 16, 165);
                var nativeBuffer = memory.plus(8);
                var handle = streams.first(query, nativeBuffer);
                assertFalse(name(nativeBuffer).isEmpty());
                for (long i = 0; i <= 7; i++) {
                    assertEquals(165L, memory.readWord8(i));
                    assertEquals(165L, memory.readWord8(WindowsDirectoryStreams.Abi.getSize() + 8 + i));
                }
                owner.shrink(0);
                assertThrows(RuntimeFault.class, () -> streams.next(handle, nativeBuffer));
                assertNotEquals(0L, streams.closeSearch(handle));
                assertNotEquals(0L, streams.closeSearch(a));
                assertNotEquals(0L, streams.closeSearch(reference));
            } finally { context.leave(); }
        }
    }

    @Test void exactContextOwnedHandlesCloseOnFailureAndDisposal() {
        var first = context();
        var second = context();
        WindowsDirectoryStreams streams;
        ManagedAddress handle;
        ManagedAddress output;
        try {
            enter(first);
            try {
                streams = service();
                output = buffer();
                handle = streams.first(path(directory + "\\*"), output);
            } finally { first.leave(); }
            enter(second);
            try {
                assertThrows(RuntimeFault.class, () -> service().next(handle, buffer()));
                assertThrows(RuntimeFault.class, () -> streams.closeSearch(handle));
            } finally { second.leave(); }
            enter(first);
            try {
                assertThrows(RuntimeFault.class, () -> streams.closeSearch(handle.plus(1)));
                var before = name(output);
                assertNotEquals(0L, streams.closeSearch(handle));
                assertEquals(before, name(output), "Win32 find-data is caller-owned after close");
                assertThrows(RuntimeFault.class, () -> streams.closeSearch(handle));
                streams.first(path(directory + "\\*"), output);
                assertEquals(1, streams.liveCount());
            } finally { first.leave(); }
        } finally { first.close(); second.close(); }
        assertEquals(0, streams.liveCount());
    }

    @Test void fileAndNativeGrantsAloneDoNotAuthenticateAnArbitraryContext() {
        for (boolean nativeAccess : new boolean[] {false, true}) for (var io : List.of(IOAccess.NONE, IOAccess.ALL))
            try (var context = Context.newBuilder("thc").allowNativeAccess(nativeAccess).allowIO(io).build()) {
                enter(context);
                try { assertThrows(SecurityException.class, () -> WindowsDirectoryStreams.current(new com.oracle.truffle.api.nodes.Node() {})); }
                finally { context.leave(); }
            }
    }

    @Test void realDeclarationsRejectWrongOwnerSafetyWidthAndForgedStateBeforeEffects() throws Exception {
        for (var operation : operations.entrySet()) {
            var call = original(operation.getKey());
            var op = operation.getValue();
            assertEquals(op, validate(call));
            for (var unit : List.of("main", "ghc-internal", "unix-2.8.8.0-inplace", "directory-1.3.10.0-inplace",
                "Win32-2.14.2.2-inplace", "Win32-2.14.2.1-ABCD")) {
                var bad = (List<Object>) Json.INSTANCE.parse(Json.INSTANCE.stringify(call));
                ((Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) bad.get(6)).get("foreignCall")).get("target")).put("unit", unit);
                if (op == OriginalStdioOp.LAST_ERROR && unit.equals("ghc-internal")) assertEquals(op, validate(bad));
                else assertThrows(RuntimeFault.class, () -> validate(bad));
            }
            for (var field : Map.of("safety", "safe", "convention", "stdcall", "arity", 19L).entrySet()) {
                var bad = (List<Object>) Json.INSTANCE.parse(Json.INSTANCE.stringify(call));
                ((Map<String, Object>) ((Map<?, ?>) bad.get(6)).get("foreignCall")).put(field.getKey(), field.getValue());
                assertThrows(RuntimeFault.class, () -> validate(bad));
            }
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                var language = enter(context);
                try {
                    for (int i = 0; i < ((List<?>) call.get(2)).size(); i++) {
                        int index = i;
                        assertThrows(RuntimeFault.class, () -> program(language, backend, OriginalStdioChecks.INSTANCE.rawModule(call, source(), index)));
                    }
                    var streams = service();
                    var output = buffer();
                    var handle = streams.first(path(directory + "\\*"), output);
                    var before = name(output);
                    var target = program(language, backend, OriginalStdioChecks.INSTANCE.rawModule(call, source(), null)).entryTarget("entry");
                    var operands = switch (op) {
                        case FIND_FIRST -> new Object[] {path(directory + "\\*"), output};
                        case FIND_NEXT -> new Object[] {handle, output};
                        case FIND_CLOSE -> new Object[] {handle};
                        default -> new Object[0];
                    };
                    var arguments = new Object[operands.length + 2];
                    arguments[0] = 0L;
                    System.arraycopy(operands, 0, arguments, 1, operands.length);
                    arguments[arguments.length - 1] = 7L;
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, arguments));
                    assertEquals(1, streams.liveCount());
                    assertEquals(before, name(output));
                    assertNotEquals(0L, streams.closeSearch(handle));
                } finally { context.leave(); }
            }
        }
    }
}
