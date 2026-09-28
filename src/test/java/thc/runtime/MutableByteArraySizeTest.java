// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import kotlin.Unit;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class MutableByteArraySizeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize");
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private Map<String,Object> manifest() throws Exception {
        return (Map<String,Object>)Json.parse(Files.readString(new File(root,"build/mutable-bytearray-size/manifest.json").toPath()));
    }
    private Map<String,Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String,Object>>();
        for (var path : paths) modules.add((Map<String,Object>)Json.parse(Files.readString(new File(root,path).toPath())));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String,Object> module, String backend) {
        return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module);
    }
    private void valid(RootCallTarget target,String label) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target),label); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target,"installed"); }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget,Boolean>());
        var result = new ArrayList<RootCallTarget>(); visit(entry,seen,result); return result;
    }
    private void visit(RootCallTarget target,Set<RootCallTarget> seen,List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode(); if (node != null) nodes.add(node);
                    }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node,DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot) visit(active,seen,result);
        result.add(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getResults().getDepth());
        assertEquals(0,state.getArguments().retainedReferences()); assertEquals(0,state.getResults().retainedReferences());
    }
    @Test void nativeLiveMutableSizesWithInlining() throws Exception { verifyNative(true); }
    @Test void nativeLiveMutableSizesAcrossResidualCalls() throws Exception { verifyNative(false); }
    private record Row(long raw,long code,long expected) {}
    private record Input(long raw,long code) {}
    @Test void sizeFixtureEvidenceRejectsMissingAndChangedProvenance() throws Exception { ByteArrayFixtureEvidence.rejectionControls(root,"mutable-bytearray-size"); }
    // Independent of both the native driver and the guest byte-array implementation.
    // Long multiplication deliberately wraps, matching the signed 64-bit input inventory.
    private final Comparator<Input> inputOrder=Comparator.comparingLong(Input::raw).thenComparingLong(Input::code);
    private final List<Input> inputs=inputs();
    private List<Input> inputs() {
        var values=new HashSet<Input>();
        for(long code=0;code<17*17;code++)values.add(new Input(code*0x123456789abcdefL,code));
        for(long raw=0;raw<=255;raw++)values.add(new Input(raw,280));
        for(long raw:new long[]{Long.MIN_VALUE,-257,-1,0,255,256,Long.MAX_VALUE})for(long code:new long[]{Long.MIN_VALUE,-1,0,16,17,288,1023,4095,4096,Long.MAX_VALUE})values.add(new Input(raw,code));
        var result=new ArrayList<>(values);result.sort(inputOrder);return result;
    }
    private long mathematical(String name,long raw,long code) {
        long key=code&1023L,old=key%17,middle=key/17%17;
        return switch(name){case "freshSize","pureSize"->code&4095;case "resizedSizes"->old*65536+middle*256+(middle+7)%17;case "pureAfterResize"->middle;case "orderedSize"->old+1+(middle+1)*257+((raw+old+1)&255)*65537;default->throw new IllegalStateException("Unknown mutable-size entry "+name);};
    }
    private Long longOrNull(String value){try{return Long.valueOf(value);}catch(NumberFormatException failure){return null;}}
    private void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private Map<String,List<Row>> checkedRows(String text) {
        var lines=new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r",-1)));if(!lines.isEmpty()&&lines.getLast().isEmpty())lines.removeLast();require(lines.size()==names.size()*inputs.size(),"Mutable-size oracle row count mismatch");
        var rows=new LinkedHashMap<String,List<Row>>();for(var name:names)rows.put(name,new ArrayList<>());
        for(int index=0;index<lines.size();index++) {
            var fields=lines.get(index).split("\t",-1);require(fields.length==4,"Mutable-size oracle columns at row "+index);var name=names.get(index/inputs.size());var input=inputs.get(index%inputs.size());
            require(fields[0].equals(name)&&Objects.equals(longOrNull(fields[1]),input.raw())&&Objects.equals(longOrNull(fields[2]),input.code()),"Mutable-size oracle inventory/order mismatch at row "+index);
            var actual=longOrNull(fields[3]);require(Objects.equals(actual,mathematical(name,input.raw(),input.code())),"Mutable-size native/model mismatch at row "+index);rows.get(name).add(new Row(input.raw(),input.code(),actual));
        }
        return rows;
    }
    @Test void independentInventoryCoversSizesResizeBoundariesAndBytePatterns() {
        assertEquals(614,inputs.size());assertEquals(3070,names.size()*inputs.size());assertEquals(inputs.size(),new HashSet<>(inputs).size());var sorted=new ArrayList<>(inputs);sorted.sort(inputOrder);assertEquals(inputs,sorted);
        var range=new ArrayList<Input>();for(long i=0;i<=288;i++)range.add(new Input(i*0x123456789abcdefL,i));assertTrue(inputs.containsAll(range));range.clear();for(long i=0;i<=255;i++)range.add(new Input(i,280));assertTrue(inputs.containsAll(range));
        for(long raw:new long[]{Long.MIN_VALUE,-257,-1,0,255,256,Long.MAX_VALUE})for(long code:new long[]{Long.MIN_VALUE,-1,0,16,17,288,1023,4095,4096,Long.MAX_VALUE})assertTrue(inputs.contains(new Input(raw,code)),raw+"/"+code);
        for(var input:inputs) {
            long raw=input.raw(),code=input.code(),old=(code&1023)%17,middle=(code&1023)/17%17;
            for(var name:List.of("freshSize","pureSize"))assertEquals(Math.floorMod(code,4096),mathematical(name,raw,code));assertEquals(middle,mathematical("pureAfterResize",raw,code));
            long encoded=mathematical("resizedSizes",raw,code);assertEquals(List.of(old,middle,(middle+7)%17),List.of(encoded/65536,encoded/256%256,encoded%256));
            assertEquals(old+1+(middle+1)*257+Math.floorMod(raw+old+1,256)*65537,mathematical("orderedSize",raw,code));
        }
        var wanted=new HashSet<Long>();var actual=new HashSet<Long>();for(long i=0;i<=255;i++){wanted.add(i);actual.add((mathematical("orderedSize",i,280)-4378)/65537);}assertEquals(wanted,actual);
    }
    @Test void independentModelHasExplicitBoundaryAnchors() {
        for(var name:List.of("freshSize","pureSize"))for(var row:new long[][]{{Long.MIN_VALUE,0},{-1,4095},{0,0},{16,16},{17,17},{288,288},{1023,1023},{4095,4095},{4096,0},{Long.MAX_VALUE,4095}})assertEquals(row[1],mathematical(name,Long.MAX_VALUE,row[0]),name+"/"+row[0]);
        for(var row:new long[][]{{Long.MIN_VALUE,7},{0,7},{16,1048583},{17,264},{288,1052678},{1023,198928},{-1,198928},{Long.MAX_VALUE,198928}})assertEquals(row[1],mathematical("resizedSizes",0,row[0]),"resizedSizes/"+row[0]);
        for(var row:new long[][]{{0,0},{288,16},{1023,9}})assertEquals(row[1],mathematical("pureAfterResize",0,row[0]));
        for(var row:new long[][]{{0,65795},{-1,258},{255,258},{256,65795},{Long.MAX_VALUE,258},{Long.MIN_VALUE,65795}})assertEquals(row[1],mathematical("orderedSize",row[0],0),"orderedSize/"+row[0]);
    }
    private Map<String,List<Row>> verify(List<String> lines){return checkedRows(String.join("\n",lines)+"\n");}
    private void reject(String label,List<String> broken){assertThrows(IllegalArgumentException.class,()->verify(broken),label);}
    private List<String> replaced(List<String> lines,String first){var result=new ArrayList<>(lines);result.set(0,first);return result;}
    @Test void exactOracleRejectsMissingDuplicatedReorderedMalformedAndWrongRows() {
        var lines=new ArrayList<String>();var expected=new LinkedHashMap<String,List<Row>>();for(var name:names){var rows=new ArrayList<Row>();for(var input:inputs){lines.add(name+"\t"+input.raw()+"\t"+input.code()+"\t"+mathematical(name,input.raw(),input.code()));rows.add(new Row(input.raw(),input.code(),mathematical(name,input.raw(),input.code())));}expected.put(name,rows);}
        assertEquals(expected,verify(lines));assertEquals(expected,checkedRows(String.join("\n",lines)));
        reject("missing",lines.subList(1,lines.size()));var extra=new ArrayList<>(lines);extra.add(lines.getFirst());reject("extra duplicate",extra);var duplicate=new ArrayList<>(lines);duplicate.set(1,duplicate.getFirst());reject("duplicate replaces input",duplicate);reject("reversed",lines.reversed());var swapped=new ArrayList<>(lines);Collections.swap(swapped,0,1);reject("swapped inputs",swapped);var rotated=new ArrayList<>(lines.subList(inputs.size(),lines.size()));rotated.addAll(lines.subList(0,inputs.size()));reject("swapped entries",rotated);
        record Mutation(int column,String value){}for(var mutation:List.of(new Mutation(0,"unknownSize"),new Mutation(1,"0"),new Mutation(2,"1"),new Mutation(3,"-1"),new Mutation(1,"not-an-int"),new Mutation(2,"9223372036854775808"),new Mutation(3,""),new Mutation(3,"1.0"),new Mutation(3,"9223372036854775808"))) {
            var fields=new ArrayList<>(Arrays.asList(lines.getFirst().split("\t",-1)));fields.set(mutation.column(),mutation.value());reject("field "+mutation.column()+"/"+mutation.value(),replaced(lines,String.join("\t",fields)));
        }
        reject("missing column",replaced(lines,lines.getFirst().substring(0,lines.getFirst().lastIndexOf('\t'))));reject("extra column",replaced(lines,lines.getFirst()+"\t0"));reject("blank row",replaced(lines,""));extra=new ArrayList<>(lines);extra.add("");reject("extra blank row",extra);reject("empty",List.of());
    }
    private long count(ExecutableProgram p){return ((Number)p.diagnostics().get("compiledEntries")).longValue();}
    private void check(Row row,Value function,String label,Language language){assertEquals(row.expected(),function.execute(row.raw(),row.code()).asLong(),label+"/"+row.raw()+"/"+row.code());released(language);}
    private void verifyNative(boolean inlining)throws Exception {
        var manifest=manifest();assertEquals(names,manifest.get("entries"));var wantedInputs=new ArrayList<List<Long>>();for(var input:inputs)wantedInputs.add(List.of(input.raw(),input.code()));assertEquals(wantedInputs,manifest.get("inputs"));assertEquals((long)names.size()*inputs.size(),manifest.get("nativeRows"));assertEquals(Set.of("pre","post"),((Map<?,?>)manifest.get("stages")).keySet());var audits=new HashSet<String>();for(var stage:List.of("pre","post"))for(var name:names)audits.add(stage+"/"+name);assertEquals(audits,((Map<?,?>)manifest.get("audits")).keySet());ByteArrayFixtureEvidence.verify(root,"mutable-bytearray-size",manifest);var rows=checkedRows(Files.readString(new File(root,"build/mutable-bytearray-size/oracle.tsv").toPath()));
        for(var stage:((Map<String,List<String>>)manifest.get("stages")).entrySet())for(var name:names) {
            var cases=Objects.requireNonNull(rows.get(name));var audit=(Map<String,Object>)Json.parse(Files.readString(new File(root,"build/mutable-bytearray-size/"+stage.getKey()+"-"+name+".audit.json").toPath()));assertEquals(true,audit.get("accepted"));boolean pure=List.of("pureSize","pureAfterResize").contains(name),primitive=false,worker=false;
            for(var p:(List<Map<String,Object>>)audit.get("primitives"))if((pure?"sizeofMutableByteArray#":"getSizeofMutableByteArray#").equals(p.get("name")))primitive=true;assertTrue(primitive);
            for(var b:(List<Map<String,Object>>)audit.get("reachableBindings"))if(((String)b.get("id")).endsWith(pure?".pureSizeWorker":".getSizeWorker"))worker=true;assertTrue(worker);
            for(var backend:List.of("ast","bytecode"))try(var context=context(inlining)){context.initialize("thc");context.enter();try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);var module=new LinkedHashMap<>(CoreModules.reachable(merged(stage.getValue()),name));module.put("instrument",true);var p=program(language,module,backend);var function=context.asValue(new EntryValue(p,name,2));var host=p.hostEntryTarget(2);var original=p.entryTarget(name);var label=stage.getKey()+"/"+backend+"/"+name+"/inlining="+inlining;
                for(var row:cases)check(row,function,label,language);var targets=activeTargets(host);assertTrue(targets.size()>1,label);for(var target:targets)if(target!=host)compile(target);assertTrue(function.invokeMember("compile").asBoolean());long allocations=language.getHandoffState().get().getResults().getAllocations();if(!pure)assertTrue(allocations>0,label+" native retained getSizeWorker returns a Long tuple");
                for(var row:cases.reversed()){long before=count(p);check(row,function,label,language);assertTrue(count(p)>before,label+" compiled guest");assertEquals(targets,activeTargets(host),label+" active identities");valid(original,label);for(var target:targets)valid(target,label);}
                for(var counter:List.of("unsupportedTraps","blackholes"))assertEquals(0L,((Number)p.diagnostics().get(counter)).longValue(),label);System.out.println("MutableByteArraySize PASS "+label+" rows="+cases.size());
            }finally{context.leave();}}
        }
    }
    private final Map<String,Object> arrayProof=Map.of("kind","object","primReps",List.of("BoxedRep (Just Unlifted)"),"evaluated",true),longProof=Map.of("kind","long","primReps",List.of("IntRep"),"evaluated",true),stateProof=Map.of("kind","void","primReps",List.of(),"evaluated",true),closureProof=Map.of("kind","closure","primReps",List.of("BoxedRep (Just Lifted)"),"evaluated",true);
    private final Map<String,Object> tupleProof=Map.of("kind","unknown","aggregate","unboxed-tuple","primReps",List.of("IntRep"),"components",List.of(stateProof,longProof),"evaluated",true);
    private Map<String,Object> synthetic(boolean effectful) {
        var proofs=effectful?List.of(arrayProof,stateProof):List.of(arrayProof);var params=new ArrayList<Map<String,Object>>();var args=new ArrayList<Object>();for(int i=0;i<proofs.size();i++){params.add(Map.of("id","p"+i,"lifted",false,"rep",proofs.get(i)));args.add(List.of("var","p"+i,Map.of("rep",proofs.get(i))));}
        var app=List.of("app",List.of("prim",effectful?"getSizeofMutableByteArray#":"sizeofMutableByteArray#"),args,Collections.nCopies(proofs.size(),false),false,false,Map.of("rep",effectful?tupleProof:longProof));
        var binders=List.of(Map.of("id","s","lifted",false,"rep",stateProof),Map.of("id","n","lifted",false,"rep",longProof));var body=effectful?List.of("case",app,"pair",List.of(List.of("data","T2",List.of("s","n"),List.of("var","n",Map.of("rep",longProof)),Map.of("binders",binders))),Map.of("rep",longProof,"binder",Map.of("id","pair","lifted",false,"rep",tupleProof))):app;
        return Map.of("instrument",true,"constructors",List.of(Map.of("id","T2","kind","unboxed-tuple","arity",2,"tag",1)),"bindings",List.of(Map.of("id","size","name","size","arity",proofs.size(),"lifted",true,"rep",closureProof,"expr",List.of("lam",params,body,Map.of("rep",closureProof,"resultRep",longProof)))));
    }
    @Test void effectfulSizeEvaluatesStateOnceBeforeTypedPublication()throws Exception {
        var builder=FrameDescriptor.newBuilder();for(int i=0;i<2;i++)builder.addSlot(FrameSlotKind.Long,null,null);var frame=Truffle.getRuntime().createVirtualFrame(new Object[0],builder.build());
        for(var mode:List.of("ok","throw","invalid")) {
            var events=new ArrayList<String>();FrameAccess.writeLong(frame,0,37);FrameAccess.writeLong(frame,1,91);
            var array=new Expr(){@Override public Object execute(VirtualFrame f){events.add("array");return new byte[]{1,2,3};}};
            var state=new Expr(){@Override public Object execute(VirtualFrame f){events.add("state");try{assertEquals(91L,f.getLong(1));}catch(com.oracle.truffle.api.frame.FrameSlotTypeException error){return sneaky(error);}if(mode.equals("throw"))throw new RuntimeFault("state failed");if(mode.equals("invalid"))return 17L;return Unit.INSTANCE;}};
            var expr=ByteArrayOp.expression(ByteArrayOp.GET_SIZE_MUTABLE,CoreRepresentation.Companion.getUNKNOWN(),new Expr[]{array,state});
            if(mode.equals("ok")){expr.executeTuple(frame,new int[]{0,1},1);assertEquals(3L,frame.getLong(1));}else{assertThrows(RuntimeFault.class,()->expr.executeTuple(frame,new int[]{0,1},1));assertEquals(91L,frame.getLong(1));}
            assertEquals(37L,frame.getLong(0));assertEquals(List.of("array","state"),events);assertThrows(RuntimeFault.class,()->expr.execute(frame));
        }
    }
    private static <T,E extends Throwable>T sneaky(Throwable failure)throws E{throw (E)failure;}
    private Object call(RootCallTarget target,boolean effectful,Object array,Object state){return Calls.target(target,effectful?new Object[]{0L,array,state}:new Object[]{0L,array});}
    @Test void typedEntriesReturnLongAndRejectInvalidStateAndCarriersWithoutLoans()throws Exception {
        var sizes=List.of(0,1,7,8,15,16,31,32,255,256,4095);
        for(var backend:List.of("ast","bytecode"))for(boolean effectful:new boolean[]{false,true})try(var context=context(true)){context.initialize("thc");context.enter();try {
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);var p=program(language,synthetic(effectful),backend);var target=p.entryTarget("size");for(int size:sizes)assertEquals((long)size,call(target,effectful,new byte[size],Unit.INSTANCE));compile(target);
            for(int size:sizes.reversed()){long before=count(p);var result=call(target,effectful,new byte[size],Unit.INSTANCE);assertTrue(result instanceof Long);assertEquals((long)size,result);assertEquals(before+1,count(p),backend+"/"+effectful+"/"+size);valid(target,backend+"/"+effectful+"/"+size);released(language);}
            if(effectful)for(var state:new Object[]{null,0L,new Object()}){var error=assertThrows(RuntimeFault.class,()->call(target,true,new byte[]{1},state));
                // Null may fail the local initialization guard before reaching the State primitive.
                if(state!=null)assertTrue(Objects.toString(error.getMessage(),"").contains("zero-width scalar carrier"),error.getMessage());released(language);}
            for(var bad:new Object[]{null,new Object(),new Object[]{1},new long[]{1}}){assertThrows(RuntimeFault.class,()->call(target,effectful,bad,Unit.INSTANCE));released(language);}assertEquals(2L,call(target,effectful,new byte[]{7,8},Unit.INSTANCE));released(language);
        }finally{context.leave();}}
    }
    private List<List<Object>> applications(Object value){var result=new ArrayList<List<Object>>();if(value instanceof List<?> list){if(!list.isEmpty()&&"app".equals(list.getFirst()))result.add((List<Object>)list);for(var item:list)result.addAll(applications(item));}else if(value instanceof Map<?,?> map)for(var item:map.values())result.addAll(applications(item));return result;}
    private List<Object> application(Object module){var apps=applications(module);if(apps.size()!=1)throw new IllegalArgumentException("Expected one application");return apps.getFirst();}
    private Map<String,Object> withReps(String rep){var result=new LinkedHashMap<>(longProof);result.put("primReps",List.of(rep));return result;}
    @Test void exactScalarAndStateLongPairContractsAreRequiredInBothLoaders() {
        for(var backend:List.of("ast","bytecode"))for(boolean effectful:new boolean[]{false,true})try(var context=context(true)){context.initialize("thc");context.enter();try {
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for(int mutation=0;mutation<=8;mutation++)for(boolean diagnostic:new boolean[]{false,true}) {
                var module=(Map<String,Object>)Json.parse(Json.stringify(synthetic(effectful)));var app=application(module);var args=(List<Object>)app.get(2);var flags=(List<Object>)app.get(3);var meta=CoreRepresentations.INSTANCE.metadata(app);var empty=Map.of("kind","unknown","aggregate","unboxed-tuple","components",List.of(),"primReps",List.of(),"evaluated",true);
                switch(mutation){case 0->{args.removeLast();flags.removeLast();}case 1->{args.add(args.getFirst());flags.add(false);}case 2->meta.remove("rep");case 3->flags.set(0,true);case 4->((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.getFirst()).get("rep")).put("primReps",List.of("BoxedRep (Just Lifted)"));case 5->((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.getFirst()).get("rep")).put("primReps",List.of("BoxedRep Nothing"));case 6->{if(effectful)CoreRepresentations.INSTANCE.metadata((List<Object>)args.get(1)).put("rep",empty);else meta.put("rep",tupleProof);}case 7->{if(effectful)((List<Object>)((Map<?,?>)meta.get("rep")).get("components")).set(0,empty);else meta.put("rep",withReps("WordRep"));}case 8->meta.put("rep",effectful?longProof:withReps("Int64Rep"));}
                var configured=new LinkedHashMap<>(module);configured.put("diagnosticUnsupported",diagnostic);var label=backend+"/"+effectful+"/"+mutation+"/"+diagnostic;
                if(!effectful&&(mutation==7||mutation==8))assertDoesNotThrow(()->program(language,configured,backend),label);else assertThrows(RuntimeFault.class,()->program(language,configured,backend),label);
            }
            var module=(Map<String,Object>)Json.parse(Json.stringify(synthetic(effectful)));var app=application(module);var bare=new ArrayList<>((List<?>)app.get(1));app.clear();app.addAll(bare);assertThrows(UnsupportedCore.class,()->program(language,module,backend));
        }finally{context.leave();}}
    }
}
