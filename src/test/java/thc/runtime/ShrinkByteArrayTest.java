// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.DoubleVector;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidBufferOffsetException;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ShrinkByteArrayTest {
    private final File root=new File(System.getProperty("thc.projectRoot")),directory=new File(root,"build/shrink-bytearrays");
    private final List<String> names=List.of("shrinkBytes","shrinkPinned");
    private Map<String,Object> json(File file)throws Exception{return (Map<String,Object>)Json.parse(Files.readString(file.toPath()));}
    private String digest(File file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));}
    private Context context(){return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build();}
    private record Row(String name,long seed,long size,long answer){}
    private long model(String name,long seed,long size){var bytes=new ArrayList<Long>();for(int i=0;i<(int)size;i++)bytes.add((seed+17L*i)&255L);long value;if(name.equals("shrinkPinned"))value=bytes.getFirst();else{value=0;for(int i=0;i<bytes.size();i++)value+=bytes.get(i)*(i+1);}return size*256+value;}
    private List<Row> rows()throws Exception{var expected=new ArrayList<Row>();for(var name:names)for(long seed:new long[]{-7L,0L,1L,42L,255L}){var sizes=new ArrayList<Long>();for(long n=name.equals("shrinkPinned")?1:0;n<=2;n++)sizes.add(n);sizes.addAll(List.of(7L,16L));for(long size:sizes)expected.add(new Row(name,seed,size,model(name,seed,size)));}
        var actual=new ArrayList<Row>();for(var line:Files.readAllLines(new File(directory,"oracle.tsv").toPath())){var fields=line.split("\t",-1);assertEquals(4,fields.length);actual.add(new Row(fields[0],Long.parseLong(fields[1]),Long.parseLong(fields[2]),Long.parseLong(fields[3])));}assertEquals(expected,actual,"Native shrink/model inventory");return actual;}
    @Test void originalNativeShrinkRunsInBothBackendsAndCompiledCode()throws Exception{
        var manifest=json(new File(directory,"manifest.json"));assertEquals(1L,manifest.get("schema"));assertEquals("9.14.1",manifest.get("ghc"));assertEquals(names,manifest.get("entries"));for(var kind:List.of("inputHashes","artifactHashes"))for(var item:((Map<String,String>)manifest.get(kind)).entrySet())assertEquals(item.getValue(),digest(new File(root,item.getKey())),"Stale shrink "+kind+": "+item.getKey());var cases=rows();assertEquals((long)cases.size(),((Number)manifest.get("nativeRows")).longValue());
        for(var stageEntry:((Map<String,List<String>>)manifest.get("stages")).entrySet()){var stage=stageEntry.getKey();var modules=new ArrayList<Map<String,Object>>();for(var path:stageEntry.getValue())modules.add(json(new File(root,path)));var merged=CoreModules.merge(modules);
            for(var name:names){var audit=json(new File(directory,stage+"/"+name+".audit.json"));assertEquals(true,audit.get("accepted"),stage+"/"+name);assertEquals(List.of(),audit.get("issues"));assertEquals(List.of(),audit.get("missingGlobals"));boolean contains=false;for(var primitive:(List<Map<String,Object>>)audit.get("primitives"))contains|="shrinkMutableByteArray#".equals(primitive.get("name"));assertTrue(contains);
                for(var backend:List.of("ast","bytecode"))try(var context=context()){context.initialize("thc");context.enter();try{var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);var linked=new LinkedHashMap<>(CoreModules.reachable(merged,name));linked.put("instrument",true);ExecutableProgram program=backend.equals("ast")?new Program(language,linked):new BytecodeProgram(language,linked);var host=program.hostEntryTarget(2);var entry=context.asValue(new EntryValue(program,name,2));var selected=new ArrayList<Row>();for(var row:cases)if(row.name().equals(name))selected.add(row);
                    for(var row:selected)assertEquals(row.answer(),entry.execute(row.seed(),row.size()).asLong(),stage+"/"+backend+"/"+name+"("+row.seed()+","+row.size()+")");assertTrue(entry.invokeMember("compile").asBoolean(),stage+"/"+backend+"/"+name+" install");
                    for(var row:selected.reversed()){long before=((Number)program.diagnostics().get("compiledEntries")).longValue();assertEquals(row.answer(),entry.execute(row.seed(),row.size()).asLong());assertTrue(((Number)program.diagnostics().get("compiledEntries")).longValue()>before,stage+"/"+backend+"/"+name+"("+row.seed()+","+row.size()+") compiled");assertEquals(true,host.getClass().getMethod("isValidLastTier").invoke(host));}
                    assertEquals(0L,((Number)program.diagnostics().get("unsupportedTraps")).longValue());assertEquals(0,language.getHandoffState().get().getResults().getDepth());
                }finally{context.leave();}}
            }
        }
    }
    @Test void shrinkingPreservesAliasesLogicalBoundsAndPointerCellSafety()throws Exception{
        var owner=ManagedByteArray.allocateGuest(32);var alias=ManagedAddress.Companion.fromAllocation(owner);for(long index=0;index<32;index++)owner.writeByte(index,index);owner.shrink(16);assertEquals(16L,ManagedByteArray.sizeGuest(owner));assertSame(owner,ManagedByteArray.freezeGuest(owner));assertEquals(15L,alias.readWord8(15));assertEquals(15L,(long)ManagedByteArray.readGuest(owner,15,true));assertDoesNotThrow(()->ManagedByteArray.readIntGuest(owner,1));assertThrows(RuntimeFault.class,()->ManagedByteArray.readIntGuest(owner,2));assertThrows(RuntimeFault.class,()->alias.readWord8(16));assertThrows(RuntimeFault.class,()->owner.writeByte(16,1));for(long length:new long[]{-1L,17L,Long.MAX_VALUE})assertThrows(RuntimeFault.class,()->owner.shrink(length));assertEquals(16L,owner.getSize());assertEquals(15L,alias.readWord8(15));var raw=new byte[16];assertThrows(RuntimeFault.class,()->ManagedByteArray.shrinkGuest(raw,8));assertEquals(16,raw.length);owner.shrink(0);assertEquals(0L,owner.getSize());assertThrows(RuntimeFault.class,()->alias.readWord8(0));
        var exposed=ManagedByteArray.allocateGuest(32);var backing=exposed.rawBytesIfPointerFree();exposed.shrink(16);assertSame(backing,exposed.rawBytesIfPointerFree());assertEquals(16L,exposed.getSize());var buffer=new CbitsBuffer(backing,true,exposed::getSize);var interop=InteropLibrary.getUncached();assertEquals(16L,interop.getBufferSize(buffer));assertThrows(InvalidBufferOffsetException.class,()->interop.readBufferByte(buffer,16));
        var pointers=ManagedByteArray.allocateGuest(24);var address=ManagedAddress.Companion.fromAllocation(pointers);pointers.writeAddressByteOffset(8,address);pointers.writeAddressByteOffset(16,address);assertThrows(RuntimeFault.class,()->pointers.shrink(20));assertEquals(24L,pointers.getSize());assertSame(address,pointers.readAddressByteOffset(16));pointers.shrink(16);assertSame(address,pointers.readAddressByteOffset(8));assertThrows(RuntimeFault.class,()->pointers.readAddressByteOffset(16));
    }
    private void check(Object expected,BiFunction<Object,Long,Object> read,BiConsumer<Object,Long> write){var owner=ManagedByteArray.allocateGuest(32);write.accept(owner,0L);owner.shrink(16);assertEquals(expected,read.apply(owner,0L));
        // The raw path still checks physical bounds; the owned path must reject the same range even with twice the backing capacity.
        for(var storage:List.of(owner,new byte[16])){write.accept(storage,0L);for(long index:new long[]{-1L,1L,Long.MAX_VALUE}){assertThrows(RuntimeFault.class,()->read.apply(storage,index));assertThrows(RuntimeFault.class,()->write.accept(storage,index));assertFalse(Thread.holdsLock(storage));assertEquals(expected,read.apply(storage,0L));}}}
    @Test void vectorsObserveShrunkBoundsWithoutLosingValidPrefix(){
        var ints=IntVector.broadcast(IntVector.SPECIES_128,1).withLane(1,2).withLane(2,3).withLane(3,4);var floats=FloatVector.broadcast(FloatVector.SPECIES_128,1.5f);var doubles=DoubleVector.broadcast(DoubleVector.SPECIES_128,-2.5);
        for(boolean scalarOffset:new boolean[]{false,true}){check(ints,(bytes,index)->ManagedByteArray.readInt32VectorGuest(bytes,index,scalarOffset),(bytes,index)->ManagedByteArray.writeInt32VectorGuest(bytes,index,ints,scalarOffset));check(ints,(bytes,index)->ManagedByteArray.readWord32VectorGuest(bytes,index,scalarOffset),(bytes,index)->ManagedByteArray.writeWord32VectorGuest(bytes,index,ints,scalarOffset));check(floats,(bytes,index)->ManagedByteArray.readFloatVectorGuest(bytes,index,scalarOffset),(bytes,index)->ManagedByteArray.writeFloatVectorGuest(bytes,index,floats,scalarOffset));check(doubles,(bytes,index)->ManagedByteArray.readDoubleVectorGuest(bytes,index,scalarOffset),(bytes,index)->ManagedByteArray.writeDoubleVectorGuest(bytes,index,doubles,scalarOffset));}
    }
}
