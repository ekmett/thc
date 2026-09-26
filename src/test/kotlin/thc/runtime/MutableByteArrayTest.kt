// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

class MutableByteArrayTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private val operations=listOf(ByteArrayOp.SET,ByteArrayOp.COPY_MUTABLE,ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING)
    private val names=listOf("filledBytes","movedBytes","disjointBytes","copiedMutableBytes","copiedDisjointBytes","publicReplicate")
    private fun context(inlining: Boolean=true)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun manifest()=Json.parse(File(root,"build/mutable-bytearrays/manifest.json").readText()) as Map<String,Any?>
    private fun merged(paths: List<String>)=CoreModules.merge(paths.map { Json.parse(File(root,it).readText()) as Map<String,Any?> })
    private fun program(language: Language,module: Map<String,Any?>,backend: String): ExecutableProgram=
        if(backend=="ast") Program(language,module) else BytecodeProgram(language,module)
    private fun valid(target: RootCallTarget,label: String)=assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target),label)
    private fun compile(target: RootCallTarget) { target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true);valid(target,"installed") }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen=Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>());val result=mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if(!seen.add(target))return
            val root=target.rootNode
            val nodes=if(root is BytecodeRoot) listOf(root)+root.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind==Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(root)
            for(call in nodes.flatMap { NodeUtil.findAllNodeInstances(it,DirectCallNode::class.java) }) {
                val active=call.currentCallTarget as? RootCallTarget ?: continue
                if(active.rootNode is GuestRoot)visit(active)
            }
            result.add(target)
        }
        visit(entry);return result
    }
    private fun released(language: Language) {
        val state=language.handoffState.get()
        assertEquals(0,state.arguments.depth);assertEquals(0,state.results.depth)
        assertEquals(0,state.arguments.retainedReferences());assertEquals(0,state.results.retainedReferences())
    }
    @Test fun nativeFillsMovesAndPublicReplicateWithInlining()=native(true)
    @Test fun nativeFillsMovesAndPublicReplicateAcrossResidualCalls()=native(false)
    private data class Row(val raw: Long,val code: Long,val expected: Long)
    private val inputs = buildSet {
        val carriers = (-256L..511L).toMutableSet()
        for (sign in listOf(-1L, 1L)) for (bit in 0..63) for (delta in -1L..1L)
            carriers.add(sign * ((1L shl bit) + delta))
        for (raw in carriers) add(raw to 72L)
        for (code in 0L..1023L) add(code * 0x123456789abcdefL + Long.MIN_VALUE to code)
        for (raw in listOf(Long.MIN_VALUE, -257L, -1L, 0L, 255L, 256L, Long.MAX_VALUE))
            for (code in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) add(raw to code)
    }.sortedWith(compareBy({ it.first }, { it.second }))
    private fun copyRange(code: Long): Triple<Int, Int, Int> {
        val key = (code and 1023).toInt(); val from = key % 9; val to = key / 9 % 9
        return Triple(from, to, minOf(key / 81 % 9, 8 - from, 8 - to))
    }
    private fun disjointRange(code: Long): Triple<Int, Int, Int> {
        val key = (code and 1023).toInt(); val low = key % 5; val high = 4 + key / 5 % 5
        val count = minOf(key / 25 % 5, 4 - low, 8 - high)
        return if (key / 125 % 2 == 0) Triple(low, high, count) else Triple(high, low, count)
    }
    private fun fingerprint(values: List<Int>): Long {
        var weight = 1L; var result = 0L
        for (value in values) { result += weight * value; weight *= 257 }
        return result
    }
    // Independent list/snapshot semantics, never a call into ManagedByteArray.
    // Long arithmetic deliberately retains the native signed-64-bit wrap.
    private fun model(name: String, raw: Long, code: Long): Long {
        require(name in names) { "Unknown mutable byte-array entry" }
        val source = MutableList(8) { ((raw + 17L * it) and 255).toInt() }
        if (name == "publicReplicate") return (0 until (code and 15).toInt())
            .fold(code and 15) { answer, _ -> answer * 257 + (raw and 255) }
        if (name == "filledBytes") {
            val key = (code and 1023).toInt(); val start = key % 9; val count = minOf(key / 9 % 9, 8 - start)
            repeat(count) { source[start + it] = (raw and 255).toInt() }
            return fingerprint(source)
        }
        val (from, to, count) = if (name == "disjointBytes") disjointRange(code) else copyRange(code)
        if (name in listOf("movedBytes", "disjointBytes")) {
            val snapshot = source.toList()
            repeat(count) { source[to + it] = snapshot[from + it] }
            return fingerprint(source)
        }
        val destination = MutableList(8) { ((raw + 101 + 29L * it) and 255).toInt() }
        repeat(count) { destination[to + it] = source[from + it] }
        source[0] = ((raw + 93) and 255).toInt()
        return fingerprint(source) + 65537 * fingerprint(destination)
    }
    private fun checkedRows(text: String): Map<String, List<Row>> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(lines.size == names.size * inputs.size) { "Incomplete mutable byte-array corpus" }
        val result = names.associateWith { mutableListOf<Row>() }
        for ((index, line) in lines.withIndex()) {
            val fields = line.split('\t'); require(fields.size == 4) { "Expected entry/raw/code/result" }
            val name = names[index / inputs.size]; val (raw, code) = inputs[index % inputs.size]
            require(fields[0] == name && fields[1].toLongOrNull() == raw && fields[2].toLongOrNull() == code) {
                "Missing, duplicate, reordered or unknown mutable byte-array row $index"
            }
            val expected = model(name, raw, code)
            require(fields[3].toLongOrNull() == expected) { "Native/model mismatch at row $index" }
            result.getValue(name).add(Row(raw, code, expected))
        }
        return result
    }
    @Test fun independentMutableModelCoversCarriersAndEveryContainedRange() {
        assertEquals(2146, inputs.size)
        val pairs = inputs.toSet()
        for (raw in -256L..511L) assertTrue(raw to 72L in pairs)
        for (raw in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) assertTrue(raw to 72L in pairs)
        val moves = inputs.map { copyRange(it.second) }.toSet()
        assertTrue(moves.containsAll(ranges()))
        val disjoint = inputs.map { disjointRange(it.second) }.toSet()
        assertTrue(disjoint.any { (a, b, n) -> n > 0 && a < b })
        assertTrue(disjoint.any { (a, b, n) -> n > 0 && a > b })
        for ((a, b, n) in disjoint) assertTrue(n == 0 || a + n <= b || b + n <= a)
    }
    @Test fun independentMutableMovesSnapshotBothDirectionsAndDistinctCopiesAgree() {
        for ((from, to, count) in listOf(Triple(0, 1, 7), Triple(1, 0, 7), Triple(0, 0, 8), Triple(8, 8, 0))) {
            val source = List(8) { (127 + 17 * it) % 256 }
            val expected = source.mapIndexed { index, value -> if (index in to until to + count) source[from + index - to] else value }
            assertEquals(fingerprint(expected), model("movedBytes", 127, (from + 9 * to + 81 * count).toLong()))
        }
        for ((raw, code) in inputs)
            assertEquals(model("copiedMutableBytes", raw, code), model("copiedDisjointBytes", raw, code))
        assertThrows(IllegalArgumentException::class.java) { model("unknown", 0, 0) }
    }
    @Test fun independentMutableCorpusRejectsMissingDuplicateReorderedAndWrongRows() {
        val lines = names.flatMap { name -> inputs.map { (raw, code) -> "$name\t$raw\t$code\t${model(name, raw, code)}" } }
        fun text(rows: List<String>) = rows.joinToString("\n", postfix = "\n")
        assertEquals(names.toSet(), checkedRows(text(lines)).keys)
        for (bad in listOf(lines.drop(1), lines + lines.first(), lines.reversed(),
            listOf(lines[1]) + lines.drop(1), listOf("unknown\t0\t0\t0") + lines.drop(1),
            listOf(lines.first().substringBeforeLast('\t') + "\t999") + lines.drop(1),
            listOf(lines.first().replaceFirst('\t', ' ')) + lines.drop(1), lines + "", emptyList()))
            assertThrows(IllegalArgumentException::class.java) { checkedRows(text(bad)) }
        checkedRows(File(root, "build/mutable-bytearrays/oracle.tsv").readText())
    }
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        ByteArrayFixtureEvidence.verify(root, "mutable-bytearrays", manifest)
        assertEquals(names, manifest["entries"])
        assertEquals(inputs.map { listOf(it.first, it.second) }, manifest["inputs"])
        val rows=checkedRows(File(root,"build/mutable-bytearrays/oracle.tsv").readText())
        assertEquals(names.toSet(),rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        for((stage,paths) in manifest["stages"] as Map<String,List<String>>)for(name in names) {
            val cases=rows.getValue(name)
            val audit=Json.parse(File(root,"build/mutable-bytearrays/$stage-$name.audit.json").readText()) as Map<*,*>
            assertEquals(true,audit["accepted"])
            for(backend in listOf("ast","bytecode"))context(inlining).use { context->
                context.initialize("thc");context.enter()
                try {
                    val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program=program(language,CoreModules.reachable(merged(paths),name)+("instrument" to true),backend)
                    val function=context.asValue(EntryValue(program,name,2));val host=program.hostEntryTarget(2);val original=program.entryTarget(name)
                    val label="$stage/$backend/$name/inlining=$inlining"
                    fun check(row: Row) { assertEquals(row.expected,function.execute(row.raw,row.code).asLong(),"$label/${row.raw}/${row.code}");released(language) }
                    cases.forEach(::check)
                    val targets=activeTargets(host);assertTrue(targets.size>1,label)
                    targets.filter { it!==host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val allocations=language.handoffState.get().results.allocations
                    assertEquals(0L,allocations,"$label State-only effects do not allocate result packets")
                    for(row in cases.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong();check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest")
                        assertEquals(targets,activeTargets(host),"$label active identities");valid(original,label);targets.forEach { valid(it,label) }
                    }
                    assertEquals(allocations,language.handoffState.get().results.allocations,label)
                    for(counter in listOf("unsupportedTraps","blackholes"))assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),label)
                    println("MutableByteArray PASS $label rows=${cases.size}")
                } finally {context.leave()}
            }
        }
    }
    @Test fun mutableFixtureEvidenceRejectsMissingAndChangedProvenance() =
        ByteArrayFixtureEvidence.rejectionControls(root, "mutable-bytearrays")
    private fun bytes(seed: Int=0)=ByteArray(8) { (seed+17*it).toByte() }
    private fun overlaps(a: Int,b: Int,n: Int)=n>0 && a<b+n && b<a+n
    private fun ranges()=buildList {for(a in 0..8)for(b in 0..8)for(n in 0..minOf(8-a,8-b))add(Triple(a,b,n))}
    private fun invalidRanges()=listOf(-1L to 0L,Long.MIN_VALUE to 0L,9L to 0L,(1L shl 32) to 0L,
        Long.MAX_VALUE to 0L,0L to -1L,0L to Long.MIN_VALUE,0L to Long.MAX_VALUE,1L to Long.MAX_VALUE,8L to 1L)
    @Test fun fullWidthDomainsFillTruncationAndOverlapPoliciesPreserveStorage() {
        for(start in 0..8)for(count in 0..8-start)for(value in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-257L,-256L,-1L,0L,127L,128L,255L,256L,511L,1L shl 40)) {
            val array=bytes();val alias=array;val expected=array.copyOf()
            for(i in start until start+count)expected[i]=(value and 255L).toByte()
            ManagedByteArray.fill(array,start.toLong(),count.toLong(),value)
            assertSame(alias,array);assertArrayEquals(expected,array)
        }
        for((from,to,count) in ranges())for(same in listOf(false,true))for(nonOverlapping in listOf(false,true)) {
            val source=bytes(0);val destination=if(same)source else bytes(101);val alias=destination
            val sourceBefore=source.copyOf();val before=destination.copyOf()
            if(same && nonOverlapping && overlaps(from,to,count)) {
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.copyMutable(source,from.toLong(),destination,to.toLong(),count.toLong(),true) }
                assertArrayEquals(before,destination)
            } else {
                val expected=before.copyOf();for(i in 0 until count)expected[to+i]=sourceBefore[from+i]
                ManagedByteArray.copyMutable(source,from.toLong(),destination,to.toLong(),count.toLong(),nonOverlapping)
                assertSame(alias,destination);assertArrayEquals(expected,destination)
                if(!same)assertArrayEquals(sourceBefore,source)
            }
        }
        for((offset,count) in invalidRanges()) {
            val source=bytes();val destination=bytes(101);val before=destination.copyOf()
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.fill(destination,offset,count,255L) };assertArrayEquals(before,destination)
            for(nonOverlapping in listOf(false,true)) {
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.copyMutable(source,offset,destination,0,count,nonOverlapping) };assertArrayEquals(before,destination)
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.copyMutable(source,0,destination,offset,count,nonOverlapping) };assertArrayEquals(before,destination)
            }
        }
        val array=bytes()
        // Existing immutable/mutable copy stays stricter than either new operation.
        for(n in listOf(0L,1L))assertThrows(RuntimeFault::class.java) { ManagedByteArray.copy(array,0,array,4,n) }
        assertArrayEquals(bytes(),array)
    }
    @Test fun allOperandsAndStateAreEvaluatedBeforeMutation() {
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),FrameDescriptor.newBuilder().build())
        for(operation in operations)for(fail in listOf(false,true)) {
            val source=bytes();val destination=bytes(101);val before=destination.copyOf();val events=mutableListOf<String>()
            fun operand(name: String,value: Any?)=object: Expr() {override fun execute(frame: VirtualFrame): Any? {events.add(name);return value}}
            val state=object: Expr() {override fun execute(frame: VirtualFrame): Any {events.add("state");assertArrayEquals(before,destination);if(fail)throw RuntimeFault("State failed before mutation");return Unit}}
            val operands=if(operation==ByteArrayOp.SET) arrayOf(operand("destination",destination),operand("offset",1L),operand("count",3L),operand("value",511L),state)
                else arrayOf(operand("source",source),operand("sourceOffset",0L),operand("destination",destination),operand("destinationOffset",1L),operand("count",3L),state)
            val expression=byteArrayExpression(operation,CoreRepresentation.UNKNOWN,operands)
            if(fail) {assertThrows(RuntimeFault::class.java) {expression.execute(frame)};assertArrayEquals(before,destination)}
            else {assertSame(Unit,expression.execute(frame));val expected=before.copyOf();for(i in 0..2)expected[i+1]=if(operation==ByteArrayOp.SET)(511 and 255).toByte() else source[i];assertArrayEquals(expected,destination)}
            assertEquals(if(operation==ByteArrayOp.SET)listOf("destination","offset","count","value","state") else listOf("source","sourceOffset","destination","destinationOffset","count","state"),events)
        }
    }
    private fun synthetic(operation: ByteArrayOp): Map<String,Any?> {
        val array=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
        val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
        val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
        val closure=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
        val proofs=if(operation==ByteArrayOp.SET)listOf(array,long,long,long,state) else listOf(array,long,array,long,long,state)
        val params=proofs.mapIndexed {i,p->mapOf("id" to "p$i","lifted" to false,"rep" to p)}
        val app=listOf("app",listOf("prim",operation.primitive),params.map {listOf("var",it["id"],mapOf("rep" to it["rep"]))},List(params.size){false},false,false,mapOf("rep" to state))
        val body=listOf("case",app,"state",listOf(listOf("default",null,emptyList<String>(),listOf("lit","int","17",mapOf("rep" to long)))),
            mapOf("rep" to long,"binder" to mapOf("id" to "state","lifted" to false,"rep" to state)))
        return mapOf("instrument" to true,"constructors" to emptyList<Any?>(),"bindings" to listOf(mapOf("id" to "mutate","name" to "mutate","arity" to params.size,"lifted" to true,"rep" to closure,
            "expr" to listOf("lam",params,body,mapOf("rep" to closure,"resultRep" to long)))))
    }
    @Test fun compiledTypedBackendsMutateExactBytesAndGuardStateBoundsAndOverlap() {
        for(backend in listOf("ast","bytecode"))for(operation in operations)context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,synthetic(operation),backend);val entry=program.entryTarget("mutate")
                fun call(source: Any?,from: Long,destination: Any?,to: Long,count: Long,value: Long=511,state: Any?=Unit): Any? {
                    val packet=if(operation==ByteArrayOp.SET)arrayOf<Any?>(0L,destination,to,count,value,state)
                        else arrayOf<Any?>(0L,source,from,destination,to,count,state)
                    return Calls.target(entry,packet)
                }
                fun positive(compiled: Boolean) {
                    for((from,to,count) in ranges())for(same in listOf(false,true)) {
                        if(operation==ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING && same && overlaps(from,to,count))continue
                        val source=bytes();val destination=if(same)source else bytes(101);val sourceBefore=source.copyOf();val expected=destination.copyOf()
                        for(i in 0 until count)expected[to+i]=if(operation==ByteArrayOp.SET)(511 and 255).toByte() else sourceBefore[from+i]
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(17L,call(source,from.toLong(),destination,to.toLong(),count.toLong()))
                        assertArrayEquals(expected,destination);if(!same)assertArrayEquals(sourceBefore,source)
                        if(compiled) {assertEquals(before+1,(program.diagnostics().getValue("compiledEntries") as Number).toLong());valid(entry,"$backend/$operation")};released(language)
                    }
                }
                positive(false);compile(entry);positive(true)
                for((offset,count) in invalidRanges()) {
                    val source=bytes();val destination=bytes(101);val before=destination.copyOf()
                    assertThrows(RuntimeFault::class.java) {call(source,0,destination,offset,count)};assertArrayEquals(before,destination)
                    if(operation!=ByteArrayOp.SET) {assertThrows(RuntimeFault::class.java) {call(source,offset,destination,0,count)};assertArrayEquals(before,destination)}
                }
                val source=bytes();val destination=bytes(101);val before=destination.copyOf()
                val failure=assertThrows(RuntimeFault::class.java) {call(source,Long.MAX_VALUE,destination,Long.MAX_VALUE,Long.MAX_VALUE,state=17L)}
                assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier"),failure.message);assertArrayEquals(before,destination)
                if(operation==ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING) {
                    for((from,to,count) in listOf(Triple(0L,1L,7L),Triple(1L,0L,7L),Triple(0L,0L,8L))) {
                        val array=bytes();assertThrows(RuntimeFault::class.java) {call(array,from,array,to,count)};assertArrayEquals(bytes(),array)
                    }
                }
                for(bad in listOf(Any(),arrayOf<Any?>(1))) {
                    assertThrows(RuntimeFault::class.java) {call(source,0,bad,0,0)}
                    if(operation!=ByteArrayOp.SET)assertThrows(RuntimeFault::class.java) {call(bad,0,destination,0,0)}
                }
                released(language)
            } finally {context.leave()}
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when(value) {
        is List<*> -> (if(value.firstOrNull()=="app")listOf(value as MutableList<Any?>) else emptyList())+value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun exactArityIntFillStateAndUnliftedReferenceProofsAreRequired() {
        val paths=(manifest()["stages"] as Map<String,List<String>>).getValue("pre")
        for(backend in listOf("ast","bytecode"))context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(operation in operations) {
                    val name=when(operation) {ByteArrayOp.SET->"filledBytes";ByteArrayOp.COPY_MUTABLE->"movedBytes";else->"disjointBytes"}
                    for(mutation in 0..12)for(diagnostic in listOf(false,true)) {
                        val module=CoreModules.reachable(merged(paths),name)
                        val app=applications(module).first {(it[1] as List<*>).take(2)==listOf("prim",operation.primitive)}
                        val args=app[2] as MutableList<Any?>;val flags=app[3] as MutableList<Any?>
                        val meta=CoreRepresentations.metadata(app) as MutableMap<String,Any?>
                        when(mutation) {
                            0->{args.removeAt(args.lastIndex);flags.removeAt(flags.lastIndex);meta.remove("callDemand")}
                            1->{args.add(args[0]);flags.add(false);meta.remove("callDemand")}
                            2->meta.remove("rep")
                            3->flags[0]=true
                            4->(CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Lifted)")
                            5,6,7-> {val index=if(operation==ByteArrayOp.SET)3 else 4
                                (CoreRepresentations.metadata(args[index] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf(when(mutation){5->"Word8Rep";6->"WordRep";else->"Int64Rep"})}
                            8->meta["rep"]=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any?>(),"primReps" to emptyList<String>(),"evaluated" to true)
                            9->(CoreRepresentations.metadata(args.last() as List<Any?>) as MutableMap<String,Any?>)["rep"]=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any?>(),"primReps" to emptyList<String>(),"evaluated" to true)
                            12-> {val index=if(operation==ByteArrayOp.SET)0 else 2
                                (CoreRepresentations.metadata(args[index] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Lifted)")}
                            10,11-> {val index=if(mutation==10)1 else if(operation==ByteArrayOp.SET)2 else 3
                                (CoreRepresentations.metadata(args[index] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("WordRep")}
                        }
                        assertThrows(RuntimeFault::class.java,{program(language,module+("diagnosticUnsupported" to diagnostic),backend)},"$operation/$mutation/$backend/$diagnostic")
                    }
                    val module=CoreModules.reachable(merged(paths),name);val app=applications(module).first {(it[1] as List<*>).take(2)==listOf("prim",operation.primitive)}
                    val primitive=(app[1] as List<*>).toList();app.clear();app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) {program(language,module,backend)}
                }
            } finally {context.leave()}
        }
    }
}
