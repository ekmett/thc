// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;

class ByteStringDataLabelsTest {
    @TempDir Path directory;
    private static final String SYMBOL = "hs_bytestring_lower_hex_table";
    private static final Map<String, Object> ADDRESS = Map.of("kind", "address", "primReps", List.of("AddrRep"), "evaluated", true);
    private static final Map<String, Object> INDEX = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static final Map<String, Object> WORD16 = Map.of("kind", "long", "primReps", List.of("Word16Rep"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);

    private ManagedAddress table() { return CoreDataLabels.fromCore(SYMBOL, CoreRepresentations.parse(ADDRESS)); }

    private String command(String... arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output.strip();
    }
    private PackageScalarLink library() throws Exception {
        var source = directory.resolve("data.c"); var bitcode = directory.resolve("data.bc");
        var original = Path.of("nih/pinned/bytestring-0.12.2.0/cbits/aligned-static-hs-data.c").toAbsolutePath();
        Files.writeString(source, "#include \"" + original.toString().replace("\\", "\\\\").replace("\"", "\\\"") +
            "\"\nconst void *table_address(void) { return &" + SYMBOL + "; }\n");
        var includes = command(System.getenv().getOrDefault("GHC_PKG", "ghc-pkg"), "field", "rts", "include-dirs", "--simple-output");
        var compile = new ArrayList<String>(); compile.add(System.getenv().getOrDefault("THC_CLANG", "clang"));
        if (System.getProperty("os.name").equals("Linux")) compile.add("--target=" +
            (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu");
        // ghc-pkg emits a list, quoting paths that contain spaces.
        var directories = new StreamTokenizer(new StringReader(includes));
        directories.resetSyntax();
        directories.whitespaceChars(0, ' ');
        directories.wordChars('!', 255);
        directories.quoteChar('"');
        while (directories.nextToken() != StreamTokenizer.TT_EOF) {
            assertNotNull(directories.sval, "Invalid GHC include directory");
            compile.add("-I" + directories.sval);
        }
        compile.addAll(List.of("-O1", "-emit-llvm", "-c", source.toString(), "-o", bitcode.toString()));
        command(compile.toArray(String[]::new));
        var bytes = Files.readAllBytes(bitcode);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new PackageScalarLink("bytestring-data", "test-host", digest, digest, bytes,
            List.of(new PackageScalarSignature(SYMBOL, "table_address", List.of(), "AddrRep")),
            "llvm-bitcode", Set.of(), new byte[0], Set.of("table_address"));
    }

    private int expectedWord(int value) {
        String digits = HexFormat.of().toHexDigits((byte) value);
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
            ? digits.charAt(0) | digits.charAt(1) << 8
            : digits.charAt(0) << 8 | digits.charAt(1);
    }


    @Test void exactSymbolAndEvaluatedAddressProofRemainRequired() {
        assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(SYMBOL, null));
        for (var change : List.of(Map.of("evaluated", false), Map.of("kind", "long"),
                                 Map.of("primReps", List.of("WordRep")), Map.of("aggregate", "unboxed-tuple"))) {
            var bad = new LinkedHashMap<String, Object>(ADDRESS);
            bad.putAll(change);
            assertThrows(RuntimeFault.class, () -> CoreDataLabels.fromCore(SYMBOL, CoreRepresentations.parse(bad)));
        }
    }

    private Map<String, Object> module() {
        var label = List.of("lit", "data-addr", SYMBOL, Map.of("rep", ADDRESS));
        var parameter = Map.of("id", "index", "lifted", false, "rep", INDEX);
        var call = List.of("app", List.of("prim", "indexWord16OffAddr#"),
            List.of(label, List.of("var", "index", Map.of("rep", INDEX))),
            List.of(false, false), false, false, Map.of("rep", WORD16));
        return Map.of("instrument", true, "constructors", List.of(),
            "bindings", List.of(Map.of("id", "read", "name", "read", "arity", 1,
                "lifted", true, "rep", CLOSURE,
                "expr", List.of("lam", List.of(parameter), call, Map.of("rep", CLOSURE, "resultRep", WORD16)))));
    }

    @SuppressWarnings("unchecked")
    @Test void demandPreparationDoesNotResolveUnlinkedNativeDataLiterals() {
        for (String backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var strict = Map.<String,Object>of("id", "label", "name", "label", "arity", 0, "lifted", false,
                    "rep", ADDRESS, "expr", List.of("lit", "data-addr", SYMBOL, Map.of("rep", ADDRESS)));
                var read = (Map<String,Object>) ((List<?>) module().get("bindings")).getFirst();
                var definitions = Map.of("read", read, "label", strict);
                var demand = new CoreDemandBindings[1];
                demand[0] = new CoreDemandBindings(definitions::containsKey, definitions::get, id -> null, (id, binding) -> {
                    var selected = Map.<String,Object>of("bindings", List.of(binding), "constructors", List.of(), "demandBindings", demand[0]);
                    return backend.equals("ast") ? new Program(language, selected) : new BytecodeProgram(language, selected);
                }, true, definitions::containsKey);
                for (String id : definitions.keySet()) {
                    demand[0].prepareCode(id);
                    assertNull(demand[0].cell(id).peek(), backend + " preparation cannot initialize a native label");
                }
                var failure = assertThrows(RuntimeFault.class, () -> demand[0].cell("label").read());
                assertTrue(failure.getMessage().contains("Unlinked native data label"), failure.getMessage());
            } finally { context.leave(); }
        }
    }

    @Test void bothBackendsReadTheTableOnTheFirstInstalledCall() throws Exception {
        var link = library();
        for (String backend : List.of("ast", "bytecode"))
            try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var threads = Language.currentState().getThreads();
                    threads.enterCurrent(null, false, true, null);
                    try {
                        var libraries = Language.currentState().getPackageCbits();
                        libraries.link(link);
                        var address = table();
                        for (int value : List.of(0, 127, 255)) assertEquals(expectedWord(value), ManagedAddressRead.WORD16.readInt(address, value));
                        assertEquals(0, address.readWord8(512));
                        assertThrows(RuntimeFault.class, () -> libraries.dataAddress("another-unit", SYMBOL));
                        assertThrows(RuntimeFault.class, () -> libraries.dataAddress(null, "missing_native_global"));
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, module()) : new BytecodeProgram(language, module());
                        var target = program.entryTarget("read");
                        for (int i = 0; i < 3; i++)
                            assertEquals(expectedWord(i), Calls.target(target, new Object[]{0L, (long) i}));
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(expectedWord(255), Calls.target(target, new Object[]{0L, 255L}));
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        assertEquals(0, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { context.leave(); }
            }
    }
}
