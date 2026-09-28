// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.ContextProfile;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.Main;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("unchecked")
class WindowsCodePagesTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(root.toPath().resolve(path)));
    }
    private Map<String, Object> receipt() throws Exception { return json("build/windows-codepages/manifest.json"); }
    private Map<String, Object> source(String stage) throws Exception { return json(receipt().get("logs") + "/" + stage + ".json"); }
    private Map<String, Object> source() throws Exception { return source("post"); }
    private Context context(boolean nativeAccess) {
        return Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(nativeAccess)
            .allowIO(IOAccess.NONE), ContextProfile.SYNCHRONOUS_TEST).build();
    }
    private Context context() { return context(true); }
    private Language enter(Context context) {
        context.initialize("thc");
        context.enter();
        try { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
        catch (RuntimeException | Error failure) { context.leave(); throw failure; }
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
    }
    private ManagedAddress buffer(int size, long fill, boolean pinned) {
        var address = ManagedAddress.fromAllocation(ManagedAllocation.mutable(size, 8, pinned, 8));
        address.fill(size, fill);
        return address;
    }
    private ManagedAddress buffer(int size, long fill) { return buffer(size, fill, false); }
    private ManagedAddress buffer(int size) { return buffer(size, 165); }
    private ManagedAddress buffer() { return buffer(64); }
    private List<Long> bytes(ManagedAddress address, int size) {
        var values = new ArrayList<Long>();
        for (int i = 0; i < size; i++) values.add(address.readWord8(i));
        return values;
    }
    private List<Long> bytes(ManagedAddress address) { return bytes(address, 64); }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private final Map<String, OriginalStdioOp> operations = new LinkedHashMap<>();
    {
        operations.put("ansiPage", OriginalStdioOp.ANSI_CODE_PAGE);
        operations.put("consolePage", OriginalStdioOp.CONSOLE_CODE_PAGE);
        operations.put("windowsError", OriginalStdioOp.LAST_ERROR);
        operations.put("pageInfo", OriginalStdioOp.CODE_PAGE_INFO);
        operations.put("leadByte", OriginalStdioOp.DBCS_LEAD_BYTE);
        operations.put("multiByte", OriginalStdioOp.MULTI_BYTE_TO_WIDE);
        operations.put("wideChar", OriginalStdioOp.WIDE_TO_MULTI_BYTE);
        operations.put("mapError", OriginalStdioOp.MAP_ERRNO_VALUE);
        operations.put("mapCurrentError", OriginalStdioOp.MAP_ERRNO);
        operations.put("errorMessage", OriginalStdioOp.WINDOWS_ERROR_MESSAGE);
        operations.put("localFree", OriginalStdioOp.LOCAL_FREE);
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
        return CoreOriginalStdio.validate(call.get(6), reps, (List<?>) call.get(3), ((Map<?, ?>) call.get(6)).get("rep"));
    }

    @Test void genuineDeclarationsMatchNativeEncodingAndErrorsOnEveryFirstCompiledCall() throws Exception {
        var proof = receipt();
        var logs = proof.get("logs").toString().replace('\\', '/');
        assertEquals("9.14.1", proof.get("ghc"));
        assertEquals(true, proof.get("originalFCallIds"));
        assertEquals(new ArrayList<>(operations.keySet()), proof.get("entries"));
        assertEquals("2a83779c9af86554a3289f2787a38d6aa83d00d136aa9f920361dd693c101e77", proof.get("archiveSha256"));
        OriginalStdioChecks.INSTANCE.hashes(root, proof.get("inputHashes"), Set.of("compiler/test-fixtures/WindowsCodePageAudit.hs",
            "test/haskell-fixtures/WindowsCodePageFixtures.hs", "scripts/core_original_foreign.py"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, proof.get("artifactHashes"), Set.of(logs + "/pre.json", logs + "/post.json", logs + "/oracle.json"), logs + "/");
        var upstream = logs + "/source/ghc-9.14.1/libraries/ghc-internal/";
        var sourceHashes = new LinkedHashMap<String, String>();
        for (var entry : ((Map<String, String>) proof.get("sourceHashes")).entrySet()) {
            var relative = root.getCanonicalFile().toPath().relativize(new File(entry.getKey()).getCanonicalFile().toPath()).toString().replace('\\', '/');
            sourceHashes.put(relative, entry.getValue());
        }
        OriginalStdioChecks.INSTANCE.hashes(root, sourceHashes, Set.of(upstream + "src/GHC/Internal/Windows.hs",
            upstream + "src/GHC/Internal/IO/Encoding/CodePage.hs", upstream + "src/GHC/Internal/IO/Encoding/CodePage/API.hs",
            upstream + "cbits/Win32Utils.c"), upstream);
        for (var command : (List<Map<String, Object>>) proof.get("commands")) assertEquals(command.get("expectedExit"), command.get("exit"));
        var oracle = json(logs + "/oracle.json");
        assertEquals(263, ((List<?>) oracle.get("mapping")).size());
        assertEquals(16, ((List<?>) oracle.get("lead")).size());
        assertEquals(12, ((List<?>) oracle.get("multi")).size());
        assertEquals(13, ((List<?>) oracle.get("wide")).size());
        for (var stage : List.of("pre", "post")) {
            for (var operation : operations.entrySet()) {
                assertEquals(operation.getValue(), validate(original(operation.getKey())));
                var audit = json(logs + "/" + stage + "-" + operation.getKey() + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            }
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                var language = enter(context);
                try {
                    var module = new LinkedHashMap<>(source(stage));
                    module.put("instrument", true);
                    var executable = program(language, backend, module);
                    var targets = new LinkedHashMap<String, RootCallTarget>();
                    for (var entry : operations.keySet()) targets.put(entry, executable.entryTarget(entry));
                    class Exercise {
                        boolean compiled;
                        Object invoke(String name, Object... args) throws Exception {
                            var target = targets.get(name);
                            if (compiled) valid(target);
                            long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue();
                            var arguments = new Object[args.length + 1];
                            arguments[0] = 0L;
                            System.arraycopy(args, 0, arguments, 1, args.length);
                            var result = Calls.target(target, arguments);
                            if (compiled) {
                                assertEquals(before + 1, ((Number) executable.diagnostics().get("compiledEntries")).longValue(), stage + "/" + backend + "/" + name + " first compiled entry");
                                valid(target);
                            }
                            var handoff = language.getHandoffState().get();
                            assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences$org_intelligence_thc());
                            assertEquals(0, handoff.getResults().retainedReferences());
                            return result;
                        }
                        void run() throws Exception {
                            assertEquals(oracle.get("ansi"), invoke("ansiPage", 0L));
                            assertEquals(oracle.get("console"), invoke("consolePage", 0L));
                            if (Long.valueOf(0).equals(oracle.get("console"))) assertEquals(oracle.get("consoleError"), invoke("windowsError", 0L));
                            for (var row : (List<Map<String, Object>>) oracle.get("info")) {
                                var storage = buffer(34);
                                var output = storage.plus(8);
                                assertEquals(row.get("result"), invoke("pageInfo", row.get("page"), output));
                                if (Long.valueOf(0).equals(row.get("result"))) assertEquals(row.get("error"), invoke("windowsError", 0L));
                                assertEquals(((List<Long>) row.get("bytes")).subList(0, 18), bytes(output, 18));
                                assertEquals(Collections.nCopies(8, 165L), bytes(storage, 8));
                                assertEquals(Collections.nCopies(8, 165L), bytes(storage.plus(26), 8));
                            }
                            for (var row : (List<Map<String, Object>>) oracle.get("lead")) {
                                assertEquals(row.get("result"), invoke("leadByte", row.get("page"), row.get("byte")));
                                if (Long.valueOf(999999).equals(row.get("page"))) assertEquals(row.get("error"), invoke("windowsError", 0L));
                            }
                            for (var row : (List<List<Long>>) oracle.get("mapping")) assertEquals(row.get(1), invoke("mapError", row.get(0)));
                            var mapped = (Map<String, Object>) oracle.get("mappedCurrent");
                            assertEquals(0L, invoke("pageInfo", 999999L, buffer()));
                            assertEquals(mapped.get("error"), invoke("windowsError", 0L));
                            assertEquals(0L, invoke("mapCurrentError", 0L));
                            assertEquals(mapped.get("errno"), Language.currentState(null).getStdio().errno());
                            for (var row : (List<Map<String, Object>>) oracle.get("multi")) {
                                var input = buffer();
                                var output = Boolean.TRUE.equals(row.get("alias")) ? input : buffer();
                                var values = (List<Long>) row.get("input");
                                for (int i = 0; i < values.size(); i++) input.writeWord8(i, values.get(i));
                                var label = row.get("case").toString();
                                assertEquals(row.get("result"), invoke("multiByte", row.get("page"), row.get("flags"), input, row.get("count"),
                                    Boolean.TRUE.equals(row.get("sizing")) ? ManagedAddress.nullAddress() : output, row.get("capacity")), label);
                                if (Long.valueOf(0).equals(row.get("result"))) assertEquals(row.get("error"), invoke("windowsError", 0L), label);
                                assertEquals(row.get("bytes"), bytes(output), label);
                            }
                            for (var row : (List<Map<String, Object>>) oracle.get("wide")) {
                                var input = buffer(64, 165, true);
                                var output = buffer(64, 165, true);
                                var values = (List<Long>) row.get("input");
                                for (int i = 0; i < values.size(); i++) input.writeNativeScalar(i, 2, values.get(i));
                                var def = (List<Long>) row.get("default");
                                var defaultChar = def.isEmpty() ? ManagedAddress.nullAddress() : buffer(4, 0);
                                for (int i = 0; i < def.size(); i++) defaultChar.writeWord8(i, def.get(i));
                                var used = buffer(4, 90);
                                var label = row.get("case").toString();
                                assertEquals(row.get("result"), invoke("wideChar", row.get("page"), row.get("flags"), input, row.get("count"),
                                    Boolean.TRUE.equals(row.get("sizing")) ? ManagedAddress.nullAddress() : output, row.get("capacity"),
                                    defaultChar, Boolean.TRUE.equals(row.get("used")) ? used : ManagedAddress.nullAddress()), label);
                                if (Long.valueOf(0).equals(row.get("result"))) assertEquals(row.get("error"), invoke("windowsError", 0L), label);
                                assertEquals(row.get("bytes"), bytes(output), label);
                                assertEquals(row.get("usedValue"), used.readWord8(0) | (used.readWord8(1) << 8) |
                                    (used.readWord8(2) << 16) | (used.readWord8(3) << 24), label);
                            }
                            for (var row : (List<Map<String, Object>>) oracle.get("messages")) {
                                var address = (ManagedAddress) invoke("errorMessage", row.get("error"));
                                assertEquals(row.get("null"), address == ManagedAddress.nullAddress());
                                if (address != ManagedAddress.nullAddress()) {
                                    var expected = new ArrayList<>((List<Long>) row.get("units"));
                                    assertEquals((expected.size() + 1L) * 2, address.availableBytes());
                                    expected.add(0L);
                                    var actual = new ArrayList<Long>();
                                    for (int i = 0; i < expected.size(); i++) actual.add(address.readWord8(i * 2L) | (address.readWord8(i * 2L + 1) << 8));
                                    assertEquals(expected, actual);
                                }
                                assertSame(ManagedAddress.nullAddress(), invoke("localFree", address));
                            }
                            assertEquals(0, Language.currentState(null).getNativeAllocations().liveCount());
                        }
                    }
                    var exercise = new Exercise();
                    exercise.run();
                    var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    for (var target : targets.values()) {
                        targetClass.getMethod("compile", boolean.class).invoke(target, true);
                        valid(target);
                        var runtime = Truffle.getRuntime();
                        runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target);
                    }
                    exercise.compiled = true;
                    exercise.run();
                    assertEquals(0L, executable.diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            }
        }
    }

    @Test void bufferValidationAndAllocatorIdentityPreserveOwnershipAndContext() {
        WindowsCodePages service;
        ManagedAddress retained;
        ManagedNativeAllocations allocations;
        try (var first = context()) {
            enter(first);
            try {
                service = Language.currentState(null).getWindowsCodePages();
                allocations = Language.currentState(null).getNativeAllocations();
                var output = buffer(18);
                assertNotEquals(0L, service.info(932, output));
                for (var invalid : List.of(buffer(17), ManagedAddress.nullAddress(), ManagedAddress.fromHex("0000"), ManagedAddress.unownedNumeric(1)))
                    assertThrows(RuntimeFault.class, () -> service.info(932, invalid));
                assertThrows(RuntimeFault.class, () -> service.multiByte(1252, 0, buffer(1, 65), -1, output, 4));
                assertThrows(RuntimeFault.class, () -> service.multiByte(1252, 0, buffer(1), 2, output, 4));
                assertThrows(RuntimeFault.class, () -> service.multiByte(1252, 0, ManagedAddress.nullAddress(), 1, output, 4));
                assertThrows(RuntimeFault.class, () -> service.multiByte(1252, 0, buffer(1), -2, output, 4));
                assertThrows(RuntimeFault.class, () -> service.multiByte(1252, 0, buffer(1), 1, output, -1));
                assertThrows(RuntimeFault.class, () -> service.wideChar(1252, 0, buffer(2), 1, buffer(1), 2, ManagedAddress.nullAddress(), buffer(3)));
                var message = service.message(2);
                var alias = message.plus(2);
                assertThrows(RuntimeFault.class, () -> allocations.free(message));
                assertThrows(RuntimeFault.class, () -> allocations.realloc(message, 4));
                assertThrows(RuntimeFault.class, () -> allocations.requireFreeTarget(message));
                assertThrows(RuntimeFault.class, () -> service.localFree(alias));
                message.withNativeBorrow(() -> assertThrows(RuntimeFault.class, () -> service.localFree(message)));
                assertTrue(message.availableBytes() > 2);
                // A context-owned native message may also be a conversion input.
                assertTrue(service.wideChar(65001, 0, message, -1, ManagedAddress.nullAddress(), 0,
                    ManagedAddress.nullAddress(), ManagedAddress.nullAddress()) > 0);
                service.localFree(message);
                assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
                assertThrows(RuntimeFault.class, () -> service.localFree(message));
                retained = service.message(5);
            } finally { first.leave(); }
            try (var second = context()) {
                enter(second);
                try {
                    assertThrows(RuntimeFault.class, () -> service.codePage(false));
                    assertThrows(RuntimeFault.class, () -> retained.readWord8(0));
                    assertThrows(RuntimeFault.class, () -> Language.currentState(null).getWindowsCodePages().localFree(retained));
                    assertEquals(0L, Language.currentState(null).getWindowsCodePages().error());
                } finally { second.leave(); }
            }
        }
        assertEquals(0, allocations.liveCount());
        try (var denied = context(false)) {
            enter(denied);
            try { assertThrows(SecurityException.class, () -> Language.currentState(null).getWindowsCodePages().codePage(false)); }
            finally { denied.leave(); }
        }
    }

    @Test void localFreeWaitsForAnActiveNativeBorrow() throws Exception {
        try (var context = context()) {
            enter(context);
            try {
                var service = Language.currentState(null).getWindowsCodePages();
                var address = service.message(2);
                var borrow = address.nativeAllocation().borrow();
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var started = new CountDownLatch(1);
                    var freeing = executor.submit(() -> {
                        context.enter();
                        try { started.countDown(); service.localFree(address); }
                        finally { context.leave(); }
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> freeing.get(100, TimeUnit.MILLISECONDS));
                    assertTrue(borrow.segment().byteSize() > 2);
                    borrow.close();
                    freeing.get(5, TimeUnit.SECONDS);
                    assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                    assertEquals(0, Language.currentState(null).getNativeAllocations().liveCount());
                } finally { borrow.close(); executor.shutdownNow(); }
            } finally { context.leave(); }
        }
    }

    @Test void exactForeignProofAndStateRejectBeforeAnOutputEffect() throws Exception {
        for (var operation : operations.entrySet()) {
            var call = original(operation.getKey());
            assertEquals(operation.getValue(), validate(call));
            for (var field : Map.of("safety", "safe", "convention", "stdcall", "arity", 42L).entrySet()) {
                var bad = (List<Object>) Json.INSTANCE.parse(Json.INSTANCE.stringify(call));
                ((Map<String, Object>) ((Map<?, ?>) bad.get(6)).get("foreignCall")).put(field.getKey(), field.getValue());
                assertThrows(RuntimeFault.class, () -> validate(bad));
            }
            var bad = (List<Object>) Json.INSTANCE.parse(Json.INSTANCE.stringify(call));
            ((Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) bad.get(6)).get("foreignCall")).get("target")).put("unit", "main");
            assertThrows(RuntimeFault.class, () -> validate(bad));
        }
        var call = original("pageInfo");
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            var language = enter(context);
            try {
                var target = program(language, backend, OriginalStdioChecks.INSTANCE.rawModule(call, source(), null)).entryTarget("entry");
                var output = buffer(18);
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[] {0L, 932L, output, 7L}));
                assertEquals(Collections.nCopies(18, 165L), bytes(output, 18));
            } finally { context.leave(); }
        }
    }
}
