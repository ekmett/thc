// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class CStringTest {
    @TempDir Path temporary;
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
    private final Map<String, Object> integerRep = proof("IntRep");
    private final Map<String, Object> characterRep = proof("WordRep");
    private final Map<String, Object> addressRep = map("kind", "address", "evaluated", true, "primReps", list("AddrRep"));
    private final Map<String, Object> dataRep = map("kind", "data", "evaluated", false, "primReps", list("BoxedRep (Just Lifted)"));
    private final Map<String, Object> closureRep = map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)"));
    private record Read(String operation, String rep) {}
    private void compile(RootCallTarget target) throws Exception {
        var targetClass = target.getClass();
        targetClass.getMethod("compile", boolean.class).invoke(target, true);
        targetClass.getMethod("waitForCompilation").invoke(target);
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
                var read = primitive(readCase.operation(), resultProof, bytes("%02x".formatted(value)), integer(0));
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
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map("rep", integerRep)); }
    private List<Object> bytes(String hex) { return list("lit", "string-bytes", hex, map("rep", addressRep)); }
    private List<Object> apply(List<Object> function, List<List<Object>> arguments, List<Boolean> lifted, Map<String, Object> resultRep) {
        return list("app", function, arguments, lifted, false, false, map("rep", resultRep));
    }
    @SafeVarargs private final List<Object> primitive(String name, Map<String, Object> resultRep, List<Object>... args) {
        return apply(list("prim", name, map()), list(args), Collections.nCopies(args.length, false), resultRep);
    }
    private Map<String, Object> binder(String id, String type, boolean lifted, Map<String, Object> rep) {
        return map("id", id, "name", id, "type", type, "lifted", lifted, "coercion", false, "rep", rep);
    }
    private Map<String, Object> binding(String id, Map<String, Object> argument, Map<String, Object> resultRep, List<Object> body) {
        return map("id", id, "name", id, "arity", 1, "lifted", true, "type", "Synthetic", "rep", closureRep,
            "expr", list("lam", list(argument), body, map("rep", closureRep, "resultRep", resultRep)));
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return map("schema", 1, "ghc", "9.14.1", "unit", "main", "boundary", "pre-core", "module", "Synthetic.CString", "bindings", bindings, "constructors", list()); }
    private Path artifact(Map<String, Object> module) throws Exception {
        return CoreCbdFixtures.write(temporary.resolve(UUID.randomUUID() + ".cbd"), module);
    }
    private String request(Map<String, Object> module, String backend) throws Exception {
        return CoreModules.request(list(artifact(module).toString()), "entry", true, false, backend);
    }
    private long count(Value fn, String key) { return ((Number) object(Json.parse(fn.getMember("diagnostics").asString())).get(key)).longValue(); }
    @Test void literalPrimitivesExecuteInGuestCodeAndRejectNumericFakePointers() throws Exception {
        var at = primitive("plusAddr#", addressRep, bytes("ff4100"), variable("offset", integerRep)); var read = primitive("indexCharOffAddr#", characterRep, at, integer(0));
        for (var backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var fn = context.eval("thc", request(module(list(binding("entry", binder("offset", "Int#", false, integerRep), characterRep, read))), backend)); long[] expected = {255L, 65L, 0L, 0L};
            for (int i = 0; i < 10; i++) for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong());
            assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "compiledEntries");
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong()); assertTrue(count(fn, "compiledEntries") > before);
            var bounds = assertThrows(PolyglotException.class, () -> fn.execute(4)); assertTrue(bounds.getMessage().contains("outside its backing storage"), bounds.getMessage());
            var fake = context.eval("thc", request(module(list(binding("entry", binder("address", "Int#", false, integerRep), characterRep,
                primitive("indexCharOffAddr#", characterRep, variable("address", integerRep), integer(0))))), backend));
            var wrong = assertThrows(PolyglotException.class, () -> fake.execute(0)); assertTrue(wrong.getMessage().contains("Expected a managed literal Addr#"), wrong.getMessage());
        }
    }
    private String constructor(List<Map<String, Object>> constructors, String name) {
        var matches = constructors.stream().filter(value -> Objects.equals(value.get("name"), name)).toList(); assertEquals(1, matches.size()); return (String) matches.getFirst().get("id");
    }
    @Test void genuineGhcCStringDecoderConsumesLiteralBytesBeforeAndAfterCompilation() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot")); var original = root.resolve("build/map/boot-core/GHC.Internal.CString.cbd"); var exported = CoreCbdFixtures.read(original);
        var constructors = objects(exported.get("constructors")); var bindings = objects(exported.get("bindings"));
        var matches = bindings.stream().filter(value -> Objects.equals(value.get("id"), "ghc-internal:GHC.Internal.CString.unpackCString#")).toList(); assertEquals(1, matches.size()); var unpack = (String) matches.getFirst().get("id");
        var summed = primitive("+#", integerRep, primitive("ord#", integerRep, variable("char", characterRep)),
            apply(variable("sumChars", closureRep), list(variable("tail", dataRep)), list(true), integerRep));
        var unbox = list("case", variable("head", dataRep), "boxedChar", list(
            list("data", constructor(constructors, "C#"), list("char"), summed, map("binders", list(binder("char", "Char#", false, characterRep))))),
            map("rep", integerRep, "binder", binder("boxedChar", "Char", true, with(dataRep, "evaluated", true))));
        var traverse = list("case", variable("list", dataRep), "spine", list(
            list("data", constructor(constructors, "[]"), list(), integer(0), map("binders", list())),
            list("data", constructor(constructors, ":"), list("head", "tail"), unbox,
                map("binders", list(binder("head", "Char", true, dataRep), binder("tail", "[Char]", true, dataRep))))),
            map("rep", integerRep, "binder", binder("spine", "[Char]", true, with(dataRep, "evaluated", true))));
        var address = primitive("plusAddr#", addressRep, bytes("41ff420043"), variable("offset", integerRep));
        var decoded = apply(variable(unpack, closureRep), list(address), list(false), dataRep);
        var driver = module(list(binding("sumChars", binder("list", "[Char]", true, dataRep), integerRep, traverse),
            binding("entry", binder("offset", "Int#", false, integerRep), integerRep,
                apply(variable("sumChars", closureRep), list(decoded), list(true), integerRep))));
        var driverFile = artifact(driver);
        for (var backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var fn = Main.loadEntry(context, list(original.toString(), driverFile.toString()), "entry", true, backend); long[] expected = {386L, 321L, 66L, 0L, 67L, 0L};
            for (int i = 0; i < 8; i++) for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong(), backend + "/" + offset);
            assertTrue(fn.invokeMember("compile").asBoolean()); long before = count(fn, "compiledEntries");
            for (int offset = 0; offset < expected.length; offset++) assertEquals(expected[offset], fn.execute(offset).asLong(), backend + "/" + offset + " compiled");
            assertTrue(count(fn, "compiledEntries") > before);
        }
    }
}
