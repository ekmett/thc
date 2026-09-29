// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class CStringTest {
    @Test void statelessAddressReadsPreserveCarriersAndRejectCoercions() {
        var address = ManagedAddress.fromHex("0000");
        assertEquals(0L, BytecodeRoot.AddressIndexManagedScalar.index(ManagedAddressRead.CHAR, address, 0L));
        for (var operation : list(ManagedAddressRead.INT16, ManagedAddressRead.WORD16))
            assertEquals(0, BytecodeRoot.AddressIndexManagedScalar.index(operation, address, 0L));
        for (boolean signed : list(false, true)) {
            assertEquals(0, BytecodeRoot.AddressIndexByte.index(signed, address, 0L));
            for (Object invalid : list(0, 0.0, null)) assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexByte.index(signed, address, invalid));
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexByte.index(signed, 0L, 0L));
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexByte.index(signed, address, -1L));
        }
        for (var operation : list(ManagedAddressRead.CHAR, ManagedAddressRead.INT16, ManagedAddressRead.WORD16)) {
            for (Object invalid : list(0, 0.0, null)) assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexManagedScalar.index(operation, address, invalid));
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexManagedScalar.index(operation, 0L, 0L));
            assertThrows(RuntimeFault.class, () -> BytecodeRoot.AddressIndexManagedScalar.index(operation, address, -1L));
        }
    }
    private Map<String, Object> proof(String rep) { return map("kind", "long", "evaluated", true, "primReps", list(rep)); }
    private record Read(String operation, String rep) {}
    private void compile(RootCallTarget target) throws Exception {
        var targetClass = target.getClass();
        targetClass.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    @ParameterizedTest @CsvSource({"ast,false", "ast,true", "bytecode,false", "bytecode,true"})
    void characterAddressReadsKeepMachineCarriersDistinctFromNarrowBytes(String backend, boolean async) throws Exception {
        for (var readCase : list(new Read("indexCharOffAddr#", "WordRep"), new Read("indexWord8OffAddr#", "Word8Rep"), new Read("indexInt8OffAddr#", "Int8Rep"))) {
            for (int value : new int[]{0, 127, 128, 255}) for (boolean compiled : list(false, true)) {
                // Char# uses WordRep despite its one-byte memory access. The return proof
                // avoids unrelated first-write specialization of a synthetic case slot.
                var resultProof = proof(readCase.rep());
                var read = new ArrayList<>(primitive(readCase.operation(), list("lit", "string-bytes", "%02x".formatted(value)), integer(0)));
                read.addAll(list(false, false, map("rep", resultProof)));
                var binding = map("id", "entry", "name", "entry", "arity", 1, "lifted", true,
                    "expr", list("lam", list(map("id", "unused", "name", "unused", "lifted", false, "rep", proof("IntRep"))), read, map("resultRep", resultProof)));
                var raw = with(module(list(binding)), "instrument", true);
                try (var context = Main.executionContext(false)) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, raw, async, false) : new BytecodeProgram(language, raw, async);
                        var target = program.entryTarget("entry"); var targetClass = target.getClass();
                        if (compiled) compile(target);
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        Object expected;
                        if (readCase.rep().equals("WordRep")) expected = (long) value;
                        else if (readCase.rep().equals("Int8Rep")) expected = (int) (byte) value;
                        else expected = value;
                        assertEquals(expected, Calls.target(target, new Object[]{0L, 0L}), backend + "/async=" + async + "/" + readCase.operation() + "/" + value + "/compiled=" + compiled);
                        if (compiled) {
                            assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                            assertSame(target, program.entryTarget("entry"));
                            if (backend.equals("bytecode") && async) {
                                // The cold async guards use ordinary branch profiles and may
                                // deopt before the read. Recompile that one observed path so the
                                // address result retains its exact carrier and installed target.
                                compile(target);
                                before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(expected, Calls.target(target, new Object[]{0L, 0L}));
                                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                                assertSame(target, program.entryTarget("entry"));
                            }
                            assertEquals(true, targetClass.getMethod("isValidLastTier").invoke(target), "The original target must retain compiled address execution");
                        }
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void literalStoragePreservesUnsignedBytesEmbeddedNulsAndImplicitTerminator() {
        var address = ManagedAddress.fromHex("ff800041");
        assertEquals(255L, address.indexChar(0)); assertEquals(128L, address.indexChar(1)); assertEquals(0L, address.indexChar(2)); assertEquals(65L, address.indexChar(3));
        assertEquals(0L, address.indexChar(4), "GHC static literals append one NUL byte"); assertEquals(0L, ManagedAddress.fromHex("").indexChar(0)); assertSame(address, address.plus(0));
        var shifted = address.plus(3); assertEquals(65L, shifted.indexChar(0)); assertEquals(255L, shifted.indexChar(-3)); assertEquals(128L, shifted.plus(-2).indexChar(0));
        assertEquals(255L, address.indexChar(0), "Offset arithmetic does not mutate the original address");
    }
    @Test void literalMemoryAccessChecksBoundsWhileAddressArithmeticRetainsItsOrigin() {
        var address = ManagedAddress.fromHex("41");
        for (long offset : new long[]{-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> address.indexChar(offset));
        for (long offset : new long[]{-1L, 3L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            // Sentinels outside the allocation retain their origin but cannot be read.
            var sentinel = address.plus(offset); assertThrows(RuntimeFault.class, () -> sentinel.indexChar(0));
        }
        assertTrue(address.plus(-1).plus(1).sameLocation(address)); assertTrue(address.plus(3).plus(-3).sameLocation(address));
        var onePast = address.plus(2); assertEquals(0L, onePast.indexChar(-1)); assertThrows(RuntimeFault.class, () -> onePast.indexChar(0));
        assertThrows(RuntimeFault.class, () -> onePast.plus(Long.MAX_VALUE));
        for (var hex : list("0", "gg", "0z")) assertThrows(RuntimeFault.class, () -> ManagedAddress.fromHex(hex));
    }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted) { return list("app", function, arguments, lifted); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return apply(list("prim", name), list(args), Collections.nCopies(args.length, false)); }
    private Map<String, Object> binding(String id, String argument, boolean lifted, List<Object> body) {
        return map("id", id, "name", id, "arity", 1, "lifted", true, "type", "Synthetic", "expr", list("lam", list(map("id", argument, "name", argument, "type", "Synthetic", "lifted", lifted, "coercion", false)), body));
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.CString", "bindings", bindings, "constructors", list()); }
    private String request(List<Map<String, Object>> modules) { return Json.stringify(map("entry", "entry", "modules", modules)); }
    private long count(Value fn, String key) { return ((Number) object(Json.parse(fn.getMember("diagnostics").asString())).get(key)).longValue(); }
    @Test void literalPrimitivesExecuteInGuestCodeAndRejectNumericFakePointers() {
        var at = primitive("plusAddr#", list("lit", "string-bytes", "ff4100"), variable("offset")); var read = primitive("indexCharOffAddr#", at, integer(0));
        try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(list(module(list(binding("entry", "offset", false, read)))))); long[] expected = {255L, 65L, 0L, 0L};
            for (int i = 0; i < 10; i++) for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong());
            assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "compiledEntries");
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong()); assertTrue(count(fn, "compiledEntries") > before);
            var bounds = assertThrows(PolyglotException.class, () -> fn.execute(4)); assertTrue(bounds.getMessage().contains("outside its backing storage"), bounds.getMessage());
            var fake = context.eval("thc", request(list(module(list(binding("entry", "address", false, primitive("indexCharOffAddr#", variable("address"), integer(0))))))));
            var wrong = assertThrows(PolyglotException.class, () -> fake.execute(0)); assertTrue(wrong.getMessage().contains("Expected a managed literal Addr#"), wrong.getMessage());
        }
    }
    private String constructor(List<Map<String, Object>> constructors, String name) {
        var matches = constructors.stream().filter(value -> Objects.equals(value.get("name"), name)).toList(); assertEquals(1, matches.size()); return (String) matches.getFirst().get("id");
    }
    @Test void genuineGhcCStringDecoderConsumesLiteralBytesBeforeAndAfterCompilation() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot")); var exported = CoreCbdFixtures.read(root.resolve("build/map/boot-core/GHC.Internal.CString.cbd"));
        var constructors = objects(exported.get("constructors")); var bindings = objects(exported.get("bindings"));
        var matches = bindings.stream().filter(value -> Objects.equals(value.get("name"), "unpackCString#")).toList(); assertEquals(1, matches.size()); var unpack = (String) matches.getFirst().get("id");
        var summed = primitive("+#", primitive("ord#", variable("char")), apply(variable("sumChars"), list(variable("tail")), list(true)));
        var unbox = list("case", variable("head"), "boxedChar", list(list("data", constructor(constructors, "C#"), list("char"), summed)));
        var traverse = list("case", variable("list"), "spine", list(list("data", constructor(constructors, "[]"), list(), integer(0)), list("data", constructor(constructors, ":"), list("head", "tail"), unbox)));
        var address = primitive("plusAddr#", list("lit", "string-bytes", "41ff420043"), variable("offset")); var decoded = apply(variable(unpack), list(address), list(false));
        var driver = module(list(binding("sumChars", "list", true, traverse), binding("entry", "offset", false, apply(variable("sumChars"), list(decoded), list(true)))));
        for (var backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", Json.stringify(map("entry", "entry", "modules", list(exported, driver), "backend", backend))); long[] expected = {386L, 321L, 66L, 0L, 67L, 0L};
            for (int i = 0; i < 8; i++) for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong(), backend + "/" + offset);
            assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "compiledEntries");
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong(), backend + "/" + offset + " compiled");
            assertTrue(count(fn, "compiledEntries") > before);
        }
    }
}
