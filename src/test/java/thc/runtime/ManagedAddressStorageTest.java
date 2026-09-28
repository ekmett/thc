// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import thc.Language;
import static thc.PrimopTestContextKt.primopTestContext;
import static org.junit.jupiter.api.Assertions.*;

class ManagedAddressStorageTest {
    private record ExpectedAddress(ManagedAddress address,long expected) {}
    @Test void firstInstalledByteReadsKeepLiteralAndMutableStorageDistinct() throws Exception {
        try(var context=primopTestContext()){context.initialize("thc");context.enter();try{
            var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var root=new RootNode(language){long compiledEntries;@Override public Object execute(VirtualFrame frame){if(CompilerDirectives.inCompiledCode())compiledEntries++;return ((ManagedAddress)frame.getArguments()[0]).readWord8((Long)frame.getArguments()[1]);}};
            var bytes=new byte[]{0,127,(byte)128,(byte)255};var mutable=ManagedAddress.fromByteArray(bytes);var literal=ManagedAddress.fromHex("007f80ff");var target=root.getCallTarget();
            for(var address:List.of(literal,mutable))for(int index=0;index<bytes.length;index++)assertEquals(bytes[index]&255L,target.call(address,(long)index));
            target.getClass().getMethod("compile",boolean.class).invoke(target,true);assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target));
            var runtime=Truffle.getRuntime();runtime.getClass().getMethod("bypassedInstalledCode",Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime,target);bytes[2]=(byte)0xa5;
            for(var row:List.of(new ExpectedAddress(mutable,165L),new ExpectedAddress(literal,128L))){long before=root.compiledEntries;assertEquals(row.expected(),target.call(row.address(),2L));assertEquals(before+1,root.compiledEntries,"first installed byte read");assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target));}
            assertThrows(RuntimeFault.class,()->target.call(ManagedAddress.nullAddress(),0L));assertThrows(RuntimeFault.class,()->target.call(mutable,4L));assertArrayEquals(new byte[]{0,127,(byte)0xa5,(byte)255},bytes);
        }finally{context.leave();}}
    }
    private Integer position(int size,int base,long displacement,boolean onePast){var target=BigInteger.valueOf(base).add(BigInteger.valueOf(displacement));var limit=BigInteger.valueOf(size);return target.signum()<0||target.compareTo(limit)>0||(!onePast&&target.equals(limit))?null:target.intValueExact();}
    @Test void completeRangesRejectFullWidthOverflowBeforeEffects(){
        for(int size:new int[]{0,1,4,16})for(int base=0;base<=size;base++){
            var bytes=new byte[size];for(int i=0;i<size;i++)bytes[i]=(byte)(i+0x80);var before=bytes.clone();var address=ManagedAddress.fromByteArray(bytes).plus(base);
            var displacements=new long[]{Long.MIN_VALUE,Long.MAX_VALUE,-(long)base-1,-(long)base,-1L,0L,1L,(long)size-base-1,(long)size-base,(long)size-base+1};
            var counts=new long[]{Long.MIN_VALUE,Long.MAX_VALUE,(long)Integer.MAX_VALUE+1,-1L,0L,1L,2L,size,(long)size+1};
            for(long displacement:displacements)for(long count:counts){var start=BigInteger.valueOf(base).add(BigInteger.valueOf(displacement));var end=start.add(BigInteger.valueOf(count));boolean valid=start.signum()>=0&&start.compareTo(BigInteger.valueOf(size))<=0&&count>=0&&end.compareTo(BigInteger.valueOf(size))<=0;
                var label="size="+size+"/base="+base+"/displacement="+displacement+"/count="+count;for(boolean writable:new boolean[]{false,true}){if(valid)address.requireRange(displacement,count,writable);else assertThrows(RuntimeFault.class,()->address.requireRange(displacement,count,writable),label);assertArrayEquals(before,bytes,label);}}
        }
        var literal=ManagedAddress.fromHex("41");literal.requireRange(0,2,false);literal.requireRange(2,0,false);assertThrows(RuntimeFault.class,()->literal.requireRange(0,0,true));assertThrows(RuntimeFault.class,()->literal.requireRange(2,0,true));
    }
    @Test void overlapUsesBackingIdentityAndExactNonemptyRegions(){
        var bytes=new byte[16];var first=ManagedAddress.fromByteArray(bytes);var alias=ManagedAddress.fromByteArray(ManagedByteArray.freeze(bytes)).plus(8);var distinct=ManagedAddress.fromByteArray(bytes.clone());
        assertTrue(first.overlaps(0,16,alias,-8,16));assertTrue(first.overlaps(4,5,alias,0,1));assertFalse(first.overlaps(4,4,alias,0,1));assertFalse(first.overlaps(0,16,alias,8,0));assertFalse(first.overlaps(16,0,alias,-8,16));assertFalse(first.overlaps(0,16,distinct,0,16));
        assertThrows(RuntimeFault.class,()->first.overlaps(0,Long.MAX_VALUE,alias,0,1));assertThrows(RuntimeFault.class,()->first.overlaps(0,1,alias,Long.MIN_VALUE,0));
        var literal=ManagedAddress.fromHex("41ff");assertTrue(literal.overlaps(1,2,literal.plus(2),-1,2));assertFalse(literal.overlaps(0,3,ManagedAddress.fromHex("41ff"),0,3));assertFalse(first.overlaps(0,3,literal,0,3));
    }
    @Test void fullWidthBoundsMatchIndependentArithmeticAndFailedWritesHaveNoEffects(){
        var displacements=new ArrayList<Long>();for(long d=-34;d<=34;d++)displacements.add(d);displacements.addAll(List.of(Long.MIN_VALUE,Long.MIN_VALUE+1,Long.MAX_VALUE-1,Long.MAX_VALUE,(long)Integer.MIN_VALUE-1,(long)Integer.MIN_VALUE,(long)Integer.MAX_VALUE,(long)Integer.MAX_VALUE+1,-(1L<<32),(1L<<32)-1,1L<<32,(1L<<32)+1));
        for(int size=0;size<=32;size++)for(int base=0;base<=size;base++)for(long displacement:displacements){
            var bytes=new byte[size];for(int i=0;i<size;i++)bytes[i]=(byte)(i*73+size*17+129);var before=bytes.clone();var address=ManagedAddress.fromByteArray(bytes).plus(base);var label="size="+size+"/base="+base+"/displacement="+displacement;
            var sum=BigInteger.valueOf(base).add(BigInteger.valueOf(displacement));if(sum.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||sum.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)assertThrows(RuntimeFault.class,()->address.plus(displacement),label);
            else{var shifted=address.plus(displacement);if(displacement==0L)assertSame(address,shifted,label);var arithmetic=position(size,base,displacement,false);if(arithmetic!=null)assertEquals(before[arithmetic]&255L,shifted.readWord8(0),label);else assertThrows(RuntimeFault.class,()->shifted.readWord8(0),"outside "+label);
                if(size>0&&!sum.equals(BigInteger.valueOf(Long.MIN_VALUE)))assertEquals(before[0]&255L,shifted.readWord8(-sum.longValue()),label);}
            assertArrayEquals(before,bytes,"arithmetic preserved backing "+label);var access=position(size,base,displacement,false);
            if(access==null){assertThrows(RuntimeFault.class,()->address.indexChar(displacement),label);assertThrows(RuntimeFault.class,()->address.readWord8(displacement),label);assertThrows(RuntimeFault.class,()->address.writeWord8(displacement,-1L),label);assertArrayEquals(before,bytes,"invalid access preserved every byte "+label);}
            else{long expected=before[access]&255L;assertEquals(expected,address.indexChar(displacement),label);assertEquals(expected,address.readWord8(displacement),label);assertArrayEquals(before,bytes,"read preserved backing "+label);var after=before.clone();after[access]=(byte)0xa5;address.writeWord8(displacement,0x1234_56a5L);assertArrayEquals(after,bytes,"only the selected byte changed "+label);}
        }
    }
    @Test void aliasesIncludingUnsafeFrozenArraysSeeEveryWriteAndUnsignedByte(){
        var bytes=new byte[258];Arrays.fill(bytes,(byte)0x5a);var original=ManagedAddress.fromByteArray(bytes);var frozen=ManagedByteArray.freeze(bytes);assertSame(bytes,frozen);var frozenAddress=ManagedAddress.fromByteArray(frozen);var middle=original.plus(129);var end=frozenAddress.plus(bytes.length);
        for(int value=0;value<=255;value++){original.writeWord8((long)value+1,value);assertEquals((byte)value,bytes[value+1]);assertEquals((long)value,frozenAddress.readWord8((long)value+1));assertEquals((long)value,middle.indexChar((long)value-128));assertEquals((long)value,end.readWord8((long)value-257));}
        assertEquals((byte)0x5a,bytes[0]);assertEquals((byte)0x5a,bytes[bytes.length-1]);
        for(long value:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,-257L,-256L,-129L,-128L,-1L,0L,255L,256L,511L}){var before=bytes.clone();frozenAddress.writeWord8(128,value);var expected=before.clone();expected[128]=(byte)value;assertArrayEquals(expected,bytes);assertEquals(value&255L,original.readWord8(128));assertEquals(value&255L,middle.readWord8(-1));bytes[128]=(byte)~value;assertEquals(~value&255L,frozenAddress.indexChar(128),"array writes remain visible");}
    }
    @Test void literalRawBytesAndTrailingNulStayImmutableThroughEveryDerivedAddress(){
        var literal=ManagedAddress.fromHex("00807fFf41");var expected=new long[]{0,128,127,255,65,0};
        for(int base=0;base<=expected.length;base++){var address=literal.plus(base);for(int index=0;index<expected.length;index++){long displacement=(long)index-base;assertEquals(expected[index],address.indexChar(displacement));assertEquals(expected[index],address.readWord8(displacement));var failure=assertThrows(RuntimeFault.class,()->address.writeWord8(displacement,0xaa));assertTrue(Objects.requireNonNull(failure.getMessage()).contains("immutable literal"));assertEquals(expected[index],literal.readWord8(index));}
            for(long displacement:new long[]{-(long)base-1,(long)expected.length-base,Long.MIN_VALUE,Long.MAX_VALUE}){assertThrows(RuntimeFault.class,()->address.readWord8(displacement));assertThrows(RuntimeFault.class,()->address.indexChar(displacement));assertThrows(RuntimeFault.class,()->address.writeWord8(displacement,-1));}}
        assertThrows(RuntimeFault.class,()->literal.plus(-1).readWord8(0));assertThrows(RuntimeFault.class,()->literal.plus((long)expected.length+1).readWord8(0));for(var malformed:List.of("0","000","xz","0G","-1"," 0","é0"))assertThrows(RuntimeFault.class,()->ManagedAddress.fromHex(malformed),malformed);
        var emptyLiteral=ManagedAddress.fromHex("");assertEquals(0L,emptyLiteral.readWord8(0),"even an empty literal owns a NUL");assertThrows(RuntimeFault.class,()->emptyLiteral.plus(1).readWord8(0));
        var emptyArray=ManagedAddress.fromByteArray(new byte[0]);assertSame(emptyArray,emptyArray.plus(0));assertThrows(RuntimeFault.class,()->emptyArray.readWord8(0));assertThrows(RuntimeFault.class,()->emptyArray.writeWord8(0,0));assertThrows(RuntimeFault.class,()->emptyArray.plus(1).readWord8(0));assertThrows(RuntimeFault.class,()->emptyArray.plus(-1).readWord8(0));
    }
    @Test void outOfRangeSentinelsRetainIdentityButGrantNoMemoryAccess(){
        var base=ManagedAddress.fromByteArray(new byte[]{65,0});var before=base.plus(-1);var after=base.plus(3);assertEquals(-1L,before.difference(base));assertTrue(before.plus(1).sameLocation(base));assertEquals(65L,before.readWord8(1));
        for(var address:List.of(before,after,base.plus(Long.MIN_VALUE),base.plus(Long.MAX_VALUE),base.plus(1L<<32))){assertThrows(RuntimeFault.class,()->address.readWord8(0));assertThrows(RuntimeFault.class,()->address.writeWord8(0,12));assertThrows(RuntimeFault.class,()->address.requireRange(0,0,false));assertThrows(RuntimeFault.class,address::cStringLength);assertThrows(RuntimeFault.class,address::utf8);assertThrows(RuntimeFault.class,()->address.copyNonOverlappingTo(base,0));}
        assertThrows(RuntimeFault.class,()->before.plus(Long.MIN_VALUE));assertThrows(RuntimeFault.class,()->base.plus(Long.MAX_VALUE).plus(1));assertThrows(RuntimeFault.class,()->base.plus(Long.MIN_VALUE).readWord8(Long.MIN_VALUE));assertEquals(65L,base.readWord8(0));
    }
    @Test void finalBackingReferencesSeparateMutableStorageFromLiteralCompilationConstants()throws Exception{
        var type=ManagedAddress.class;assertTrue(Modifier.isFinal(type.getModifiers()));var fields=new ArrayList<java.lang.reflect.Field>();for(var field:type.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()))fields.add(field);
        boolean immutable=true;for(var field:fields)immutable&=Modifier.isPrivate(field.getModifiers())&&Modifier.isFinal(field.getModifiers());assertTrue(immutable);
        // New address capabilities may add final ownership fields. Only literal contents may be treated as compilation constants.
        var constants=new HashSet<String>();for(var field:fields)if(field.isAnnotationPresent(CompilationFinal.class))constants.add(field.getName());assertEquals(Set.of("literalBytes"),constants);
        var literalField=type.getDeclaredField("literalBytes");literalField.setAccessible(true);var mutableField=type.getDeclaredField("mutableBytes");mutableField.setAccessible(true);var ownerField=type.getDeclaredField("owner");ownerField.setAccessible(true);
        assertTrue(fields.contains(literalField));assertTrue(fields.contains(mutableField));assertTrue(fields.contains(ownerField));assertEquals(byte[].class,literalField.getType());assertEquals(byte[].class,mutableField.getType());assertEquals(ManagedAllocation.class,ownerField.getType());
        assertEquals(1,literalField.getAnnotation(CompilationFinal.class).dimensions());assertNull(mutableField.getAnnotation(CompilationFinal.class));assertNull(ownerField.getAnnotation(CompilationFinal.class));var bytes=new byte[8];
        // No root-address reference is retained here: the derived address owns the lifetime.
        var derived=ManagedAddress.fromByteArray(bytes).plus(8).plus(-3);assertSame(bytes,mutableField.get(derived));assertNull(literalField.get(derived));var literal=ManagedAddress.fromHex("ff");var shiftedLiteral=literal.plus(1);
        assertNull(mutableField.get(literal));assertNull(mutableField.get(shiftedLiteral));assertSame(literalField.get(literal),literalField.get(shiftedLiteral));assertNotSame(bytes,literalField.get(literal));
        var allocation=ManagedAllocation.mutable(16,8);var pinned=ManagedAddress.fromAllocation(allocation).plus(8);assertSame(allocation,ownerField.get(pinned));assertNull(literalField.get(pinned));assertNull(mutableField.get(pinned));
    }
}
