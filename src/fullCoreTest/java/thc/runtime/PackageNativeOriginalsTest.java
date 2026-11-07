// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static thc.Main.withContextProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Original package source, typed retained imports, and an independent native
 * Haskell oracle. This checks common foreign adapters, not whole Pandoc Core. */
@SuppressWarnings("unchecked")
public class PackageNativeOriginalsTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/original-native");
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        private final NarrowInteger result;
        Entry(Language language, PackageScalarCall call) {
            super(language); access = new PackageScalarAccess(call);
            result = NarrowInteger.fromRep(call.getSignature().getResult());
        }
        @Override public Object execute(VirtualFrame frame) {
            if (result != null) {
                int value = access.executeInt(frame.getArguments(), thc.runtime.Unit.INSTANCE);
                return result == NarrowInteger.WORD32 ? Integer.toUnsignedLong(value) : (long) value;
            }
            return access.executeLong(frame.getArguments(), thc.runtime.Unit.INSTANCE);
        }
    }
    private static final class Setter extends RootNode {
        @Child private PackageScalarAccess access;
        Setter(Language language, PackageScalarCall call) { super(language); access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            access.executeVoid(frame.getArguments(), thc.runtime.Unit.INSTANCE); return thc.runtime.Unit.INSTANCE;
        }
    }
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
    private List<List<String>> rows(String name) throws Exception {
        var result = new ArrayList<List<String>>();
        for (String line : Files.readAllLines(new File(directory, name).toPath(), StandardCharsets.UTF_8)) result.add(Arrays.asList(line.split("\t", -1)));
        return result;
    }
    private List<File> moduleFiles(String name) {
        File[] files = new File(directory, name).listFiles((dir, file) -> file.endsWith(".cbd")); assertNotNull(files);
        Arrays.sort(files, Comparator.comparing(File::getName)); return Arrays.asList(files);
    }
    private List<Map<String, Object>> modules(List<File> files) throws Exception {
        var result = new ArrayList<Map<String, Object>>(); for (File file : files) result.add(OriginalStdioChecks.module(file)); return result;
    }
    private PackageScalarLink link(Map<String, Object> merged) {
        var links = (List<PackageScalarLink>) merged.get("packageScalarLinks"); assertEquals(1, links.size()); return links.getFirst();
    }
    private Set<String> artifacts(List<String> names) {
        var result = new LinkedHashSet<String>(); for (String name : names) result.add("build/original-native/" + name); return result;
    }
    private Set<String> moduleArtifacts(List<File> files) {
        var result = new LinkedHashSet<String>(); for (File file : files) result.add(root.toPath().relativize(file.toPath()).toString()); return result;
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached);
            }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result);
        result.add(target);
    }
    private List<RootCallTarget> installed(Collection<RootCallTarget> entries) {
        var result = new LinkedHashSet<RootCallTarget>(); for (var entry : entries) result.addAll(targets(entry)); return new ArrayList<>(result);
    }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); }
    private void valid(RootCallTarget target, String message) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), message); }
    private long compiled(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
    }
    private Context context() { return withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private void checkErf(String backend, List<String> row, ExecutableProgram program, Map<String, String> names, Language language) {
        long argument = Long.parseUnsignedLong(row.get(1)), expected = Long.parseUnsignedLong(row.get(2));
        assertEquals(expected, OriginalStdioChecks.invoke(program, names.get(row.getFirst()), argument), backend + "/" + row); released(language);
    }

    @Test public void originalSafeErfImportsMatchNativeInBothFirstInstalledBackends() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(88L, manifest.get("erfNativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("sourceHashes"), Set.of(
            "build/original-native/sources/erf-2.0.0.0/erf.cabal", "build/original-native/sources/erf-2.0.0.0/src/Data/Number/Erf.hs"), "build/original-native/sources/");
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of(
            "t/fixtures/compiler/OriginalErfNative.hs", "t/fixtures/compiler/OriginalErfEntry.hs",
            "t/haskell-fixtures/PackageNativeOriginalsFixtures.hs", "src/driver/THC/Driver/PackageNative.hs", "src/driver/THC/Driver/NativeLibrarySources.hs"), null);
        var moduleNames = List.of("linked/erf-2.0.0.0-inplace/Data.Number.Erf.cbd", "erf-entry/units/u-original-erf-entry/OriginalErfEntry.cbd");
        var namesAndArtifacts = new ArrayList<>(moduleNames); namesAndArtifacts.addAll(List.of("erf-native.tsv", "erf-audit.json"));
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), artifacts(namesAndArtifacts), "build/original-native/");
        assertEquals(true, json(new File(directory, "erf-audit.json")).get("accepted"));
        var modules = new ArrayList<Map<String, Object>>(); for (String name : moduleNames) modules.add(OriginalStdioChecks.module(new File(directory, name)));
        var merged = CoreModules.merge(modules); var link = link(merged); assertEquals("llvm-embedded-elf", link.getFormat());
        var symbols = new LinkedHashSet<String>(); for (var abi : link.getAbi()) {
            symbols.add(abi.getSymbol()); assertEquals("safe", abi.getSafety()); assertEquals(List.of(abi.getResult()), abi.getArguments());
        }
        assertEquals(Set.of("erf", "erfc", "erff", "erfcf"), symbols);
        var names = new LinkedHashMap<String, String>(); names.put("erf", "erfDouble"); names.put("erfc", "erfcDouble"); names.put("erff", "erfFloat"); names.put("erfcf", "erfcFloat");
        names.replaceAll((key, value) -> "original-erf-entry:OriginalErfEntry." + value);
        var rows = rows("erf-native.tsv"); assertEquals(88, rows.size());
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); owner.getPackageCbits().link(link);
                var source = new LinkedHashMap<>(CoreModules.reachable(merged, new ArrayList<>(names.values()), true)); source.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                var entries = new LinkedHashMap<String, RootCallTarget>(); for (var entry : names.entrySet()) entries.put(entry.getKey(), program.entryTarget(entry.getValue()));
                owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    for (var row : rows) checkErf(backend, row, program, names, language);
                    var installed = installed(entries.values()); for (var target : installed) { compile(target); valid(target, backend); }
                    for (var row : rows.reversed()) {
                        long before = compiled(program); checkErf(backend, row, program, names, language); assertTrue(compiled(program) > before);
                        for (var target : installed) valid(target, backend + "/" + target.getRootNode().getName() + " retains first-installed code");
                    }
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
    private byte[] checksumBytes(int size) { byte[] bytes = new byte[size]; for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 37 + 11); return bytes; }
    private void checkDigest(List<String> row, PackageScalarLink link, Map<PackageScalarSignature, RootCallTarget> entries) {
        String symbol = row.getFirst(), carrier = row.get(1); int offset = Integer.parseInt(row.get(2)), count = Integer.parseInt(row.get(3)); long seed = Long.parseLong(row.get(4));
        byte[] bytes = checksumBytes(count + offset);
        var storage = PinnedMemory.allocate(carrier.equals("AddrRep") ? bytes.length : count, 8);
        int start = carrier.equals("AddrRep") ? 0 : offset;
        for (int i = start; i < bytes.length; i++) storage.writeByte(i - start, bytes[i]);
        var segment = storage.nativeSegment();
        Object pointer = carrier.equals("AddrRep") ? ManagedAddress.fromGuestByteArray(storage).plus(offset) : ManagedByteArray.freezeGuest(storage);
        PackageScalarSignature signature = null;
        for (var abi : link.getAbi()) if (abi.getSymbol().equals(symbol) && abi.getArguments().contains(carrier)) { assertNull(signature); signature = abi; }
        assertNotNull(signature);
        Object[] arguments = switch (symbol) {
            case "adler32", "crc32" -> new Object[] {seed, pointer, count};
            case "crc32c_extend" -> new Object[] {(int) seed, pointer, (long) count};
            case "crc32c_value" -> new Object[] {pointer, (long) count};
            default -> throw new AssertionError("Unexpected original digest symbol");
        };
        assertEquals(Long.parseLong(row.get(5)), entries.get(signature).call(arguments), row.toString());
        assertSame(segment, storage.nativeSegment(), "native checksums retain the allocation created for the guest");
        for (int i = start; i < bytes.length; i++) assertEquals(Byte.toUnsignedLong(bytes[i]), storage.readByte(i - start), "checksums preserve their input");
    }
    @Test public void originalDigestCxxAndZlibMatchNativeAcrossOffsetsAndLoopBoundaries() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(1L, manifest.get("schema"));
        assertEquals("original-package-foreign-adapters", manifest.get("scope")); assertEquals(270L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("sourceHashes"), Set.of(
            "build/original-native/sources/digest-0.0.2.1/digest.cabal", "build/original-native/sources/digest-0.0.2.1/Data/Digest/CRC32C.hs",
            "build/original-native/sources/digest-0.0.2.1/external/crc32c/src/crc32c.cc", "build/original-native/sources/digest-0.0.2.1/external/crc32c/src/crc32c_portable.cc"), "build/original-native/sources/");
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalDigestNative.hs",
            "t/haskell-fixtures/PackageNativeOriginalsFixtures.hs", "src/driver/THC/Driver/PackageNative.hs", "src/driver/THC/Driver/NativeLibrarySources.hs"), null);
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), Set.of("build/original-native/digest-native.tsv",
            "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.Adler32.cbd", "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.CRC32.cbd",
            "build/original-native/linked/digest-0.0.2.1-inplace/Data.Digest.CRC32C.cbd"), "build/original-native/");
        var modules = modules(moduleFiles("linked/digest-0.0.2.1-inplace")); var moduleNames = new LinkedHashSet<Object>();
        for (var module : modules) moduleNames.add(module.get("module")); assertEquals(Set.of("Data.Digest.Adler32", "Data.Digest.CRC32", "Data.Digest.CRC32C"), moduleNames);
        var link = link(CoreModules.merge(modules)); assertEquals(6, link.getAbi().size()); var rows = rows("digest-native.tsv"); assertEquals(270, rows.size());
        var profile = (Map<?, ?>) modules.getFirst().get("packageNativeLink"); var inputs = (Map<?, ?>) profile.get("buildInputs");
        assertEquals(Set.of("adler32", "crc32"), new LinkedHashSet<>((List<?>) inputs.get("unresolved")));
        assertEquals(List.of(), inputs.get("providers")); assertNotNull(profile.get("nativeLibrary"));
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var entries = new LinkedHashMap<PackageScalarSignature, RootCallTarget>(); for (var abi : link.getAbi()) entries.put(abi, new Entry(language, new PackageScalarCall(link, abi)).getCallTarget());
                for (var row : rows) checkDigest(row, link, entries);
                for (var target : entries.values()) compile(target); for (var target : entries.values()) valid(target, "installed");
                for (var row : rows) { checkDigest(row, link, entries); for (var target : entries.values()) valid(target, "each first installed call and subsequent comparison retains code"); }
            } finally { context.leave(); }
        }
    }
    private void checkPrimitive(List<String> row, List<PackageScalarSignature> setters, Map<PackageScalarSignature, RootCallTarget> entries) {
        String rep = row.getFirst(), carrier = row.get(1); byte[] expected = HexFormat.of().parseHex(row.get(5)), bytes = new byte[expected.length]; Arrays.fill(bytes, (byte) 0xa5);
        Object pointer = carrier.equals("AddrRep") ? ManagedAddress.fromByteArray(bytes) : bytes;
        PackageScalarSignature signature = null;
        for (var abi : setters) if (abi.getArguments().getFirst().equals(carrier) && abi.getArguments().getLast().equals(rep)) { assertNull(signature); signature = abi; }
        assertNotNull(signature); long value = new BigInteger(row.get(4)).longValue(); Object argument;
        if (Set.of("IntRep", "WordRep", "Int64Rep", "Word64Rep").contains(rep)) argument = value;
        else argument = PackageScalarAccess.packageCInteger(rep, (int) value);
        entries.get(signature).call(pointer, Long.parseLong(row.get(2)), Long.parseLong(row.get(3)), argument);
        assertArrayEquals(expected, bytes, row.subList(0, 5).toString());
    }
    @Test public void originalPrimitiveSignednessAdaptersMatchNativeWithoutCopyingManagedStorage() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(720L, manifest.get("primitiveNativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("sourceHashes"), Set.of("build/original-native/sources/primitive-0.9.1.0/primitive.cabal",
            "build/original-native/sources/primitive-0.9.1.0/Data/Primitive/Internal/Operations.hs", "build/original-native/sources/primitive-0.9.1.0/cbits/primitive-memops.c",
            "build/original-native/sources/primitive-0.9.1.0/cbits/primitive-memops.h"), "build/original-native/sources/");
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalPrimitiveNative.hs",
            "t/haskell-fixtures/PackageNativeOriginalsFixtures.hs", "src/driver/THC/Driver/PackageNative.hs"), null);
        var files = moduleFiles("linked/primitive-0.9.1.0-inplace"); assertEquals(14, files.size()); var artifacts = moduleArtifacts(files); artifacts.add("build/original-native/primitive-native.tsv");
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), artifacts, "build/original-native/");
        var link = link(CoreModules.merge(modules(files))); var setters = new ArrayList<PackageScalarSignature>(); var reps = new LinkedHashSet<String>();
        for (var abi : link.getAbi()) if (abi.getSymbol().startsWith("hsprimitive_memset_Word")) { setters.add(abi); reps.add(abi.getArguments().getLast()); }
        assertEquals(20, setters.size()); assertEquals(10, reps.size()); var rows = rows("primitive-native.tsv"); assertEquals(720, rows.size());
        try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); Language.currentState().getPackageCbits().link(link);
                var entries = new LinkedHashMap<PackageScalarSignature, RootCallTarget>(); for (var abi : setters) entries.put(abi, new Setter(language, new PackageScalarCall(link, abi)).getCallTarget());
                for (var row : rows) checkPrimitive(row, setters, entries);
                for (var target : entries.values()) { compile(target); valid(target, "installed"); }
                for (var row : rows.reversed()) { checkPrimitive(row, setters, entries); for (var target : entries.values()) valid(target, "original primitive first-installed adapter retains code"); }
            } finally { context.leave(); }
        }
    }
    private record Case(String name, long input, long expected) {}
    private void checkCase(String backend, Case value, ExecutableProgram program, Language language) {
        assertEquals(value.expected(), OriginalStdioChecks.invoke(program, value.name(), value.input()), backend + "/" + value); released(language);
    }
    @Test public void originalPrimitivePublicSettersRunThroughBothFirstInstalledBackends() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); var files = moduleFiles("linked/primitive-0.9.1.0-inplace");
        String entryPath = "primitive-entry/units/u-original-primitive-entry/OriginalPrimitiveEntry.cbd";
        OriginalStdioChecks.hashes(root, manifest.get("sourceHashes"), Set.of("build/original-native/sources/primitive-0.9.1.0/Data/Primitive/ByteArray.hs",
            "build/original-native/sources/primitive-0.9.1.0/Data/Primitive/Internal/Operations.hs"), "build/original-native/sources/");
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalPrimitiveEntry.hs", "t/fixtures/compiler/OriginalPrimitiveNative.hs",
            "t/haskell-fixtures/PackageNativeOriginalsFixtures.hs", "src/driver/THC/Driver/PackageNative.hs"), null);
        var artifacts = moduleArtifacts(files); artifacts.addAll(artifacts(List.of(entryPath, "primitive-audit.json", "primitive-native.tsv")));
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), artifacts, "build/original-native/");
        var modules = modules(files); modules.add(OriginalStdioChecks.module(new File(directory, entryPath))); assertEquals(true, json(new File(directory, "primitive-audit.json")).get("accepted"));
        var names = new LinkedHashMap<String, String>(); names.put("Int16Rep", "signed16"); names.put("Word16Rep", "unsigned16"); names.put("Int64Rep", "signed64");
        names.replaceAll((key, value) -> "original-primitive-entry:OriginalPrimitiveEntry." + value);
        long[] inputs = {0L, 1L, -1L, -128L, 128L, 0x123456789abcdef0L}; var groups = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (var row : rows("primitive-native.tsv")) if (names.containsKey(row.getFirst()) && row.get(1).equals("MutableByteArray#") && row.get(2).equals("2") && row.get(3).equals("7")) {
            groups.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(18, count); var cases = new ArrayList<Case>();
        for (var group : groups.entrySet()) for (int i = 0; i < group.getValue().size(); i++) {
            var observed = ByteBuffer.wrap(HexFormat.of().parseHex(group.getValue().get(i).get(5))).order(ByteOrder.nativeOrder());
            long expected = switch (group.getKey()) {
                case "Int16Rep" -> observed.getShort(3 * 2); case "Word16Rep" -> observed.getShort(3 * 2) & 0xffffL;
                case "Int64Rep" -> observed.getLong(3 * 8); default -> throw new AssertionError("Unexpected original primitive native carrier");
            };
            cases.add(new Case(names.get(group.getKey()), inputs[i], expected));
        }
        var merged = CoreModules.merge(modules); var link = link(merged);
        for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState(); owner.getPackageCbits().link(link);
                var source = new LinkedHashMap<>(CoreModules.reachable(merged, new ArrayList<>(names.values()), true)); source.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, source, true) : new BytecodeProgram(language, source, true);
                var entries = new LinkedHashMap<String, RootCallTarget>(); for (var name : names.values()) entries.put(name, program.entryTarget(name));
                owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    for (var value : cases) checkCase(backend, value, program, language);
                    var installed = installed(entries.values()); for (var target : installed) { compile(target); valid(target, "installed"); }
                    for (var value : cases.reversed()) {
                        long before = compiled(program); checkCase(backend, value, program, language); assertTrue(compiled(program) > before);
                        for (var target : installed) valid(target, "first-installed");
                    }
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { context.leave(); }
        }
    }
}
