// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

@SuppressWarnings("unchecked")
class OriginalMemsetTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-memset";
    private final boolean nativeSupported = System.getProperty("os.name").equals("Linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch"));
    private Object json(String path) throws Exception { return Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalMemsetAudit","THC.InterfaceClosure")) modules.add((Map<String,Object>) json(prefix + "/" + stage + "/core/" + part + ".json")); return CoreModules.merge(modules); }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000").build(); }
    private <T> T inside(Callable<T> body) throws Exception { try (var context = context()) { context.initialize("thc"); context.enter(); try { return body.call(); } finally { context.leave(); } } }
    private ExecutableProgram load(Language language,String backend,Map<String,Object> source) throws Exception { source = ForeignExceptionFixtureSupport.nativeModules(List.of(source)); return backend.equals("ast") ? new Program(language,source) : new BytecodeProgram(language,source); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode",type).invoke(runtime,target); }
    private List<Map<String,Object>> rows() throws Exception {
        var manifest = (Map<String,Object>) json(prefix + "/manifest.json"); assertEquals(true,manifest.get("strictAccepted")); assertEquals(198L,manifest.get("nativeRows"));
        hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalMemsetAudit.hs","t/fixtures/compiler/OriginalMemsetNative.hs","t/haskell-fixtures/MemsetFixtures.hs","bin/core_original_foreign.py"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) for (var part : List.of("OriginalMemsetAudit","THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + part + ".json"); hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        for (var stage : List.of("pre","post")) { var audit = (Map<String,Object>) json(prefix + "/" + stage + "/originalFill.audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); }
        var rows = (List<Map<String,Object>>) json(prefix + "/oracle.json"); assertEquals(198,rows.size()); return rows;
    }
    private List<Long> bytes(ManagedAddress address,int size) { var values = new ArrayList<Long>(); for (int i = 0; i < size; i++) values.add(address.readWord8(i)); return values; }
    private void returnedAlias(ManagedAddress original, Object result) {
        var returned = assertInstanceOf(ManagedAddress.class, result);
        assertTrue(original.sameLocation(returned));
        var backing = Objects.requireNonNull(returned.returnedAddress().getBacking());
        assertTrue(original.sameLocation(backing));
        if (original.nativeAllocation() != null) assertSame(original.nativeAllocation(), backing.nativeAllocation());
        else assertSame(original.cbitsStorageKey(), backing.cbitsStorageKey());
    }
    private ManagedAddress address(List<Long> values,int kind) {
        ManagedAddress address;
        if (kind == 0) { byte[] bytes = new byte[values.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue(); address = ManagedAddress.fromByteArray(bytes); }
        else if (kind == 3) address = Language.currentState().getNativeAllocations().malloc(values.size()); else address = ManagedAddress.fromAllocation(ManagedAllocation.mutable(values.size(),8,kind == 2));
        for (int i = 0; i < values.size(); i++) address.writeWord8(i,values.get(i)); return address;
    }
    @Test void originalCorpusAndExportProvenanceRemainExact() throws Exception { rows(); }
    @Test @Tag("foreign-exceptions-full-core") void originalWrapperAndRawCIntCallMatchNativeOnFirstCompiledEntries() throws Exception {
        var rows = rows();
        for (var stage : List.of("pre","post")) {
            var source = module(stage); var original = single(foreignCalls(source),ignored -> true);
            var descriptor = (Map<?, ?>) ((Map<?, ?>) original.get(6)).get("foreignCall");
            assertEquals("memset", ((Map<?, ?>) descriptor.get("target")).get("symbol"));
            var evidence = new ArrayCoreEvidence(source,"originalFill"); var lambda = (List<Object>) evidence.getRoot().get("expr"); evidence.immediateStateLambda(lambda.get(2)); assertEquals(1,evidence.getBindings().size()); assertEquals(2,evidence.guestLambdas(lambda).size()); int executedRoots = evidence.loweredGuestLambdas(lambda).size(); assertEquals(1,executedRoots,"exact immediate State# application is in-frame");
            for (var backend : List.of("ast","bytecode")) inside(() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean raw : new boolean[]{false,true}) {
                    var selected = raw ? rawModule(original,source) : CoreModules.reachable(source,"originalFill"); var program = load(language,backend,with(selected,"instrument",true)); var target = program.entryTarget(raw ? "entry" : "originalFill");
                    class Exercise { void run(boolean compiled) throws Exception {
                        for (int kind = 0; kind <= (nativeSupported ? 3 : 2); kind++) for (int index = 0; index < rows.size(); index++) {
                            var row = rows.get(index); var values = (List<Long>) row.get("before"); var base = address(values,kind);
                            try {
                                var destination = base.plus((Long) row.get("offset")); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                // Raw calls retain the genuine descriptor and cover the entire CInt corpus.
                                Object fill = raw ? Integer.valueOf(((Long) row.get("value")).intValue()) : row.get("value"); Object[] args = {0L,destination,fill,row.get("count")}; Object result;
                                if (raw) { var packet = Arrays.copyOf(args,args.length + 1); packet[args.length] = thc.runtime.Unit.INSTANCE; result = callScalarTestTarget(target,packet); } else result = callScalarTestTarget(target,args);
                                var label = stage + "/" + backend + "/raw=" + raw + "/storage=" + kind + "/" + index + "/compiled=" + compiled; returnedAlias(destination,result); assertEquals(row.get("returned"),((ManagedAddress) result).difference(base),label); assertEquals(row.get("after"),bytes(base,values.size()),label);
                                if (compiled) { assertEquals(before + executedRoots,((Number) program.diagnostics().get("compiledEntries")).longValue(),label); valid(target); }
                                var state = language.getHandoffState().get(); assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getResults().getDepth()); assertEquals(0,state.getResults().retainedReferences());
                            } finally { if (kind == 3) Language.currentState().getNativeAllocations().free(base); }
                        }
                    }}
                    var exercise = new Exercise(); exercise.run(false); compile(target); exercise.run(true);
                }
                return null;
            });
        }
    }
    @Test @Tag("foreign-exceptions-full-core") void ownershipAndPointerCellReconciliationUseTheActualForeignLeaf() throws Exception {
        var source = module("pre"); var raw = rawModule(single(foreignCalls(source),ignored -> true),source);
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = load(language,backend,raw).entryTarget("entry");
            class Fill { Object call(ManagedAddress address,int value,long count) { return call(address,value,count,thc.runtime.Unit.INSTANCE); } Object call(ManagedAddress address,int value,long count,Object state) { return callScalarTestTarget(target,new Object[]{0L,address,value,count,state}); }}
            var fill = new Fill();
            for (int kind = 0; kind <= (nativeSupported ? 3 : 2); kind++) {
                var values = new ArrayList<Long>(); for (long i = 0; i < 16; i++) values.add(i); var base = address(values,kind);
                try {
                    assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,base,0L,1L,17L})); assertThrows(RuntimeFault.class,() -> fill.call(base,0,1,17L)); assertEquals(values,bytes(base,16)); var end = base.plus(16); returnedAlias(end,fill.call(end,-1,0)); assertEquals(values,bytes(base,16));
                } finally { if (kind == 3) Language.currentState().getNativeAllocations().free(base); }
            }
            assertThrows(RuntimeFault.class,() -> fill.call(ManagedAddress.unownedNumeric(0x1000),0,1));
            var moving = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24,8)); var opaque = ManagedAddress.fromByteArray(new byte[]{9});
            for (long i = 0; i <= 2; i++) moving.writeAddressElementIndex(i,opaque);
            assertThrows(RuntimeFault.class,() -> fill.call(moving.plus(8),255,8));
            for (long i = 0; i <= 2; i++) assertSame(opaque,moving.readAddressElementIndex(i),"rejection precedes native writes");
            var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24,8,true));
            var referent = ManagedAddress.fromAllocation(ManagedAllocation.mutable(1,8,true)); referent.writeWord8(0,9);
            for (long i = 0; i <= 2; i++) cells.writeAddressElementIndex(i,referent);
            fill.call(cells.plus(8),255,8); assertEquals(-1L,cells.readAddressElementIndex(1).toNativeBits()); assertSame(referent,cells.readAddressElementIndex(2));
            fill.call(cells.plus(16),0,8); assertSame(ManagedAddress.nullAddress(),cells.readAddressElementIndex(2)); assertSame(referent,cells.readAddressElementIndex(0)); assertEquals(9L,referent.readWord8(0));
            if (nativeSupported) {
                var owned = Language.currentState().getNativeAllocations().malloc(8);
                inside(() -> { var other = TruffleLanguage.LanguageReference.create(Language.class).get(null); var foreignTarget = load(other,backend,raw).entryTarget("entry"); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(foreignTarget,new Object[]{0L,owned,0,1L,thc.runtime.Unit.INSTANCE})); return null; });
                Language.currentState().getNativeAllocations().free(owned); assertThrows(RuntimeFault.class,() -> fill.call(owned,0,0));
            }
            return null;
        });
    }
    @Test @Tag("foreign-exceptions-full-core") void exactOriginalDescriptorAndLexicalOwnershipAreRequired() throws Exception {
        var source = module("pre"); var original = single(foreignCalls(source),ignored -> true); var raw = rawModule(original,source);
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (int variant : new int[]{2, 6, 7, 8, 9, 10, 11, 12, 13, 14}) {
                var bad = (Map<String,Object>) Json.parse(Json.stringify(raw)); var call = single(foreignCalls(bad),ignored -> true); var descriptor = (Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"); var target = (Map<String,Object>) descriptor.get("target");
                switch (variant) { case 2 -> descriptor.put("arity",3L);
                    case 6 -> ((List<Object>) call.get(3)).set(0,true); case 7 -> ((List<Object>) call.get(1)).set(1,"entry"); case 8 -> ((List<Object>) descriptor.get("argumentReps")).set(2,OriginalStdioFixtures.scalar("WordRep",false)); case 9 -> ((List<Object>) descriptor.get("argumentReps")).set(1,OriginalStdioFixtures.scalar("IntRep",false));
                    case 10 -> descriptor.put("suppliedArity",3L); case 11 -> target.put("isFunction",false); case 12 -> ((List<Object>) call.get(1)).set(1,((List<List<Object>>) call.get(2)).get(0).get(1)); case 13 -> ((Map<String,Object>) descriptor.get("resultRep")).put("primReps",list("IntRep")); case 14 -> descriptor.put("schema",true); }
                assertThrows(RuntimeFault.class,() -> load(language,backend,bad),backend + "/" + variant);
            }
            return null;
        });
    }
    @ParameterizedTest @Tag("foreign-exceptions-full-core")
    @ValueSource(strings = {"ast","bytecode"})
    void lexicalJoinsCannotClaimOriginalMemsetAuthority(String backend) throws Exception {
        for (var stage : List.of("pre","post")) {
            var source = module(stage);
            inside(() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var original : foreignCalls(source)) {
                    class Shadow { Map<String,Object> module(boolean claimForeign) {
                        var raw = (Map<String,Object>) Json.parse(Json.stringify(rawModule(original,source))); var entry = single((List<Map<String,Object>>) raw.get("bindings"),ignored -> true); var lambda = (List<Object>) entry.get("expr"); var body = (List<Object>) lambda.get(2); var call = (List<Object>) body.get(1);
                        var metadata = (Map<String,Object>) call.get(6); var tuple = (Map<String,Object>) metadata.get("rep"); var fields = (List<Map<String,Object>>) tuple.get("components"); var operands = (List<List<Object>>) call.get(2); var closure = OriginalStdioFixtures.closure(); var formals = new ArrayList<Map<String,Object>>();
                        for (int i = 0; i < operands.size(); i++) formals.add(map("id","join" + i,"lifted",false,"rep",Objects.requireNonNull(CoreRepresentations.metadata(operands.get(i))).get("rep")));
                        var value = list("var","join0",map("rep",fields.get(1))); var pair = list("app",list("con","T2",2,map("rep",closure)),list(list("var","join3",map("rep",fields.get(0))),value),list(false,false),false,false,map("rep",with(tuple,"evaluated",true)));
                        var join = map("id",((List<?>) call.get(1)).get(1),"name","shadowedMemset","lifted",true,"rep",closure,"expr",list("lam",formals,pair,map("rep",closure,"resultRep",tuple)),"joinValueArity",4L,"joinResultRep",tuple,"info",map("joinArity",4L));
                        if (!claimForeign) call.set(6,without(metadata,"foreignCall")); CoreJoins.validate(List.of(join),call,false); body.set(1,list("let",false,list(join),call,map("rep",tuple))); return raw;
                    }}
                    var shadow = new Shadow(); var bytes = ManagedAddress.fromByteArray(new byte[]{1,2}); int second = 9; var target = load(language,backend,shadow.module(false)).entryTarget("entry"); var result = callScalarTestTarget(target,new Object[]{0L,bytes,second,1L,thc.runtime.Unit.INSTANCE}); assertSame(bytes,result);
                    var failure = assertThrows(RuntimeFault.class,() -> load(language,backend,shadow.module(true))); assertTrue(Objects.toString(failure.getMessage(),"").contains("unresolved declared foreign head"),stage + "/" + backend + " rejects the shadowed head specifically: " + failure.getMessage());
                }
                return null;
            });
        }
    }
}
