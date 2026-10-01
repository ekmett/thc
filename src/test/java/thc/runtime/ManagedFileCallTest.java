// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Language;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic ABI execution controls; public System.IO Handle coverage is separate. */
@SuppressWarnings("unchecked")
class ManagedFileCallTest {
    @TempDir Path directory;
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowIO(IOAccess.ALL).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray((path + "\u0000").getBytes(StandardCharsets.UTF_8)); }
    @Test void normalAndInstalledCompiledCallsPreserveVisibleFilesTypedPayloadsAndErasedState() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = ManagedFileFixtures.module(ManagedFileFixtures.signatures.keySet(), ignored -> {});
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
                var targets = new LinkedHashMap<String, RootCallTarget>();
                for (var name : ManagedFileFixtures.signatures.keySet()) targets.put(name, program.entryTarget(name));
                class Caller {
                    boolean compiled;
                    Object call(String name, Object... arguments) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var target = targets.get(name);
                        var packet = new Object[arguments.length + 2]; packet[0] = 0L;
                        System.arraycopy(arguments, 0, packet, 1, arguments.length); packet[packet.length-1] = Unit.INSTANCE;
                        var result = callScalarTestTarget(target, packet);
                        if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue(), name); valid(target); }
                        released(language); return result;
                    }
                    void exercise(String pass) throws Exception {
                        var file = directory.resolve(backend + "-" + pass + ".bin");
                        long fd = (Long) call("open", path(file), 3L); assertTrue(fd >= 3L);
                        var bytes = new byte[]{0,1,127,-128,-1,10};
                        assertEquals(6L, call("write", fd, ManagedAddress.fromByteArray(bytes), 6L));
                        assertArrayEquals(bytes, Files.readAllBytes(file)); assertEquals(6L, call("size", fd));
                        assertEquals(0L, call("device_type", fd)); assertEquals(0L, call("is_terminal", fd));
                        assertEquals(0L, call("seek", fd, 0L, 0L));
                        var buffer = new byte[10]; Arrays.fill(buffer, (byte) 0x55);
                        assertEquals(6L, call("read", fd, ManagedAddress.fromByteArray(buffer).plus(2L), 8L));
                        assertArrayEquals(new byte[]{0x55,0x55,0,1,127,-128,-1,10,0x55,0x55}, buffer);
                        assertEquals(0L, call("read", fd, ManagedAddress.fromByteArray(buffer), 10L));
                        assertEquals(4L, call("seek", fd, -2L, 2L)); assertEquals(3L, call("seek", fd, -1L, 1L));
                        assertEquals(0L, call("set_size", fd, 3L)); assertEquals(3L, call("size", fd));
                        assertArrayEquals(Arrays.copyOf(bytes, 3), Files.readAllBytes(file)); assertEquals(0L, call("close", fd));
                        assertEquals(-1L, call("size", fd)); assertEquals(4L, call("error_kind"));
                        var error = (ManagedAddress) call("error_message"); assertTrue(error.indexChar(0L) != 0L);
                        assertEquals(4L, call("error_kind"));
                        long append = (Long) call("open", path(file), 2L); assertTrue(append >= 3L);
                        assertEquals(2L, call("write", append, ManagedAddress.fromByteArray(new byte[]{9,8}), 2L));
                        assertEquals(0L, call("close", append)); assertArrayEquals(new byte[]{0,1,127,9,8}, Files.readAllBytes(file));
                        long input = (Long) call("open", path(file), 0L); assertTrue(input >= 3L); assertEquals(0L, call("close", input));
                    }
                }
                var caller = new Caller(); caller.exercise("normal");
                for (var target : targets.values()) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
                caller.compiled = true; caller.exercise("compiled"); caller.compiled = false;
                // An invalid State must fault before truncate/open or writing guest data.
                var untouched = directory.resolve(backend + "-state.bin"); Files.write(untouched, new byte[]{8,7,6});
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(targets.get("open"), new Object[]{0L,path(untouched),1L,9L}));
                assertArrayEquals(new byte[]{8,7,6}, Files.readAllBytes(untouched));
                long fd = (Long) caller.call("open", path(untouched), 3L); var buffer = new byte[]{4,5};
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(targets.get("read"), new Object[]{0L,fd,ManagedAddress.fromByteArray(buffer),2L,9L}));
                assertArrayEquals(new byte[]{4,5}, buffer); assertEquals(0L, caller.call("seek", fd, 0L, 1L));
                assertEquals(0L, caller.call("close", fd)); released(language);
            } finally { context.leave(); }
        }
    }
    @Test void bothLoadersRejectBoundHeadsUnknownSymbolsAndMalformedProofs() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                java.util.function.Function<Consumer<List<Object>>, ExecutableProgram> load = mutate -> {
                    var module = ManagedFileFixtures.module(List.of("open"), call -> mutate.accept(call));
                    return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
                };
                for (var id : Arrays.asList(null, "", 3L, "p0", "open"))
                    assertThrows(RuntimeFault.class, () -> load.apply(call -> ((List<Object>) call.get(1)).set(1, id)));
                for (var head : List.of(List.of("prim", "open"), List.of("var", "foreign-open"),
                    List.of("var", "foreign-open", Map.of("rep", ManagedFileFixtures.scalar(null, true)))))
                    assertThrows(RuntimeFault.class, () -> load.apply(call -> call.set(1, head)));
                for (var change : new Object[][]{{"convention","ccall"},{"convention","javascript"},{"safety","unsafe"},{"arity",3.0},{"suppliedArity",2L}})
                    assertThrows(RuntimeFault.class, () -> load.apply(call -> {
                        var descriptor = (Map<String, Object>) ((Map<String, Object>) call.get(6)).get("foreignCall");
                        descriptor.put((String) change[0], change[1]);
                    }));
                assertThrows(RuntimeFault.class, () -> load.apply(call -> {
                    var descriptor = (Map<String, Object>) ((Map<String, Object>) call.get(6)).get("foreignCall");
                    ((Map<String, Object>) descriptor.get("target")).put("symbol", "thc_io_v1_unknown");
                }));
                assertThrows(RuntimeFault.class, () -> load.apply(call -> ((List<Object>) call.get(3)).set(0, true)));
                assertThrows(RuntimeFault.class, () -> load.apply(call -> {
                    var arguments = (List<List<Object>>) call.get(2);
                    arguments.get(2).set(2, Map.of("rep", ManagedFileFixtures.scalar("IntRep", true)));
                }));
                assertThrows(RuntimeFault.class, () -> load.apply(call -> {
                    var arguments = (List<List<Object>>) call.get(2);
                    arguments.get(0).set(1, "p1"); // Occurrence claims AddrRep; lexical binder proves IntRep.
                }));
                assertThrows(UnsupportedCore.class, () -> load.apply(call -> ((Map<String, Object>) call.get(6)).remove("foreignCall")));
            } finally { context.leave(); }
        }
    }
}
