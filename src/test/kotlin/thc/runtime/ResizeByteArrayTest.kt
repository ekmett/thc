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

class ResizeByteArrayTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private val names=listOf("resizedBytes","resizedTwiceWrites")
    private fun context(inlining: Boolean=true)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun manifest()=Json.parse(File(root,"build/resize-bytearrays/manifest.json").readText()) as Map<String,Any?>
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
    @Test fun nativeResizePrefixAndRepeatedWritesWithInlining()=native(true)
    @Test fun nativeResizePrefixAndRepeatedWritesAcrossResidualCalls()=native(false)
    private data class Row(val name: String,val raw: Long,val code: Long,val expected: Long)
    @Test fun resizeFixtureEvidenceRejectsMissingAndChangedProvenance() =
        ByteArrayFixtureEvidence.rejectionControls(root, "resize-bytearrays")
    private fun inputs(): List<Pair<Long,Long>> {
        val pairs=(0 until 17*17).map { it.toLong()*0x123456789abcdefL to it.toLong() }.toMutableSet()
        for(seed in 0L..255L)for((old,size) in listOf(0 to 16,16 to 0,16 to 8,8 to 16,8 to 8))
            pairs.add(seed to (old+17*size).toLong())
        for(seed in listOf(Long.MIN_VALUE,-257L,-1L,0L,255L,256L,Long.MAX_VALUE))
            for(code in listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE))pairs.add(seed to code)
        return pairs.sortedWith(compareBy({ it.first },{ it.second }))
    }
    private fun sizes(code: Long): Pair<Int,Int> {
        val key=(code and 1023).toInt()
        return key%17 to key/17%17
    }
    // Defined-domain list semantics: retain the prefix and initialize every grown
    // byte. Do not model retired aliases or promise zero-filled native growth.
    private fun byteModel(name: String,raw: Long,code: Long): List<Int> {
        require(name in names) { "Unknown resize entry: $name" }
        val (old,size)=sizes(code)
        fun byte(seed: Long,index: Int)=((seed+17L*index) and 255).toInt()
        fun resize(source: List<Int>,length: Int,seed: Long)=List(length) { index->
            if(index<source.size)source[index] else byte(seed,index)
        }
        val result=resize(List(old) { byte(raw,it) },size,raw+91)
        if(name=="resizedBytes")return result
        val twice=resize(result,(size+7)%17,raw+133).toMutableList()
        if(twice.isNotEmpty())twice[twice.lastIndex]=((raw+211) and 255).toInt()
        return twice
    }
    private fun model(name: String,raw: Long,code: Long): Long {
        val bytes=byteModel(name,raw,code)
        return bytes.fold(bytes.size.toLong()) { answer,byte->answer*257+byte }
    }
    private fun expectedRows()=names.flatMap { name->inputs().map { (raw,code)->Row(name,raw,code,model(name,raw,code)) } }
    private fun verifyRows(text: String): List<Row> {
        val lines=text.lineSequence().toList().let { if(it.lastOrNull()=="")it.dropLast(1) else it }
        val rows=lines.map { line->
            val fields=line.split('\t')
            require(fields.size==4) { "Resize oracle requires name/raw/code/result" }
            Row(fields[0],fields[1].toLong(),fields[2].toLong(),fields[3].toLong())
        }
        require(rows==expectedRows()) { "Resize native/model mismatch, missing, duplicated or reordered row" }
        return rows
    }
    @Test fun independentModelCoversEveryLengthBytePatternAndExtreme() {
        val inputs=inputs();val inventory=inputs.toSet()
        assertEquals(1596,inputs.size);assertEquals(inputs.size,inventory.size)
        assertEquals(inputs.sortedWith(compareBy({ it.first },{ it.second })),inputs)
        assertEquals((0..16).flatMap { old->(0..16).map { old to it } }.toSet(),inputs.map { sizes(it.second) }.toSet())
        for((old,size) in listOf(0 to 16,16 to 0,16 to 8,8 to 16,8 to 8))
            for(seed in 0L..255L)assertTrue((seed to (old+17*size).toLong()) in inventory)
        for(seed in listOf(Long.MIN_VALUE,-257L,-1L,0L,255L,256L,Long.MAX_VALUE))
            for(code in listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE))assertTrue((seed to code) in inventory)
        for(name in names)for((raw,code) in inputs) {
            val (old,size)=sizes(code);val bytes=byteModel(name,raw,code)
            val final=if(name=="resizedBytes")size else (size+7)%17
            assertEquals(final,bytes.size)
            for(index in bytes.indices) {
                val expected=when {
                    name=="resizedTwiceWrites" && index==final-1 -> raw+211
                    index>=size -> raw+133+17*index
                    index>=old -> raw+91+17*index
                    else -> raw+17*index
                }
                assertEquals((expected and 255).toInt(),bytes[index],"$name/$raw/$code/$index")
            }
        }
        assertEquals(0L,model("resizedBytes",Long.MIN_VALUE,0))
        assertEquals(468L,model("resizedTwiceWrites",0,11*17))
        assertThrows(IllegalArgumentException::class.java) { byteModel("unknown",0,0) }
    }
    @Test fun independentOracleRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        val expected=expectedRows()
        fun text(rows: List<Row>)=rows.joinToString("\n",postfix="\n") { "${it.name}\t${it.raw}\t${it.code}\t${it.expected}" }
        val valid=text(expected)
        assertEquals(3192,expected.size);assertEquals(expected,verifyRows(valid))
        val first=expected.first()
        val bad=listOf("",text(expected.drop(1)),text(expected+first),text(expected.asReversed()),
            text(expected.toMutableList().apply { this[0]=this[1] }),
            text(expected.toMutableList().apply { this[0]=first.copy(expected=first.expected+1) }),
            text(expected.toMutableList().apply { this[0]=first.copy(raw=first.raw+1) }),
            text(expected.toMutableList().apply { this[0]=first.copy(code=first.code+1) }),
            text(expected.toMutableList().apply { this[0]=first.copy(name="unknown") }),
            valid.replaceFirst("\t"," "),valid.replaceFirst("\t","\textra\t"),
            "resizedBytes\t0\t0\tnot-an-integer\n", "resizedBytes\t9223372036854775808\t0\t0\n",valid+"\n")
        for((index,corrupt) in bad.withIndex())assertThrows(IllegalArgumentException::class.java,{verifyRows(corrupt)},"mutation $index")
    }
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        ByteArrayFixtureEvidence.verify(root, "resize-bytearrays", manifest)
        assertEquals(names,manifest["entries"])
        assertEquals(inputs(),(manifest["inputs"] as List<List<Number>>).map { it[0].toLong() to it[1].toLong() })
        val rows=verifyRows(File(root,"build/resize-bytearrays/oracle.tsv").readText()).groupBy { it.name }
        assertEquals(names.toSet(),rows.keys)
        assertEquals(3192,(manifest["nativeRows"] as Number).toInt())
        assertEquals((manifest["nativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        val stages=manifest["stages"] as Map<String,List<String>>
        assertEquals(setOf("pre","post"),stages.keys)
        for((stage,paths) in stages)for(name in names) {
            val cases=rows.getValue(name)
            val audit=Json.parse(File(root,"build/resize-bytearrays/$stage-$name.audit.json").readText()) as Map<*,*>
            assertEquals(true,audit["accepted"])
            assertTrue((audit["reachableBindings"] as List<Map<String,Any?>>).any { (it["id"] as String).endsWith(".resizeWorker") })
            assertTrue((audit["primitives"] as List<Map<String,Any?>>).any { it["name"]=="resizeMutableByteArray#" })
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
                    assertTrue(allocations>0,"$label native retained resizeWorker returns a reference tuple")
                    for(row in cases.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong();check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest")
                        assertEquals(targets,activeTargets(host),"$label active identities");valid(original,label);targets.forEach { valid(it,label) }
                    }

                    for(counter in listOf("unsupportedTraps","blackholes"))assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),label)
                    println("ResizeByteArray PASS $label rows=${cases.size}")
                } finally {context.leave()}
            }
        }
    }
    private val invalidSizes=listOf(Long.MIN_VALUE,-1L,Int.MAX_VALUE.toLong()+1,1L shl 32,Long.MAX_VALUE)
    @Test fun managedResizePreservesThePrefixAndRejectsFullWidthInvalidLengths() {
        for(old in 0..16)for(size in 0..16)for(seed in 0..255) {
            val original=ByteArray(old) { (seed+17*it).toByte() }
            val result=ManagedByteArray.resize(original,size.toLong())
            assertEquals(size,result.size)
            for(i in 0 until minOf(old,size))assertEquals((seed+17*i).toByte(),result[i])
            if(size==old)assertSame(original,result)
            // Do not turn JVM-zeroed growth into a guest contract: initialize it.
            for(i in old until size)result[i]=(seed+91+17*i).toByte()
            if(size>0) { ManagedByteArray.write(result,(size-1).toLong(),511);assertEquals(255L,ManagedByteArray.read(result,(size-1).toLong())) }
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.read(result,size.toLong()) }
        }
        for(size in invalidSizes) {
            val original=byteArrayOf(1,2,3)
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.resize(original,size) }
            assertArrayEquals(byteArrayOf(1,2,3),original)
        }
    }
    @Test fun stateIsEvaluatedBeforeAllocationAndFailureDoesNotPublish() {
        val descriptor=FrameDescriptor.newBuilder().apply { addSlot(FrameSlotKind.Object,null,null);addSlot(FrameSlotKind.Object,null,null) }.build()
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),descriptor)
        for(fail in listOf(false,true)) {
            val sentinel=Any();FrameAccess.write(frame,1,sentinel)
            val source=byteArrayOf(3,5,7);val events=mutableListOf<String>()
            fun operand(name: String,value: Any?)=object: Expr() {override fun execute(frame: VirtualFrame): Any? {events.add(name);return value}}
            val state=object: Expr() {override fun execute(frame: VirtualFrame): Any {
                events.add("state");assertSame(sentinel,FrameAccess.read(frame,1));assertArrayEquals(byteArrayOf(3,5,7),source)
                if(fail)throw RuntimeFault("State failed");return Unit
            }}
            val expr=byteArrayExpression(ByteArrayOp.RESIZE,CoreRepresentation.UNKNOWN,arrayOf(operand("array",source),operand("size",5L),state))
            if(fail) {assertThrows(RuntimeFault::class.java) {expr.executeTuple(frame,intArrayOf(0,1),1)};assertSame(sentinel,FrameAccess.read(frame,1))}
            else {expr.executeTuple(frame,intArrayOf(0,1),1);val result=FrameAccess.read(frame,1) as ByteArray;assertEquals(5,result.size);assertArrayEquals(byteArrayOf(3,5,7),result.copyOf(3))}
            assertEquals(listOf("array","size","state"),events)
        }
    }
    private fun synthetic(): Map<String,Any?> {
        val array=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
        val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
        val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
        val closure=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
        val tuple=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","primReps" to array["primReps"],"components" to listOf(state,array),"evaluated" to true)
        val params=listOf(array,long,state).mapIndexed {i,p->mapOf("id" to "p$i","lifted" to false,"rep" to p)}
        val app=listOf("app",listOf("prim","resizeMutableByteArray#"),params.map {listOf("var",it["id"],mapOf("rep" to it["rep"]))},listOf(false,false,false),false,false,mapOf("rep" to tuple))
        val binders=listOf(mapOf("id" to "s","lifted" to false,"rep" to state),mapOf("id" to "a","lifted" to false,"rep" to array))
        val body=listOf("case",app,"pair",listOf(listOf("data","T2",listOf("s","a"),listOf("var","a",mapOf("rep" to array)),mapOf("binders" to binders))),
            mapOf("rep" to array,"binder" to mapOf("id" to "pair","lifted" to false,"rep" to tuple)))
        return mapOf("instrument" to true,"constructors" to listOf(mapOf("id" to "T2","kind" to "unboxed-tuple","arity" to 2,"tag" to 1)),
            "bindings" to listOf(mapOf("id" to "resize","name" to "resize","arity" to 3,"lifted" to true,"rep" to closure,
                "expr" to listOf("lam",params,body,mapOf("rep" to closure,"resultRep" to array)))))
    }
    @Test fun compiledBackendsReturnTheExactStorageAndGuardStateAndInvalidCarriers() {
        for(backend in listOf("ast","bytecode"))context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,synthetic(),backend);val entry=program.entryTarget("resize")
                fun call(array: Any?,size: Long,state: Any?=Unit)=Calls.target(entry,arrayOf<Any?>(0L,array,size,state)) as ByteArray
                fun positive(compiled: Boolean) {
                    for(old in 0..16)for(size in 0..16) {
                        val source=ByteArray(old) { (128+17*it).toByte() };val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result=call(source,size.toLong());assertEquals(size,result.size)
                        for(i in 0 until minOf(old,size))assertEquals((128+17*i).toByte(),result[i])
                        if(compiled) {assertEquals(before+1,(program.diagnostics().getValue("compiledEntries") as Number).toLong());valid(entry,backend)}
                        released(language)
                    }
                }
                positive(false);compile(entry);positive(true)
                for(size in invalidSizes) {val source=byteArrayOf(7,8);assertThrows(RuntimeFault::class.java) {call(source,size)};assertArrayEquals(byteArrayOf(7,8),source);released(language)}
                val stateFailure=assertThrows(RuntimeFault::class.java) {call(byteArrayOf(7),Long.MAX_VALUE,17L)}
                assertTrue(stateFailure.message.orEmpty().contains("zero-width scalar carrier"),stateFailure.message)
                for(bad in listOf(Any(),arrayOf<Any?>(1),longArrayOf(1)))assertThrows(RuntimeFault::class.java) {call(bad,0)}
                assertEquals(1,call(byteArrayOf(7,8),1).size);released(language)
            } finally {context.leave()}
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when(value) {
        is List<*> -> (if(value.firstOrNull()=="app")listOf(value as MutableList<Any?>) else emptyList())+value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun exactSaturationStateReferenceAndLogicalPairProofsAreRequired() {
        val paths=(manifest()["stages"] as Map<String,List<String>>).getValue("pre")
        for(backend in listOf("ast","bytecode"))context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(mutation in 0..10)for(diagnostic in listOf(false,true)) {
                    val module=CoreModules.reachable(merged(paths),"resizedBytes")
                    val app=applications(module).first {(it[1] as List<*>).take(2)==listOf("prim","resizeMutableByteArray#")}
                    val args=app[2] as MutableList<Any?>;val flags=app[3] as MutableList<Any?>;val meta=CoreRepresentations.metadata(app) as MutableMap<String,Any?>
                    val empty=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any?>(),"primReps" to emptyList<String>(),"evaluated" to true)
                    when(mutation) {
                        0->{args.removeAt(2);flags.removeAt(2);meta.remove("callDemand")}
                        1->{args.add(args[0]);flags.add(false);meta.remove("callDemand")}
                        2->meta.remove("rep")
                        3->flags[0]=true
                        4->(CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Lifted)")
                        5,6->(CoreRepresentations.metadata(args[1] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf(if(mutation==5)"WordRep" else "Int64Rep")
                        7->(CoreRepresentations.metadata(args[2] as List<Any?>) as MutableMap<String,Any?>)["rep"]=empty
                        8->((meta["rep"] as MutableMap<String,Any?>)["components"] as MutableList<Any?>)[0]=empty
                        9->{val rep=meta["rep"] as MutableMap<String,Any?>;val fields=rep["components"] as MutableList<Any?>;fields.removeAt(0)}
                        10->{val rep=meta["rep"] as MutableMap<String,Any?>;rep["primReps"]=emptyList<String>();rep["components"]=emptyList<Any?>()}
                    }
                    if (mutation in 5..6)
                        assertDoesNotThrow({program(language,module+("diagnosticUnsupported" to diagnostic),backend)},"$backend/$mutation/$diagnostic")
                    else assertThrows(RuntimeFault::class.java,{program(language,module+("diagnosticUnsupported" to diagnostic),backend)},"$backend/$mutation/$diagnostic")
                }
                val module=CoreModules.reachable(merged(paths),"resizedBytes");val app=applications(module).first {(it[1] as List<*>).take(2)==listOf("prim","resizeMutableByteArray#")}
                val bare=(app[1] as List<*>).toList();app.clear();app.addAll(bare)
                assertThrows(UnsupportedCore::class.java) {program(language,module,backend)}
            } finally {context.leave()}
        }
    }
}
