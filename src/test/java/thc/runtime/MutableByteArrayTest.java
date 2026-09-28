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
class MutableByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes", "publicReplicate");
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private Map<String,Object> manifest() throws Exception {
        return (Map<String,Object>)Json.parse(Files.readString(new File(root,"build/mutable-bytearrays/manifest.json").toPath()));
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
    private final List<ByteArrayOp> operations=List.of(ByteArrayOp.SET,ByteArrayOp.COPY_MUTABLE,ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING);
    @Test void nativeFillsMovesAndPublicReplicateWithInlining()throws Exception{verifyNative(true);}
    @Test void nativeFillsMovesAndPublicReplicateAcrossResidualCalls()throws Exception{verifyNative(false);}
    private record Row(long raw,long code,long expected){}
    private record Input(long raw,long code){}
    private record Range(int from,int to,int count){}
    private final List<Input> inputs=inputs();
    private List<Input> inputs(){
        var values=new HashSet<Input>();var carriers=new HashSet<Long>();for(long raw=-256;raw<=511;raw++)carriers.add(raw);
        for(long sign:new long[]{-1,1})for(int bit=0;bit<=63;bit++)for(long delta=-1;delta<=1;delta++)carriers.add(sign*((1L<<bit)+delta));
        for(long raw:carriers)values.add(new Input(raw,72));for(long code=0;code<=1023;code++)values.add(new Input(code*0x123456789abcdefL+Long.MIN_VALUE,code));
        for(long raw:new long[]{Long.MIN_VALUE,-257,-1,0,255,256,Long.MAX_VALUE})for(long code:new long[]{Long.MIN_VALUE,-1,0,Long.MAX_VALUE})values.add(new Input(raw,code));
        var result=new ArrayList<>(values);result.sort(Comparator.comparingLong(Input::raw).thenComparingLong(Input::code));return result;
    }
    private Range copyRange(long code){int key=(int)(code&1023),from=key%9,to=key/9%9;return new Range(from,to,Math.min(key/81%9,Math.min(8-from,8-to)));}
    private Range disjointRange(long code){int key=(int)(code&1023),low=key%5,high=4+key/5%5,count=Math.min(key/25%5,Math.min(4-low,8-high));return key/125%2==0?new Range(low,high,count):new Range(high,low,count);}
    private long fingerprint(List<Integer> values){long weight=1,result=0;for(int value:values){result+=weight*value;weight*=257;}return result;}
    // Independent list/snapshot semantics, never a call into ManagedByteArray.
    // Long arithmetic deliberately retains the native signed-64-bit wrap.
    private long model(String name,long raw,long code){
        if(!names.contains(name))throw new IllegalArgumentException("Unknown mutable byte-array entry");var source=new ArrayList<Integer>();for(int i=0;i<8;i++)source.add((int)((raw+17L*i)&255));
        if(name.equals("publicReplicate")){long answer=code&15;for(int i=0;i<(int)(code&15);i++)answer=answer*257+(raw&255);return answer;}
        if(name.equals("filledBytes")){int key=(int)(code&1023),start=key%9,count=Math.min(key/9%9,8-start);for(int i=0;i<count;i++)source.set(start+i,(int)(raw&255));return fingerprint(source);}
        var range=name.equals("disjointBytes")?disjointRange(code):copyRange(code);
        if(List.of("movedBytes","disjointBytes").contains(name)){var snapshot=new ArrayList<>(source);for(int i=0;i<range.count();i++)source.set(range.to()+i,snapshot.get(range.from()+i));return fingerprint(source);}
        var destination=new ArrayList<Integer>();for(int i=0;i<8;i++)destination.add((int)((raw+101+29L*i)&255));for(int i=0;i<range.count();i++)destination.set(range.to()+i,source.get(range.from()+i));source.set(0,(int)((raw+93)&255));return fingerprint(source)+65537*fingerprint(destination);
    }
    private Long longOrNull(String value){try{return Long.valueOf(value);}catch(NumberFormatException failure){return null;}}
    private void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private Map<String,List<Row>> checkedRows(String text){
        var lines=new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r",-1)));if(!lines.isEmpty()&&lines.getLast().isEmpty())lines.removeLast();require(lines.size()==names.size()*inputs.size(),"Incomplete mutable byte-array corpus");var result=new LinkedHashMap<String,List<Row>>();for(var name:names)result.put(name,new ArrayList<>());
        for(int index=0;index<lines.size();index++){var fields=lines.get(index).split("\t",-1);require(fields.length==4,"Expected entry/raw/code/result");var name=names.get(index/inputs.size());var input=inputs.get(index%inputs.size());require(fields[0].equals(name)&&Objects.equals(longOrNull(fields[1]),input.raw())&&Objects.equals(longOrNull(fields[2]),input.code()),"Missing, duplicate, reordered or unknown mutable byte-array row "+index);long expected=model(name,input.raw(),input.code());require(Objects.equals(longOrNull(fields[3]),expected),"Native/model mismatch at row "+index);result.get(name).add(new Row(input.raw(),input.code(),expected));}return result;
    }
    @Test void independentMutableModelCoversCarriersAndEveryContainedRange(){
        assertEquals(2146,inputs.size());var pairs=new HashSet<>(inputs);for(long raw=-256;raw<=511;raw++)assertTrue(pairs.contains(new Input(raw,72)));for(long raw:new long[]{Long.MIN_VALUE,Long.MAX_VALUE})assertTrue(pairs.contains(new Input(raw,72)));
        var moves=new HashSet<Range>();var disjoint=new HashSet<Range>();for(var input:inputs){moves.add(copyRange(input.code()));disjoint.add(disjointRange(input.code()));}assertTrue(moves.containsAll(ranges()));boolean forward=false,backward=false;for(var range:disjoint){if(range.count()>0&&range.from()<range.to())forward=true;if(range.count()>0&&range.from()>range.to())backward=true;assertTrue(range.count()==0||range.from()+range.count()<=range.to()||range.to()+range.count()<=range.from());}assertTrue(forward);assertTrue(backward);
    }
    @Test void independentMutableMovesSnapshotBothDirectionsAndDistinctCopiesAgree(){
        for(var range:List.of(new Range(0,1,7),new Range(1,0,7),new Range(0,0,8),new Range(8,8,0))){var source=new ArrayList<Integer>();for(int i=0;i<8;i++)source.add((127+17*i)%256);var expected=new ArrayList<Integer>();for(int i=0;i<source.size();i++)expected.add(i>=range.to()&&i<range.to()+range.count()?source.get(range.from()+i-range.to()):source.get(i));assertEquals(fingerprint(expected),model("movedBytes",127,range.from()+9L*range.to()+81L*range.count()));}
        for(var input:inputs)assertEquals(model("copiedMutableBytes",input.raw(),input.code()),model("copiedDisjointBytes",input.raw(),input.code()));assertThrows(IllegalArgumentException.class,()->model("unknown",0,0));
    }
    private String text(List<String> rows){return String.join("\n",rows)+"\n";}
    private List<String> replaced(List<String> lines,String first){var result=new ArrayList<>(lines);result.set(0,first);return result;}
    @Test void independentMutableCorpusRejectsMissingDuplicateReorderedAndWrongRows()throws Exception{
        var lines=new ArrayList<String>();for(var name:names)for(var input:inputs)lines.add(name+"\t"+input.raw()+"\t"+input.code()+"\t"+model(name,input.raw(),input.code()));assertEquals(new HashSet<>(names),checkedRows(text(lines)).keySet());var duplicate=new ArrayList<>(lines);duplicate.add(lines.getFirst());var blank=new ArrayList<>(lines);blank.add("");
        for(var bad:List.of(lines.subList(1,lines.size()),duplicate,lines.reversed(),replaced(lines,lines.get(1)),replaced(lines,"unknown\t0\t0\t0"),replaced(lines,lines.getFirst().substring(0,lines.getFirst().lastIndexOf('\t'))+"\t999"),replaced(lines,lines.getFirst().replaceFirst("\t"," ")),blank,List.<String>of()))assertThrows(IllegalArgumentException.class,()->checkedRows(text(bad)));
        checkedRows(Files.readString(new File(root,"build/mutable-bytearrays/oracle.tsv").toPath()));
    }
    private long count(ExecutableProgram p){return ((Number)p.diagnostics().get("compiledEntries")).longValue();}
    private void check(Row row,Value function,String label,Language language){assertEquals(row.expected(),function.execute(row.raw(),row.code()).asLong(),label+"/"+row.raw()+"/"+row.code());released(language);}
    private void verifyNative(boolean inlining)throws Exception{
        var manifest=manifest();ByteArrayFixtureEvidence.verify(root,"mutable-bytearrays",manifest);assertEquals(names,manifest.get("entries"));var wantedInputs=new ArrayList<List<Long>>();for(var input:inputs)wantedInputs.add(List.of(input.raw(),input.code()));assertEquals(wantedInputs,manifest.get("inputs"));var rows=checkedRows(Files.readString(new File(root,"build/mutable-bytearrays/oracle.tsv").toPath()));assertEquals(new HashSet<>(names),rows.keySet());int rowCount=0;for(var list:rows.values())rowCount+=list.size();assertEquals(((Number)manifest.get("nativeRows")).intValue(),rowCount);
        for(var stage:((Map<String,List<String>>)manifest.get("stages")).entrySet())for(var name:names){var cases=Objects.requireNonNull(rows.get(name));var audit=(Map<?,?>)Json.parse(Files.readString(new File(root,"build/mutable-bytearrays/"+stage.getKey()+"-"+name+".audit.json").toPath()));assertEquals(true,audit.get("accepted"));
            for(var backend:List.of("ast","bytecode"))try(var context=context(inlining)){context.initialize("thc");context.enter();try{
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);var module=new LinkedHashMap<>(CoreModules.reachable(merged(stage.getValue()),name));module.put("instrument",true);var p=program(language,module,backend);var function=context.asValue(new EntryValue(p,name,2));var host=p.hostEntryTarget(2);var original=p.entryTarget(name);var label=stage.getKey()+"/"+backend+"/"+name+"/inlining="+inlining;
                for(var row:cases)check(row,function,label,language);var targets=activeTargets(host);assertTrue(targets.size()>1,label);for(var target:targets)if(target!=host)compile(target);assertTrue(function.invokeMember("compile").asBoolean());long allocations=language.getHandoffState().get().getResults().getAllocations();assertEquals(0L,allocations,label+" State-only effects do not allocate result packets");
                for(var row:cases.reversed()){long before=count(p);check(row,function,label,language);assertTrue(count(p)>before,label+" compiled guest");assertEquals(targets,activeTargets(host),label+" active identities");valid(original,label);for(var target:targets)valid(target,label);}assertEquals(allocations,language.getHandoffState().get().getResults().getAllocations(),label);
                for(var counter:List.of("unsupportedTraps","blackholes"))assertEquals(0L,((Number)p.diagnostics().get(counter)).longValue(),label);System.out.println("MutableByteArray PASS "+label+" rows="+cases.size());
            }finally{context.leave();}}
        }
    }
    @Test void mutableFixtureEvidenceRejectsMissingAndChangedProvenance()throws Exception{ByteArrayFixtureEvidence.rejectionControls(root,"mutable-bytearrays");}
    private byte[] bytes(int seed){var result=new byte[8];for(int i=0;i<8;i++)result[i]=(byte)(seed+17*i);return result;}
    private boolean overlaps(int a,int b,int n){return n>0&&a<b+n&&b<a+n;}
    private List<Range> ranges(){var result=new ArrayList<Range>();for(int a=0;a<=8;a++)for(int b=0;b<=8;b++)for(int n=0;n<=Math.min(8-a,8-b);n++)result.add(new Range(a,b,n));return result;}
    private long[][] invalidRanges(){return new long[][]{{-1,0},{Long.MIN_VALUE,0},{9,0},{1L<<32,0},{Long.MAX_VALUE,0},{0,-1},{0,Long.MIN_VALUE},{0,Long.MAX_VALUE},{1,Long.MAX_VALUE},{8,1}};}
    @Test void fullWidthDomainsFillTruncationAndOverlapPoliciesPreserveStorage(){
        for(int start=0;start<=8;start++)for(int count=0;count<=8-start;count++)for(long value:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,-257,-256,-1,0,127,128,255,256,511,1L<<40}){var array=bytes(0);var alias=array;var expected=array.clone();for(int i=start;i<start+count;i++)expected[i]=(byte)(value&255);ManagedByteArray.fill(array,start,count,value);assertSame(alias,array);assertArrayEquals(expected,array);}
        for(var range:ranges())for(boolean same:new boolean[]{false,true})for(boolean nonOverlapping:new boolean[]{false,true}){
            var source=bytes(0);var destination=same?source:bytes(101);var alias=destination;var sourceBefore=source.clone();var before=destination.clone();
            if(same&&nonOverlapping&&overlaps(range.from(),range.to(),range.count())){assertThrows(RuntimeFault.class,()->ManagedByteArray.copyMutable(source,range.from(),destination,range.to(),range.count(),true));assertArrayEquals(before,destination);}
            else{var expected=before.clone();for(int i=0;i<range.count();i++)expected[range.to()+i]=sourceBefore[range.from()+i];ManagedByteArray.copyMutable(source,range.from(),destination,range.to(),range.count(),nonOverlapping);assertSame(alias,destination);assertArrayEquals(expected,destination);if(!same)assertArrayEquals(sourceBefore,source);}
        }
        for(var range:invalidRanges()){long offset=range[0],count=range[1];var source=bytes(0);var destination=bytes(101);var before=destination.clone();assertThrows(RuntimeFault.class,()->ManagedByteArray.fill(destination,offset,count,255));assertArrayEquals(before,destination);for(boolean nonOverlapping:new boolean[]{false,true}){assertThrows(RuntimeFault.class,()->ManagedByteArray.copyMutable(source,offset,destination,0,count,nonOverlapping));assertArrayEquals(before,destination);assertThrows(RuntimeFault.class,()->ManagedByteArray.copyMutable(source,0,destination,offset,count,nonOverlapping));assertArrayEquals(before,destination);}}
        var array=bytes(0);
        // Existing immutable/mutable copy stays stricter than either new operation.
        for(long n:new long[]{0,1})assertThrows(RuntimeFault.class,()->ManagedByteArray.copy(array,0,array,4,n));assertArrayEquals(bytes(0),array);
    }
    private Expr operand(String name,Object value,List<String> events){return new Expr(){@Override public Object execute(VirtualFrame frame){events.add(name);return value;}};}
    @Test void allOperandsAndStateAreEvaluatedBeforeMutation(){
        var frame=Truffle.getRuntime().createVirtualFrame(new Object[0],FrameDescriptor.newBuilder().build());
        for(var operation:operations)for(boolean fail:new boolean[]{false,true}){var source=bytes(0);var destination=bytes(101);var before=destination.clone();var events=new ArrayList<String>();var state=new Expr(){@Override public Object execute(VirtualFrame f){events.add("state");assertArrayEquals(before,destination);if(fail)throw new RuntimeFault("State failed before mutation");return Unit.INSTANCE;}};
            var operands=operation==ByteArrayOp.SET?new Expr[]{operand("destination",destination,events),operand("offset",1L,events),operand("count",3L,events),operand("value",511L,events),state}:new Expr[]{operand("source",source,events),operand("sourceOffset",0L,events),operand("destination",destination,events),operand("destinationOffset",1L,events),operand("count",3L,events),state};var expression=ByteArrayOp.expression(operation,CoreRepresentation.Companion.getUNKNOWN(),operands);
            if(fail){assertThrows(RuntimeFault.class,()->expression.execute(frame));assertArrayEquals(before,destination);}else{assertSame(Unit.INSTANCE,expression.execute(frame));var expected=before.clone();for(int i=0;i<=2;i++)expected[i+1]=operation==ByteArrayOp.SET?(byte)(511&255):source[i];assertArrayEquals(expected,destination);}assertEquals(operation==ByteArrayOp.SET?List.of("destination","offset","count","value","state"):List.of("source","sourceOffset","destination","destinationOffset","count","state"),events);
        }
    }
    private Map<String,Object> synthetic(ByteArrayOp operation){
        var array=Map.of("kind","object","primReps",List.of("BoxedRep (Just Unlifted)"),"evaluated",true);var number=Map.of("kind","long","primReps",List.of("IntRep"),"evaluated",true);var state=Map.of("kind","void","primReps",List.of(),"evaluated",true);var closure=Map.of("kind","closure","primReps",List.of("BoxedRep (Just Lifted)"),"evaluated",true);var proofs=operation==ByteArrayOp.SET?List.of(array,number,number,number,state):List.of(array,number,array,number,number,state);var params=new ArrayList<Map<String,Object>>();var args=new ArrayList<Object>();for(int i=0;i<proofs.size();i++){params.add(Map.of("id","p"+i,"lifted",false,"rep",proofs.get(i)));args.add(List.of("var","p"+i,Map.of("rep",proofs.get(i))));}var app=List.of("app",List.of("prim",operation.getPrimitive()),args,Collections.nCopies(params.size(),false),false,false,Map.of("rep",state));
        var body=List.of("case",app,"state",List.of(Arrays.asList("default",null,List.of(),List.of("lit","int","17",Map.of("rep",number)))),Map.of("rep",number,"binder",Map.of("id","state","lifted",false,"rep",state)));
        return Map.of("instrument",true,"constructors",List.of(),"bindings",List.of(Map.of("id","mutate","name","mutate","arity",params.size(),"lifted",true,"rep",closure,"expr",List.of("lam",params,body,Map.of("rep",closure,"resultRep",number)))));
    }
    private Object call(RootCallTarget entry,ByteArrayOp op,Object source,long from,Object destination,long to,long count,long value,Object state){return Calls.target(entry,op==ByteArrayOp.SET?new Object[]{0L,destination,to,count,value,state}:new Object[]{0L,source,from,destination,to,count,state});}
    private Object call(RootCallTarget entry,ByteArrayOp op,Object source,long from,Object destination,long to,long count){return call(entry,op,source,from,destination,to,count,511,Unit.INSTANCE);}
    private void positive(boolean compiled,ByteArrayOp op,ExecutableProgram p,RootCallTarget entry,Language language,String backend)throws Exception{
        for(var range:ranges())for(boolean same:new boolean[]{false,true}){if(op==ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING&&same&&overlaps(range.from(),range.to(),range.count()))continue;var source=bytes(0);var destination=same?source:bytes(101);var sourceBefore=source.clone();var expected=destination.clone();for(int i=0;i<range.count();i++)expected[range.to()+i]=op==ByteArrayOp.SET?(byte)(511&255):sourceBefore[range.from()+i];long before=count(p);assertEquals(17L,call(entry,op,source,range.from(),destination,range.to(),range.count()));assertArrayEquals(expected,destination);if(!same)assertArrayEquals(sourceBefore,source);if(compiled){assertEquals(before+1,count(p));valid(entry,backend+"/"+op);}released(language);}
    }
    @Test void compiledTypedBackendsMutateExactBytesAndGuardStateBoundsAndOverlap()throws Exception{
        for(var backend:List.of("ast","bytecode"))for(var operation:operations)try(var context=context(true)){context.initialize("thc");context.enter();try{
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);var p=program(language,synthetic(operation),backend);var entry=p.entryTarget("mutate");positive(false,operation,p,entry,language,backend);compile(entry);positive(true,operation,p,entry,language,backend);
            for(var range:invalidRanges()){long offset=range[0],length=range[1];var source=bytes(0);var destination=bytes(101);var before=destination.clone();assertThrows(RuntimeFault.class,()->call(entry,operation,source,0,destination,offset,length));assertArrayEquals(before,destination);if(operation!=ByteArrayOp.SET){assertThrows(RuntimeFault.class,()->call(entry,operation,source,offset,destination,0,length));assertArrayEquals(before,destination);}}
            var source=bytes(0);var destination=bytes(101);var before=destination.clone();var failure=assertThrows(RuntimeFault.class,()->call(entry,operation,source,Long.MAX_VALUE,destination,Long.MAX_VALUE,Long.MAX_VALUE,511,17L));assertTrue(Objects.toString(failure.getMessage(),"").contains("zero-width scalar carrier"),failure.getMessage());assertArrayEquals(before,destination);
            if(operation==ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING)for(var range:List.of(new Range(0,1,7),new Range(1,0,7),new Range(0,0,8))){var array=bytes(0);assertThrows(RuntimeFault.class,()->call(entry,operation,array,range.from(),array,range.to(),range.count()));assertArrayEquals(bytes(0),array);}
            for(var bad:List.of(new Object(),new Object[]{1})){assertThrows(RuntimeFault.class,()->call(entry,operation,source,0,bad,0,0));if(operation!=ByteArrayOp.SET)assertThrows(RuntimeFault.class,()->call(entry,operation,bad,0,destination,0,0));}released(language);
        }finally{context.leave();}}
    }
    private List<List<Object>> applications(Object value){var result=new ArrayList<List<Object>>();if(value instanceof List<?> list){if(!list.isEmpty()&&"app".equals(list.getFirst()))result.add((List<Object>)list);for(var item:list)result.addAll(applications(item));}else if(value instanceof Map<?,?> map)for(var item:map.values())result.addAll(applications(item));return result;}
    private List<Object> application(Object module,ByteArrayOp op){for(var app:applications(module)){var function=(List<?>)app.get(1);if(function.subList(0,Math.min(2,function.size())).equals(List.of("prim",op.getPrimitive())))return app;}throw new NoSuchElementException();}
    @Test void exactArityIntFillStateAndUnliftedReferenceProofsAreRequired()throws Exception{
        var paths=Objects.requireNonNull(((Map<String,List<String>>)manifest().get("stages")).get("pre"));
        for(var backend:List.of("ast","bytecode"))try(var context=context(true)){context.initialize("thc");context.enter();try{
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for(var operation:operations){var name=operation==ByteArrayOp.SET?"filledBytes":operation==ByteArrayOp.COPY_MUTABLE?"movedBytes":"disjointBytes";
                for(int mutation=0;mutation<=12;mutation++)for(boolean diagnostic:new boolean[]{false,true}){
                    var module=CoreModules.reachable(merged(paths),name);var app=application(module,operation);var args=(List<Object>)app.get(2);var flags=(List<Object>)app.get(3);var meta=CoreRepresentations.INSTANCE.metadata(app);
                    switch(mutation){case 0->{args.removeLast();flags.removeLast();meta.remove("callDemand");}case 1->{args.add(args.getFirst());flags.add(false);meta.remove("callDemand");}case 2->meta.remove("rep");case 3->flags.set(0,true);case 4->((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.getFirst()).get("rep")).put("primReps",List.of("BoxedRep (Just Lifted)"));case 5,6,7->{int index=operation==ByteArrayOp.SET?3:4;((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.get(index)).get("rep")).put("primReps",List.of(mutation==5?"Word8Rep":mutation==6?"WordRep":"Int64Rep"));}case 8->meta.put("rep",Map.of("kind","unknown","aggregate","unboxed-tuple","components",List.of(),"primReps",List.of(),"evaluated",true));case 9->CoreRepresentations.INSTANCE.metadata((List<Object>)args.getLast()).put("rep",Map.of("kind","unknown","aggregate","unboxed-tuple","components",List.of(),"primReps",List.of(),"evaluated",true));case 12->{int index=operation==ByteArrayOp.SET?0:2;((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.get(index)).get("rep")).put("primReps",List.of("BoxedRep (Just Lifted)"));}case 10,11->{int index=mutation==10?1:operation==ByteArrayOp.SET?2:3;((Map<String,Object>)CoreRepresentations.INSTANCE.metadata((List<Object>)args.get(index)).get("rep")).put("primReps",List.of("WordRep"));}}
                    var configured=new LinkedHashMap<>(module);configured.put("diagnosticUnsupported",diagnostic);var label=operation+"/"+mutation+"/"+backend+"/"+diagnostic;
                    if(mutation>=5&&mutation<=7||mutation>=10&&mutation<=11)assertDoesNotThrow(()->program(language,configured,backend),label);else assertThrows(RuntimeFault.class,()->program(language,configured,backend),label);
                }
                var module=CoreModules.reachable(merged(paths),name);var app=application(module,operation);var primitive=new ArrayList<>((List<?>)app.get(1));app.clear();app.addAll(primitive);assertThrows(UnsupportedCore.class,()->program(language,module,backend));
            }
        }finally{context.leave();}}
    }
}
