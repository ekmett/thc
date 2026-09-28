// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.NarrowIntegerCarrierTestKt.callScalarTestTarget;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static thc.runtime.OriginalStdioChecks.*;

/** Original ghc-internal errno messages against an exact typed FCall consumer. */
@SuppressWarnings("unchecked")
class OriginalStrerrorNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File fixture = new File(root,"build/original-strerror");
    private Map<String,Object> json(File path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(path.toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    @SuppressWarnings("unchecked") private static <E extends Throwable,T> T propagate(Throwable failure) throws E { throw (E) failure; }
    @Test void ownedNativeOutputRemainsBorrowedUntilCopybackCompletes() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch")));
        var manifest = json(new File(fixture,"manifest.json")); hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalStrerrorNative.hs"));
        hashes(root,manifest.get("artifactHashes"),Set.of("build/original-strerror/oracle.json"),"build/original-strerror/");
        var rows = new ArrayList<Map<String,Object>>(); var errors = new ArrayList<Object>();
        for (var row : (List<Map<String,Object>>) json(new File(fixture,"oracle.json")).get("raw")) if (Objects.equals(row.get("length"),512L)) { rows.add(row); errors.add(row.get("errno")); }
        assertEquals(List.of(22L,999999L),errors);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            var executor = Executors.newSingleThreadExecutor(); context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var registry = owner.getNativeAllocations();
                for (var row : rows) {
                    var base = registry.malloc(528); var output = base.plus(8); for (long i = 0; i < 528; i++) base.writeWord8(i,0x55);
                    long error = ((Number) row.get("errno")).longValue(), expected = ((Number) row.get("status")).longValue();
                    assertEquals(expected,owner.getStrerror().call(error,output,512));
                    var bytes = new ArrayList<Long>(); for (int i = 0; i < 8; i++) bytes.add(0x55L); for (var value : (List<Number>) row.get("bytes")) bytes.add(value.longValue()); for (int i = 0; i < 8; i++) bytes.add(0x55L);
                    var actual = new ArrayList<Long>(); for (long i = 0; i < 528; i++) actual.add(base.readWord8(i)); assertEquals(bytes,actual,"native output and canaries");
                    for (long i = 0; i < 528; i++) base.writeWord8(i,0x55);
                    var startFree = new CountDownLatch(1); var freeingStarted = new CountDownLatch(1);
                    var freeing = executor.submit(() -> { context.enter(); try { assertTrue(startFree.await(5,TimeUnit.SECONDS)); freeingStarted.countDown(); registry.free(base); return null; } finally { context.leave(); } });
                    var service = new ManagedStrerror(() -> {
                        // Same-thread free proves the borrow; queued free waits through copyback.
                        var denied = assertThrows(RuntimeFault.class,() -> registry.free(base)); assertTrue(Objects.requireNonNull(denied.getMessage()).contains("borrowed by this thread"));
                        startFree.countDown();
                        try { assertTrue(freeingStarted.await(5,TimeUnit.SECONDS)); } catch (InterruptedException failure) { return propagate(failure); }
                        assertThrows(TimeoutException.class,() -> freeing.get(100,TimeUnit.MILLISECONDS)); return owner.cbits();
                    },owner.getThreads());
                    try {
                        assertEquals(expected,service.call(error,output,512)); freeing.get(5,TimeUnit.SECONDS);
                        assertThrows(RuntimeFault.class,() -> base.readWord8(0)); assertEquals(0,registry.liveCount());
                    } finally { startFree.countDown(); }
                }
            } finally { context.leave(); executor.shutdownNow(); }
        }
    }
    @Test void originalMessagesSurviveNativeCopyAndCompiledCalls() throws Exception {
        var manifest = json(new File(fixture,"manifest.json")); assertEquals(1L,manifest.get("schema")); assertEquals("9.14.1",manifest.get("ghc")); assertEquals("C",manifest.get("locale")); assertEquals(6L,manifest.get("nativeRows"));
        hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalStrerrorNative.hs")); hashes(root,manifest.get("artifactHashes"),Set.of("build/original-strerror/oracle.json"),"build/original-strerror/");
        var oracle = json(new File(fixture,"oracle.json")); var messages = (List<Map<String,Object>>) oracle.get("messages"); var raw = (List<Map<String,Object>>) oracle.get("raw");
        var messageErrors = new ArrayList<Object>(); for (var row : messages) messageErrors.add(row.get("errno")); assertEquals(List.of(2L,22L),messageErrors);
        var rawKeys = new ArrayList<List<Object>>(); for (var row : raw) rawKeys.add(list(row.get("errno"),row.get("length")));
        assertEquals(list(list(22L,512L),list(999999L,512L),list(22L,4L),list(22L,8L)),rawKeys);
        for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = OriginalStdioFixtures.module(List.of("strerror"));
                ExecutableProgram program = backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); var entry = program.entryTarget("strerror");
                class Replay { void run(boolean compiled) throws Exception {
                    for (var row : messages) {
                        byte[] bytes = new byte[512]; Arrays.fill(bytes,(byte) 0x55); var address = ManagedAddress.fromByteArray(bytes); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(0,callScalarTestTarget(entry,new Object[]{0L,((Number) row.get("errno")).intValue(),address,512L,thc.runtime.Unit.INSTANCE}));
                        if (compiled) { assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue()); valid(entry); }
                        int end = -1; for (int i = 0; i < bytes.length; i++) if (bytes[i] == 0) { end = i; break; }
                        assertTrue(end >= 1 && end <= 511); assertEquals(row.get("message"),new String(bytes,0,end,StandardCharsets.US_ASCII));
                    }
                    for (var row : raw) {
                        int length = ((Number) row.get("length")).intValue(); byte[] bytes = new byte[length]; Arrays.fill(bytes,(byte) 0x55); var address = ManagedAddress.fromByteArray(bytes);
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(((Number) row.get("status")).intValue(),callScalarTestTarget(entry,new Object[]{0L,((Number) row.get("errno")).intValue(),address,(long) length,thc.runtime.Unit.INSTANCE}));
                        if (compiled) { assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue()); valid(entry); }
                        var expected = new ArrayList<Long>(); for (var value : (List<Number>) row.get("bytes")) expected.add(value.longValue()); var actual = new ArrayList<Long>(); for (byte value : bytes) actual.add((long) (value & 0xff));
                        assertEquals(expected,actual,"native strerror buffer for " + row.get("errno") + "/" + length);
                    }
                }}
                var replay = new Replay(); replay.run(false); entry.getClass().getMethod("compile",boolean.class).invoke(entry,true); valid(entry); replay.run(true);
                byte[] bytes = new byte[512]; Arrays.fill(bytes,(byte) 0x55); var address = ManagedAddress.fromByteArray(bytes);
                class Control { void reject(Object error,Object output,Object length,Object state) {
                    assertThrows(RuntimeFault.class,() -> callScalarTestTarget(entry,new Object[]{0L,error,output,length,state})); for (byte value : bytes) assertEquals((byte) 0x55,value);
                }}
                assertThrows(RuntimeFault.class,() -> Calls.target(entry,new Object[]{0L,22,address,512L,9L})); var control = new Control(); control.reject(22,address,3L,thc.runtime.Unit.INSTANCE); // C ERANGE indexes buflen-4.
                control.reject(22,address,513L,thc.runtime.Unit.INSTANCE); control.reject(1L << 32,address,512L,thc.runtime.Unit.INSTANCE);
                control.reject(22,address,512L,9L); control.reject(22,ManagedAddress.nullAddress(),512L,thc.runtime.Unit.INSTANCE);
            } finally { context.leave(); }
        }
    }
}
