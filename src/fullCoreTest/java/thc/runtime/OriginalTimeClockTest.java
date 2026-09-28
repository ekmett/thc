// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class OriginalTimeClockTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-time-clock";
    private Map<String, Object> json(String name) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/" + name + ".json").toPath())); }
    private Context context(boolean nativeAccess) { return Context.newBuilder("thc").allowNativeAccess(nativeAccess).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void inside(boolean nativeAccess, Action action) throws Exception { try (var context = context(nativeAccess)) { context.initialize("thc"); context.enter(); try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); } } }
    private Map<String, Object> source(String stage) throws Exception { return CoreModules.merge(List.of(json("linked"), json(stage))); }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> source) { var selected = with(source, "instrument", true); return backend.equals("ast") ? new Program(language, selected) : new BytecodeProgram(language, selected); }
    private ForeignBitcode link() throws Exception { return Objects.requireNonNull(CoreForeignArtifacts.linked(json("linked"))); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); }
    private List<Long> bytes(ManagedAddress address) { var result = new ArrayList<Long>(); for (long i = 0; i <= 31; i++) result.add(address.readWord8(i)); return result; }
    private record Fields(long seconds, long nanos) {}
    private Fields fields(ManagedAddress address) { var values = bytes(address); var bytes = new byte[values.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue(); var view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()); return new Fields(view.getLong(8), view.getLong(16)); }
    private ManagedAddress allocation(int kind) { var result = switch (kind) { case 0 -> ManagedAddress.fromByteArray(new byte[32]); case 3 -> Language.currentState(null).getNativeAllocations().malloc(32); default -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8, kind == 2)); }; for (long i = 0; i <= 31; i++) result.writeWord8(i, 0x5a); return result; }
    private void release(ManagedAddress address, int kind) { if (kind == 3) Language.currentState(null).getNativeAllocations().free(address); }
    private void exercise(boolean compiled, String stage, String backend, String entry, ExecutableProgram p, RootCallTarget target, long executedRoots, Map<String, Object> oracle, List<Map<String, Object>> rows) throws Exception {
        var state = Language.currentState(null);
        if (entry.equals("originalConstant")) { long before = (Long) p.diagnostics().get("compiledEntries"); assertEquals(oracle.get("realtime"), Calls.target(target, new Object[] {0L, 0L})); if (compiled) { assertEquals(before + executedRoots, p.diagnostics().get("compiledEntries")); valid(target); } }
        else for (var row : rows) if (Objects.equals(row.get("entry"), entry)) for (int kind = 0; kind <= 3; kind++) {
            var base = allocation(kind);
            try {
                state.getStdio().captureForeignErrno((Long) row.get("seedErrno")); long before = (Long) p.diagnostics().get("compiledEntries"); var earliest = Instant.now();
                var result = Calls.target(target, new Object[] {0L, row.get("clock"), Boolean.TRUE.equals(row.get("null")) ? ManagedAddress.nullAddress() : base.plus(8)}); var latest = Instant.now();
                assertEquals(row.get("status"), result, stage + "/" + backend + "/" + entry + "/" + kind + "/" + row); assertEquals(row.get("errno"), state.getStdio().errno());
                if (Objects.equals(result, 0L) && Boolean.FALSE.equals(row.get("null"))) {
                    var fields = fields(base); assertTrue(fields.seconds >= 0 && fields.nanos >= 0 && fields.nanos <= 999999999);
                    if (entry.equals("originalResolution")) { assertEquals(row.get("seconds"), fields.seconds); assertEquals(row.get("nanos"), fields.nanos); }
                    else { // Realtime may jump: accept either ordering of the enclosing samples.
                        var actual = Instant.ofEpochSecond(fields.seconds, fields.nanos); var min = earliest.compareTo(latest) <= 0 ? earliest : latest; var max = earliest.compareTo(latest) >= 0 ? earliest : latest; assertTrue(actual.compareTo(min) >= 0 && actual.compareTo(max) <= 0);
                    }
                    assertEquals(Collections.nCopies(8, 0x5aL), bytes(base).subList(0, 8)); assertEquals(Collections.nCopies(8, 0x5aL), bytes(base).subList(24, 32));
                } else assertEquals(Collections.nCopies(32, 0x5aL), bytes(base), "failure never publishes a staged image");
                if (compiled) { assertEquals(before + executedRoots, p.diagnostics().get("compiledEntries")); valid(target); }
            } finally { release(base, kind); }
        }
    }
    @Test public void genuineNativeResultsGuardsErrnoAndFirstCompiledEntries() throws Exception {
        var manifest = json("manifest"); assertEquals(9L, manifest.get("nativeRows"));
        for (var key : List.of("inputHashes", "artifactHashes", "interfaceHashes")) for (var e : ((Map<String, String>) manifest.get(key)).entrySet()) { var file = new File(e.getKey()); if (!file.isAbsolute()) file = new File(root, e.getKey()); assertEquals(e.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), e.getKey()); }
        var oracle = json("oracle"); var rows = (List<Map<String, Object>>) oracle.get("rows");
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) inside(true, language -> {
            Language.currentState(null).cbits().link(link());
            for (var entry : List.of("originalConstant", "originalResolution", "originalTime")) {
                assertEquals(true, json(stage + "-" + entry + ".audit").get("accepted")); var selected = CoreModules.reachable(source(stage), entry); var evidence = new ArrayCoreEvidence(selected, entry); assertEquals(1, evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size());
                var names = new LinkedHashSet<Object>(); long executedRoots = 0; Map<String, Object> worker = null;
                for (var binding : evidence.getBindings()) { names.add(binding.get("name")); executedRoots += evidence.loweredGuestLambdas(binding.get("expr")).size(); if (Objects.equals(binding.get("name"), "$woriginalConstant")) { assertNull(worker); worker = binding; } }
                assertEquals(entry.equals("originalConstant") ? Set.of(entry, "$woriginalConstant") : Set.of(entry), names); assertEquals(entry.equals("originalConstant") ? 2L : 1L, executedRoots);
                if (entry.equals("originalConstant")) assertEquals(List.of(Objects.requireNonNull(worker).get("id")), evidence.globalReferences(evidence.getRoot().get("expr")));
                var p = program(language, backend, selected); var target = p.entryTarget(entry); exercise(false, stage, backend, entry, p, target, executedRoots, oracle, rows); compile(target); exercise(true, stage, backend, entry, p, target, executedRoots, oracle, rows);
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            }
        });
    }
    private String symbol(ForeignBitcode bitcode, String kind, boolean single) { String result = null; for (var e : bitcode.getAbi().entrySet()) if (e.getValue().equals(kind)) { if (!single) return e.getKey(); if (result != null) throw new IllegalArgumentException("Multiple matching ABI entries"); result = e.getKey(); } return Objects.requireNonNull(result); }
    @Test public void baseAndTimeLibrariesCoexistWithoutSharingAbiOrLosingErrno() throws Exception { inside(true, language -> {
        var cbits = Language.currentState(null).cbits(); var time = link(); var base = Objects.requireNonNull(CoreForeignArtifacts.linked(json("base-linked"))); for (var record : List.of(base, time, base, time)) cbits.link(record);
        long cpu = cbits.capiZero(base.getUnit(), symbol(base, "clock-id", true), false); var address = allocation(0); assertEquals(0L, cbits.capiWordAddress(base.getUnit(), symbol(base, "clock-buffer", false), cpu, address.plus(8)).getValue());
        long realtime = cbits.capiZero(time.getUnit(), symbol(time, "time-clock-id", true), true); assertEquals(json("oracle").get("realtime"), realtime); var call = new CapiCall(time.getUnit(), symbol(time, "time-clock-resolution", true), false, true, true);
        assertEquals(0L, cbits.capiWordAddress(call, realtime, ManagedAddress.nullAddress()).getValue()); var failure = cbits.capiWordAddress(call, -1, address.plus(8)); assertEquals(-1L, failure.getValue()); Map<String, Object> expected = null;
        for (var row : (List<Map<String, Object>>) json("oracle").get("rows")) if (Objects.equals(row.get("entry"), "originalResolution") && Objects.equals(row.get("clock"), -1L)) { expected = row; break; } assertEquals(Objects.requireNonNull(expected).get("errno"), failure.getErrno()); assertEquals(cpu, cbits.capiZero(base.getUnit(), symbol(base, "clock-id", true), false));
    }); }
    private Object invoke(RootCallTarget target, long clock, ManagedAddress address, Object state) { return Calls.target(target, new Object[] {0L, clock, address, state}); }
    @Test public void checkedOutputsStateAndDynamicClockIdsFailBeforeObservation() throws Exception {
        for (var backend : List.of("ast", "bytecode")) inside(true, language -> {
            Language.currentState(null).cbits().link(link()); var source = source("pre"); var originals = OriginalStdioChecks.foreignCalls(CoreModules.reachable(source, "originalTime")); assertEquals(1, originals.size()); var original = originals.getFirst();
            var raw = with(OriginalStdioChecks.rawModule(original, source, null), "foreignLinks", source.get("foreignLinks")); var target = program(language, backend, raw).entryTarget("entry"); long realtime = (Long) json("oracle").get("realtime");
            for (var bad : List.of(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(0x1000), ManagedAddress.fromHex("00".repeat(16)), ManagedAddress.fromByteArray(new byte[15]))) assertThrows(RuntimeFault.class, () -> invoke(target, realtime, bad, kotlin.Unit.INSTANCE));
            var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8, false)); var pointer = ManagedAddress.fromByteArray(new byte[] {42}); cells.writeAddressElementIndex(1, pointer); assertThrows(RuntimeFault.class, () -> invoke(target, realtime, cells.plus(8), kotlin.Unit.INSTANCE)); assertSame(pointer, cells.readAddressElementIndex(1)); var good = allocation(0);
            for (long bad : new long[] {-2L, -8L, Integer.MIN_VALUE, 0x100000000L}) { assertThrows(RuntimeFault.class, () -> invoke(target, bad, good.plus(8), kotlin.Unit.INSTANCE)); assertEquals(Collections.nCopies(32, 0x5aL), bytes(good)); }
            assertThrows(RuntimeFault.class, () -> invoke(target, realtime, good.plus(8), 17L)); assertEquals(Collections.nCopies(32, 0x5aL), bytes(good)); var nativeAddress = allocation(3); Language.currentState(null).getNativeAllocations().free(nativeAddress); assertThrows(RuntimeFault.class, () -> invoke(target, realtime, nativeAddress, kotlin.Unit.INSTANCE)); var other = allocation(3);
            try { inside(true, second -> { Language.currentState(null).cbits().link(link()); var otherTarget = program(second, backend, raw).entryTarget("entry"); assertThrows(RuntimeFault.class, () -> invoke(otherTarget, realtime, other, kotlin.Unit.INSTANCE)); }); assertEquals(Collections.nCopies(32, 0x5aL), bytes(other)); } finally { release(other, 3); }
            for (int position : new int[] {0, 1, 2}) { var contradiction = with(OriginalStdioChecks.rawModule(original, source, position), "foreignLinks", source.get("foreignLinks")); assertThrows(RuntimeFault.class, () -> program(language, backend, contradiction)); }
        });
        inside(false, language -> assertThrows(RuntimeFault.class, () -> Language.currentState(null).cbits().link(link())));
    }
    @Test public void originalOwnerHeadersAndExactCapiIndicesRejectMutations() throws Exception {
        var original = json("linked"); var metadata = (Map<String, Object>) original.get("foreignLink"); var missing = new LinkedHashMap<>(metadata); missing.remove("headerHashes");
        var abi = new ArrayList<Map<String, Object>>(); var reversed = ((List<Map<String, Object>>) metadata.get("abi")).reversed(); var kinds = List.of("time-clock-id", "time-clock-resolution", "time-clock-time"); for (int i = 0; i < reversed.size(); i++) abi.add(with(reversed.get(i), "kind", kinds.get(i)));
        for (var change : List.of(with(metadata, "schema", 2L), missing, with(metadata, "headerHashes", List.of()), with(metadata, "unit", "time-1.16-inplace"), with(metadata, "abi", abi))) assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.linked(with(original, "foreignLink", change)));
        var source = source("pre");
        for (var call : OriginalStdioChecks.foreignCalls(CoreModules.reachable(source, "originalTime"))) {
            var meta = (Map<String, Object>) call.get(6); var descriptor = (Map<String, Object>) meta.get("foreignCall"); var operands = new ArrayList<Object>(); for (var operand : (List<List<Object>>) call.get(2)) { var rep = CoreRepresentations.metadata(operand); operands.add(rep == null ? null : rep.get("rep")); } var links = List.of(link());
            assertNotNull(CoreCapiForeign.validate(meta, operands, (List<?>) call.get(3), meta.get("rep"), links)); assertThrows(RuntimeFault.class, () -> CoreCapiForeign.validate(with(meta, "foreignCall", with(descriptor, "safety", "safe")), operands, (List<?>) call.get(3), meta.get("rep"), links));
            CoreCapiForeign.validateHead((List<Object>) call.get(1), false); assertThrows(RuntimeFault.class, () -> CoreCapiForeign.validateHead((List<Object>) call.get(1), true));
            var admitted = Objects.requireNonNull(CoreCapiForeign.validate(meta, operands, (List<?>) call.get(3), meta.get("rep"), links)); var state = new CoreRepresentation(CoreKind.VOID, false, true, List.of(), null, null, null, null, null); var number = new CoreRepresentation(CoreKind.LONG, false, true, List.of("IntRep"), null, null, null, null, null);
            assertThrows(RuntimeFault.class, () -> CoreCapiForeign.validateOperand(admitted, 2, number, null)); assertThrows(RuntimeFault.class, () -> CoreCapiForeign.validateOperand(admitted, 2, state, number));
        }
    }
    @Test public void firstClassForeignIdRemainsAnExplicitRejectedShapeWithItsWorkerRetained() throws Exception {
        var closed = json("specialized-closed"); var names = new ArrayList<Object>(); for (var binding : (List<Map<String, Object>>) closed.get("bindings")) names.add(binding.get("name")); assertTrue(names.contains("$woriginalId"), "ordinary -O2 ignored-argument worker must survive specialization");
        for (var stage : List.of("pre", "post")) { var audit = json(stage + "-originalId.audit"); assertEquals(false, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); var missing = (List<?>) audit.get("missingGlobals"); assertEquals(1, missing.size()); assertTrue(missing.getFirst().toString().contains("HSzuCLOCKzuREALTIME")); assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(source(stage), "originalId", true)); }
    }
}
