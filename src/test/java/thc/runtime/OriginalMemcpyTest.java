// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Retained original declarations executed by explicitly synthetic callers. */
@SuppressWarnings("unchecked")
class OriginalMemcpyTest {
    private final Map<String,Object> closure = map("kind","closure","primReps",list("BoxedRep (Just Lifted)"),"evaluated",true);
    private final Map<String,Object> address = map("kind","address","primReps",list("AddrRep"),"evaluated",true);
    private final Map<String,Object> longRep = map("kind","long","primReps",list("IntRep"),"evaluated",true);
    private String resource(String path) throws Exception { try (var input = getClass().getResourceAsStream(path)) { return new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8); } }
    private Map<String,Object> descriptor() throws Exception { return (Map<String,Object>) Json.parse(resource("/core/original-memcpy-descriptor.json")); }
    private Map<String,Object> ramDescriptor() throws Exception { return (Map<String,Object>) Json.parse(resource("/core/original-ram-memcpy-descriptor.json")); }
    private Map<String,Object> module() throws Exception { return module(descriptor()); }
    private Map<String,Object> module(Map<String,Object> declaration) throws Exception { return module(declaration,Map.of(),false,descriptor(),UnaryOperator.identity()); }
    private Map<String,Object> module(Map<String,Object> declaration,Map<Integer,Map<String,Object>> stored,boolean shadowForeignWithJoin,Map<String,Object> canonical,UnaryOperator<List<Object>> changeCall) {
        var tuple = (Map<String,Object>) canonical.get("resultRep"); var fields = (List<Map<String,Object>>) tuple.get("components"); var reps = new ArrayList<Map<String,Object>>(); for (var rep : (List<Map<String,Object>>) canonical.get("argumentReps")) reps.add(with(rep,"evaluated",true));
        var formals = new ArrayList<Map<String,Object>>(); var operands = new ArrayList<Object>();
        for (int i = 0; i < reps.size(); i++) { formals.add(map("id","arg" + i,"lifted",false,"rep",stored.getOrDefault(i,reps.get(i)))); operands.add(list("var","arg" + i,map("rep",reps.get(i)))); }
        var call = changeCall.apply(list("app",list("var","original-memcpy-id",map("rep",closure)),operands,list(false,false,false,false),false,false,map("rep",tuple,"foreignCall",declaration)));
        List<Object> callRegion = call;
        if (shadowForeignWithJoin) {
            var joinFormals = new ArrayList<Map<String,Object>>(); for (int i = 0; i < reps.size(); i++) joinFormals.add(map("id","join" + i,"lifted",false,"rep",reps.get(i)));
            var pair = list("app",list("con","tuple2",2,map("rep",closure)),list(list("var","join3",map("rep",reps.get(3))),list("var","join0",map("rep",reps.get(0)))),list(false,false),false,false,map("rep",with(tuple,"evaluated",true)));
            var join = map("id","original-memcpy-id","name","shadowedForeign","lifted",true,"rep",closure,"expr",list("lam",joinFormals,pair,map("rep",closure,"resultRep",tuple)),"joinValueArity",4L,"joinResultRep",tuple,"info",map("joinArity",4L));
            CoreJoins.validate(List.of(join),call,false); callRegion = list("let",false,list(join),call,map("rep",tuple));
        }
        var binders = new ArrayList<Object>(); for (int i = 0; i < fields.size(); i++) binders.add(map("id",i == 0 ? "result-state" : "result-address","lifted",false,"rep",fields.get(i)));
        var body = list("case",callRegion,"result-tuple",list(list("data","tuple2",list("result-state","result-address"),list("var","result-address",map("rep",address)),map("binders",binders))),map("rep",address,"binder",map("id","result-tuple","lifted",false,"rep",with(tuple,"evaluated",true))));
        var binding = map("id","copy","name","copy","arity",4,"lifted",true,"rep",closure,"expr",list("lam",formals,body,map("rep",closure,"resultRep",address)));
        return map("schema",1,"module","SyntheticOriginalMemcpy","unit","test","ghc","9.14.1","instrument",true,"bindings",list(binding),"constructors",list(map("id","tuple2","name","(#,#)","kind","unboxed-tuple","arity",2,"tag",1)));
    }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000").build(); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private <T> T inside(Callable<T> block) throws Exception { try (var context = context()) { context.initialize("thc"); context.enter(); try { return block.call(); } finally { context.leave(); } } }
    private ExecutableProgram program(Language language,String backend) throws Exception { return program(language,backend,module()); }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> source) { return backend.equals("ast") ? new Program(language,source) : new BytecodeProgram(language,source); }
    private ManagedAddress managed() { return ManagedAddress.fromAllocation(ManagedAllocation.mutable(16,8)); }
    private List<Long> contents(ManagedAddress base) { var values = new ArrayList<Long>(); for (long i = 0; i < 16; i++) values.add(base.readWord8(i)); return values; }
    private boolean linuxNative() { return System.getProperty("os.name").equals("Linux") && Set.of("amd64","x86_64").contains(System.getProperty("os.arch")); }
    private ManagedAddress copy(RootCallTarget target,ManagedAddress destination,ManagedAddress source,long count) { return (ManagedAddress) callScalarTestTarget(target,new Object[]{0L,destination,source,count,thc.runtime.Unit.INSTANCE}); }
    @Test void originalArrayMemcpyRetainsBackingIdentityAndByteArraySafety() throws Exception {
        // Original Alex Output module SHA-256 5ca87bc502b96c468c4b45647776d77693510b4bb2febd1af4fbaeb765091664.
        var original = (Map<String,Object>) Json.parse(resource("/core/original-array-memcpy-descriptor.json"));
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var guest = program(language,backend,module(original,Map.of(),false,original,UnaryOperator.identity())); var target = guest.entryTarget("copy"); byte[] bytes = new byte[16]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i + 16);
            var mutable = ManagedAllocation.mutable(16,8); for (int i = 0; i < bytes.length; i++) mutable.writeByte(i,bytes[i]); List<Object> sources = List.of(bytes,ManagedAllocation.immutable(bytes.clone(),8),mutable); List<Object> destinations = List.of(new byte[16],ManagedAllocation.mutable(16,8)); byte[] empty = new byte[0];
            assertTrue(((ManagedAddress) callScalarTestTarget(target,new Object[]{0L,empty,empty,0L,thc.runtime.Unit.INSTANCE})).sameLocation(ManagedAddress.fromByteArray(empty)));
            class Exercise { void run(Object source,Object destination) { var result = (ManagedAddress) callScalarTestTarget(target,new Object[]{0L,destination,source,8L,thc.runtime.Unit.INSTANCE}); var view = ManagedAddress.fromGuestByteArray(destination); assertTrue(result.sameLocation(view)); var actual = new ArrayList<Long>(); for (long i = 0; i < 8; i++) actual.add(result.readWord8(i)); assertEquals(List.of(16L,17L,18L,19L,20L,21L,22L,23L),actual); result.writeWord8(15,99); assertEquals(99L,view.readWord8(15)); }}
            var exercise = new Exercise(); for (var source : sources) for (var destination : destinations) exercise.run(source,destination); target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target);
            for (var source : sources) for (var destination : destinations) { long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); exercise.run(source,destination); assertEquals(before + 1,((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target); }
            var destination = destinations.getLast(); var before = contents(ManagedAddress.fromGuestByteArray(destination)); var shrunk = ManagedAllocation.mutable(16,8); shrunk.shrink(3); record Invalid(Object source,long count) {}
            for (var invalid : List.of(new Invalid(sources.getFirst(),-1),new Invalid(sources.getFirst(),17),new Invalid(destination,1),new Invalid(shrunk,4))) { assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,destination,invalid.source(),invalid.count(),thc.runtime.Unit.INSTANCE})); assertEquals(before,contents(ManagedAddress.fromGuestByteArray(destination))); }
            assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,sources.get(1),sources.getFirst(),8L,thc.runtime.Unit.INSTANCE})); var pointers = ManagedAllocation.mutable(16,8); var payload = managed(); ManagedAddress.fromAllocation(pointers).writeAddressElementIndex(0,payload);
            callScalarTestTarget(target,new Object[]{0L,destination,pointers,8L,thc.runtime.Unit.INSTANCE}); assertSame(payload,ManagedAddress.fromGuestByteArray(destination).readAddressElementIndex(0)); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,destination,pointers,7L,thc.runtime.Unit.INSTANCE})); assertSame(payload,ManagedAddress.fromGuestByteArray(destination).readAddressElementIndex(0)); return null;
        });
    }
    @Test void originalDescriptorCopiesManagedAndNativeRegionsOnFirstCompiledCalls() throws Exception {
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var guest = program(language,backend); var target = guest.entryTarget("copy"); var sources = new ArrayList<>(List.of(managed(),ManagedAddress.fromByteArray(new byte[16]))); var destinations = new ArrayList<>(List.of(managed(),ManagedAddress.fromByteArray(new byte[16])));
            if (linuxNative()) { sources.add(Language.currentState().getNativeAllocations().malloc(16)); destinations.add(Language.currentState().getNativeAllocations().malloc(16)); }
            class Exercise { void run(ManagedAddress source,ManagedAddress destination) {
                for (long i = 0; i < 16; i++) { source.writeWord8(i,i + 16); destination.writeWord8(i,0); } var interior = destination.plus(2); assertSame(interior,copy(target,interior,source.plus(3),5)); assertEquals(List.of(0L,0L,19L,20L,21L,22L,23L,0L,0L,0L,0L,0L,0L,0L,0L,0L),contents(destination)); var expected = new ArrayList<Long>(); for (long i = 16; i < 32; i++) expected.add(i); assertEquals(expected,contents(source));
            }}
            var exercise = new Exercise(); for (var source : sources) for (var destination : destinations) exercise.run(source,destination); target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target);
            for (var source : sources) for (var destination : destinations) { long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); exercise.run(source,destination); assertEquals(before + 1,((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target); } return null;
        });
    }
    @Test void originalRamMemcpyMatchesNativeOnFirstCompiledCalls() throws Exception {
        var rows = new ArrayList<List<String>>(); for (var line : resource("/core/original-memcpy-native.tsv").trim().split("\\R")) rows.add(Arrays.asList(line.split("\t",-1))); assertEquals(36,rows.size());
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var guest = program(language,backend,module(ramDescriptor())); var target = guest.entryTarget("copy"); var sources = new ArrayList<>(List.of(managed(),ManagedAddress.fromByteArray(new byte[16]))); var destinations = new ArrayList<>(List.of(managed(),ManagedAddress.fromByteArray(new byte[16])));
            if (linuxNative()) { sources.add(Language.currentState().getNativeAllocations().malloc(16)); destinations.add(Language.currentState().getNativeAllocations().malloc(16)); }
            class Exercise { void run(ManagedAddress source,ManagedAddress destination,List<String> row) {
                for (long i = 0; i < 16; i++) { source.writeWord8(i,i + 16); destination.writeWord8(i,0); } var interior = destination.plus(Long.parseLong(row.get(1))); var result = copy(target,interior,source.plus(Long.parseLong(row.get(0))),Long.parseLong(row.get(2)));
                boolean expected = switch (row.get(3).toLowerCase(Locale.ROOT)) { case "true" -> true; case "false" -> false; default -> throw new IllegalArgumentException("Invalid strict boolean: " + row.get(3)); }; assertEquals(expected,result.sameLocation(interior)); assertSame(interior,result);
                var expectedDestination = new ArrayList<Long>(); for (var value : row.get(4).split(",",-1)) expectedDestination.add(Long.parseLong(value)); var expectedSource = new ArrayList<Long>(); for (var value : row.get(5).split(",",-1)) expectedSource.add(Long.parseLong(value)); assertEquals(expectedDestination,contents(destination)); assertEquals(expectedSource,contents(source));
            }}
            var exercise = new Exercise(); for (var source : sources) for (var destination : destinations) for (var row : rows) exercise.run(source,destination,row); target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target);
            for (var source : sources) for (var destination : destinations) for (var row : rows) { long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue(); exercise.run(source,destination,row); assertEquals(before + 1,((Number) guest.diagnostics().get("compiledEntries")).longValue()); valid(target); } return null;
        });
    }
    @Test void ramUnitAuthorityDoesNotAdmitOtherLibrariesSymbolsOrArrayAbi() throws Exception {
        inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = (Map<String,Object>) ramDescriptor().get("target"); var arrayDescriptor = (Map<String,Object>) Json.parse(resource("/core/original-array-memcpy-descriptor.json"));
            for (var backend : List.of("ast","bytecode")) {
                for (var unit : List.of("ram-0.22.1","ram-0.22.1-inplace","ram-0.22.1-aB123")) program(language,backend,module(with(ramDescriptor(),"target",with(target,"unit",unit))));
                for (var unit : list(null,list("ram-0.22.1"),"other-0.22.1","ram-0.22.0","ram-0.22.1-","ram-0.22.1-a-b","ram-0.22.1 hash","ram-0.22.1:hash","ram-0.22.1\n")) assertThrows(RuntimeFault.class,() -> program(language,backend,module(with(ramDescriptor(),"target",with(target,"unit",unit)))));
                for (var replacement : List.of(with(ramDescriptor(),"target",with(target,"symbol","memmove")),with(ramDescriptor(),"safety","safe"),with(ramDescriptor(),"convention","capi"),with(arrayDescriptor,"target",target))) assertThrows(RuntimeFault.class,() -> program(language,backend,module(replacement,Map.of(),false,replacement,UnaryOperator.identity())));
            }
            return null;
        });
    }
    @Test void overlapRangesAndNativeOwnershipRejectBeforeWriting() throws Exception {
        for (var declaration : List.of(descriptor(),ramDescriptor())) for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = program(language,backend,module(declaration)).entryTarget("copy"); byte[] array = new byte[16]; var arrayBase = ManagedAddress.fromByteArray(array); var bases = new ArrayList<>(List.of(managed(),arrayBase)); if (linuxNative()) bases.add(Language.currentState().getNativeAllocations().malloc(16));
            for (var base : bases) {
                for (long i = 0; i < 16; i++) base.writeWord8(i,i); var adjacent = base.plus(8); assertSame(adjacent,copy(target,adjacent,base,8)); var end = base.plus(16); assertSame(end,copy(target,end,end,0)); var before = contents(base);
                class Reject { void call(ManagedAddress destination,ManagedAddress source,long count) { assertThrows(RuntimeFault.class,() -> copy(target,destination,source,count)); assertEquals(before,contents(base)); }}
                var reject = new Reject(); reject.call(base.plus(4),base,8); reject.call(base,base.plus(4),8); reject.call(base,base,1); for (long count : new long[]{-1,17,Long.MAX_VALUE}) reject.call(base,managed(),count);
                reject.call(base,managed().plus(15),2); reject.call(base.plus(15),managed(),2); reject.call(base,ManagedAddress.unownedNumeric(1),1); reject.call(ManagedAddress.unownedNumeric(1),base,1); reject.call(ManagedAddress.fromHex("0000000000000000"),base,8);
                assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,base,managed(),1L,0L})); assertEquals(before,contents(base));
            }
            var beforeAlias = contents(arrayBase); assertThrows(RuntimeFault.class,() -> copy(target,ManagedAddress.fromByteArray(array).plus(4),arrayBase,8)); assertEquals(beforeAlias,contents(arrayBase));
            if (linuxNative()) {
                var nativeAddress = bases.getLast(); var destination = managed(); var before = contents(destination); var nativeBefore = contents(nativeAddress);
                try (var other = context()) { other.initialize("thc"); other.enter(); try { var otherLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null); var otherTarget = program(otherLanguage,backend,module(declaration)).entryTarget("copy"); assertThrows(RuntimeFault.class,() -> copy(otherTarget,destination,nativeAddress,1)); assertThrows(RuntimeFault.class,() -> copy(otherTarget,nativeAddress,destination,1)); } finally { other.leave(); } }
                assertEquals(before,contents(destination)); assertEquals(nativeBefore,contents(nativeAddress)); var alias = nativeAddress.plus(4); Language.currentState().getNativeAllocations().free(nativeAddress);
                assertThrows(RuntimeFault.class,() -> copy(target,destination,alias,0)); assertThrows(RuntimeFault.class,() -> copy(target,alias,destination,0)); assertEquals(before,contents(destination));
            }
            return null;
        });
    }
    @Test void wholePointerCellsCopyAsReferencesAndPartialCellsReject() throws Exception {
        for (var backend : List.of("ast","bytecode")) inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = program(language,backend).entryTarget("copy"); var base = ManagedAddress.fromAllocation(ManagedAllocation.mutable(40,8)); var payload = managed(); base.writeAddressElementIndex(0,payload); base.writeAddressElementIndex(1,base.plus(32));
            var destination = base.plus(16); assertSame(destination,copy(target,destination,base,16)); assertSame(payload,base.readAddressElementIndex(2)); assertTrue(base.readAddressElementIndex(3).sameLocation(base.plus(32))); assertThrows(RuntimeFault.class,() -> copy(target,destination,base.plus(1),8)); assertSame(payload,base.readAddressElementIndex(2)); assertTrue(base.readAddressElementIndex(3).sameLocation(base.plus(32))); return null;
        });
    }
    @Test void malformedDescriptorsAndForgedProofsRejectInBothBackends() throws Exception {
        inside(() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (var backend : List.of("ast","bytecode")) {
                class Reject { void call(Consumer<Map<String,Object>> change) throws Exception { var malformed = new LinkedHashMap<>(descriptor()); change.accept(malformed); assertThrows(RuntimeFault.class,() -> program(language,backend,module(malformed))); }}
                var reject = new Reject(); reject.call(it -> it.put("safety","safe")); reject.call(it -> it.put("convention","capi")); reject.call(it -> it.put("arity",3L)); reject.call(it -> it.put("suppliedArity",3L)); reject.call(it -> it.put("schema",1.0)); reject.call(it -> it.put("argumentReps",list(address))); reject.call(it -> it.put("resultRep",address));
                for (var unit : list(null,"foreign")) reject.call(it -> it.put("target",with((Map<?,?>) it.get("target"),"unit",unit))); reject.call(it -> it.put("target",with((Map<?,?>) it.get("target"),"isFunction",false))); reject.call(it -> it.put("target",with((Map<?,?>) it.get("target"),"kind","dynamic")));
                for (int i = 0; i <= 3; i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(language,backend,module(descriptor(),Map.of(index,longRep),false,descriptor(),UnaryOperator.identity()))); }
                var ordinaryJoin = module(descriptor(),Map.of(),true,descriptor(),it -> { var call = new ArrayList<>(it); call.set(6,without((Map<?,?>) call.get(6),"foreignCall")); return call; });
                var ordinaryTarget = program(language,backend,ordinaryJoin).entryTarget("copy"); var destination = managed(); assertSame(destination,copy(ordinaryTarget,destination,managed(),1));
                var shadowed = assertThrows(RuntimeFault.class,() -> program(language,backend,module(descriptor(),Map.of(),true,descriptor(),UnaryOperator.identity()))); assertTrue(Objects.toString(shadowed.getMessage(),"").contains("unresolved original foreign variable required"),shadowed.getMessage());
                for (var head : list(list("var","arg0",map("rep",closure)),list("prim","memcpy",map("rep",closure)))) assertThrows(RuntimeFault.class,() -> program(language,backend,module(descriptor(),Map.of(),false,descriptor(),it -> { var call = new ArrayList<>(it); call.set(1,head); return call; })));
                assertThrows(RuntimeFault.class,() -> program(language,backend,module(descriptor(),Map.of(),false,descriptor(),it -> { var call = new ArrayList<>(it); call.set(3,list(true,false,false,false)); return call; })));
                assertThrows(RuntimeFault.class,() -> program(language,backend,module(descriptor(),Map.of(),false,descriptor(),it -> { var call = new ArrayList<>(it); var arguments = new ArrayList<>((List<Object>) call.get(2)); arguments.set(0,list("lit","int","0",map("rep",address))); call.set(2,arguments); return call; })));
            }
            return null;
        });
    }
}
