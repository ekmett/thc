// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.graalvm.polyglot.Context;
import thc.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.withContextProfile;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Actual C-owned static storage, returned by compiled C with no known managed
 * backing. The Core callers are explicit runtime models, not exporter proofs. */
public class NarrowReturnedPointerTest {
    private static final class Buffer extends RootNode {
        @Child private PackageScalarAccess access;
        Buffer(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) { return access.executeAddress(new Object[0], kotlin.Unit.INSTANCE); }
    }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private Map<String, Object> module(String family, boolean byteOffset) {
        Map<String, Object> address = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
        Map<String, Object> index = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        Map<String, Object> value = Map.of("kind", "long", "primReps", List.of(family + "Rep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var parameters = List.of(Map.of("id", "p", "lifted", false, "rep", address), Map.of("id", "i", "lifted", false, "rep", index), Map.of("id", "x", "lifted", false, "rep", value));
        var suffix = byteOffset ? "Word8OffAddrAs" + family + "#" : family + "OffAddr#";
        var write = List.of("app", List.of("prim", "write" + suffix), List.of(variable("p", address), variable("i", index), variable("x", value), List.of("void", Map.of("rep", state))),
            List.of(false, false, false, false), false, false, Map.of("rep", state));
        var read = List.of("app", List.of("prim", "index" + suffix), List.of(variable("p", address), variable("i", index)), List.of(false, false), false, false, Map.of("rep", value));
        var body = List.of("case", write, "stored", List.of(Arrays.asList("default", null, List.of(), read)),
            Map.of("rep", value, "binder", Map.of("id", "stored", "type", "State# RealWorld", "lifted", false, "rep", state)));
        return Map.of("instrument", true, "constructors", List.of(), "bindings", List.of(Map.of("id", "roundtrip", "name", "roundtrip",
            "arity", 3, "lifted", true, "rep", closure, "expr", List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", value)))));
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void check(int raw, NarrowInteger integer, ManagedAddress pointer, RootCallTarget target, long offset, int width, long displacement) {
        int expected = integer.narrow(raw); pointer.fill(32, 90);
        assertEquals(expected, callScalarTestTarget(target, new Object[] {0L, pointer, offset, expected}));
        var result = new byte[32]; pointer.copyToByteArray(result, 0, 32); var expectedBytes = new byte[32]; Arrays.fill(expectedBytes, (byte) 90);
        var buffer = ByteBuffer.wrap(expectedBytes).order(ByteOrder.nativeOrder());
        if (width == 2) buffer.putShort((int) displacement, (short) expected); else buffer.putInt((int) displacement, expected);
        assertArrayEquals(expectedBytes, result, "native bytes and untouched neighbours");
    }

    @ParameterizedTest @CsvSource({
        "ast,Int16,false", "ast,Word16,false", "ast,Int32,false", "ast,Word32,false",
        "ast,Int16,true", "ast,Word16,true", "ast,Int32,true", "ast,Word32,true",
        "bytecode,Int16,false", "bytecode,Word16,false", "bytecode,Int32,false", "bytecode,Word32,false",
        "bytecode,Int16,true", "bytecode,Word16,true", "bytecode,Int32,true", "bytecode,Word32,true"})
    public void cOwnedBufferAcceptsNarrowStoresAndRetainsFirstCompiledEntry(String backend, String family, boolean byteOffset) throws Exception {
        try (var context = withContextProfile(Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000"), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var symbol = "thc_package_pointer_test_buffer"; var signature = new PackageScalarSignature(symbol, symbol, List.of(), "AddrRep");
                final byte[] bytes; try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/thc/cbits/package-pointer.bc"))) { bytes = input.readAllBytes(); }
                var link = new PackageScalarLink("narrow-external-control", "unused", "narrow-external-control", "", bytes, List.of(signature));
                owner.getPackageCbits().link(link); var pointer = (ManagedAddress) new Buffer(language, new PackageScalarCall(link, signature)).getCallTarget().call();
                assertNotNull(pointer.returnedAddress()); assertNull(pointer.returnedAddress().getBacking()); assertNull(pointer.nativeAllocation());
                assertThrows(RuntimeFault.class, pointer::availableBytes);
                var integer = Objects.requireNonNull(NarrowInteger.fromRep(family + "Rep")); int width = integer.getBits() / 8;
                long offset = byteOffset ? 3L : 1L, displacement = byteOffset ? offset : offset * width;
                var values = integer.getBits() == 16 ? List.of(-32768, -1, 0, 32767, 65535) : List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module(family, byteOffset)) : new BytecodeProgram(language, module(family, byteOffset));
                var target = program.entryTarget("roundtrip"); owner.getThreads().enterCurrent();
                try {
                    for (int i = 0; i < 5; i++) for (int value : values) check(value, integer, pointer, target, offset, width, displacement);
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                    var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
                    for (int value : values) {
                        long before = count(program); check(value, integer, pointer, target, offset, width, displacement);
                        assertEquals(before + 1, count(program), "first installed call must enter the original target"); valid(target);
                    }
                    assertThrows(RuntimeFault.class, () -> pointer.writeNativeInt(Long.MAX_VALUE, width, 1));
                    assertThrows(RuntimeFault.class, () -> pointer.writeNativeInt(0, 8, 1));
                } finally { owner.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
}
