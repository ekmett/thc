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
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

class MutableByteArraySizeTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private val names=listOf("freshSize","pureSize","resizedSizes","pureAfterResize","orderedSize")
    private fun context(inlining: Boolean=true)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun manifest()=Json.parse(File(root,"build/mutable-bytearray-size/manifest.json").readText()) as Map<String,Any?>
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
    @Test fun nativeLiveMutableSizesWithInlining()=native(true)
    @Test fun nativeLiveMutableSizesAcrossResidualCalls()=native(false)
    private data class Row(val raw: Long,val code: Long,val expected: Long)
    @Test fun sizeFixtureEvidenceRejectsMissingAndChangedProvenance() =
        ByteArrayFixtureEvidence.rejectionControls(root, "mutable-bytearray-size")
    // Independent of both the native driver and the guest byte-array implementation.
    // Long multiplication deliberately wraps, matching the signed 64-bit input inventory.
    private val inputs=buildSet {
        for(code in 0L until 17L*17L)add(code*0x123456789abcdefL to code)
        for(raw in 0L..255L)add(raw to 280L)
        for(raw in listOf(Long.MIN_VALUE,-257L,-1L,0L,255L,256L,Long.MAX_VALUE))
            for(code in listOf(Long.MIN_VALUE,-1L,0L,16L,17L,288L,1023L,4095L,4096L,Long.MAX_VALUE))add(raw to code)
    }.sortedWith(compareBy<Pair<Long,Long>> { it.first }.thenBy { it.second })
    private fun mathematical(name: String,raw: Long,code: Long): Long {
        val key=code and 1023L;val old=key%17L;val middle=key/17L%17L
        return when(name) {
            "freshSize","pureSize" -> code and 4095L
            "resizedSizes" -> old*65536L+middle*256L+(middle+7L)%17L
            "pureAfterResize" -> middle
            "orderedSize" -> old+1L+(middle+1L)*257L+((raw+old+1L) and 255L)*65537L
            else -> error("Unknown mutable-size entry $name")
        }
    }
    private fun checkedRows(text: String): Map<String,List<Row>> {
        val split=text.lineSequence().toList()
        val lines=if(split.lastOrNull()=="")split.dropLast(1) else split
        require(lines.size==names.size*inputs.size) { "Mutable-size oracle row count mismatch" }
        val rows=names.associateWith { mutableListOf<Row>() }
        for((index,line) in lines.withIndex()) {
            val fields=line.split('\t');require(fields.size==4) { "Mutable-size oracle columns at row $index" }
            val name=names[index/inputs.size];val (raw,code)=inputs[index%inputs.size]
            require(fields[0]==name && fields[1].toLongOrNull()==raw && fields[2].toLongOrNull()==code) {
                "Mutable-size oracle inventory/order mismatch at row $index"
            }
            val actual=fields[3].toLongOrNull()
            require(actual==mathematical(name,raw,code)) { "Mutable-size native/model mismatch at row $index" }
            rows.getValue(name).add(Row(raw,code,actual))
        }
        return rows
    }
    @Test fun independentInventoryCoversSizesResizeBoundariesAndBytePatterns() {
        assertEquals(614,inputs.size);assertEquals(3070,names.size*inputs.size)
        assertEquals(inputs.size,inputs.toSet().size)
        assertEquals(inputs,inputs.sortedWith(compareBy<Pair<Long,Long>> { it.first }.thenBy { it.second }))
        assertTrue(inputs.containsAll((0L..288L).map { it*0x123456789abcdefL to it }))
        assertTrue(inputs.containsAll((0L..255L).map { it to 280L }))
        for(raw in listOf(Long.MIN_VALUE,-257L,-1L,0L,255L,256L,Long.MAX_VALUE))
            for(code in listOf(Long.MIN_VALUE,-1L,0L,16L,17L,288L,1023L,4095L,4096L,Long.MAX_VALUE))
                assertTrue(raw to code in inputs,"$raw/$code")
        for((raw,code) in inputs) {
            val old=(code and 1023L)%17L;val middle=(code and 1023L)/17L%17L
            for(name in listOf("freshSize","pureSize"))assertEquals(Math.floorMod(code,4096L),mathematical(name,raw,code))
            assertEquals(middle,mathematical("pureAfterResize",raw,code))
            val encoded=mathematical("resizedSizes",raw,code)
            assertEquals(listOf(old,middle,(middle+7L)%17L),listOf(encoded/65536L,encoded/256L%256L,encoded%256L))
            assertEquals(old+1L+(middle+1L)*257L+Math.floorMod(raw+old+1L,256L)*65537L,mathematical("orderedSize",raw,code))
        }
        assertEquals((0L..255L).toSet(),(0L..255L).map { (mathematical("orderedSize",it,280L)-4378L)/65537L }.toSet())
    }
    @Test fun independentModelHasExplicitBoundaryAnchors() {
        for(name in listOf("freshSize","pureSize"))
            for((code,expected) in listOf(Long.MIN_VALUE to 0L,-1L to 4095L,0L to 0L,16L to 16L,
                17L to 17L,288L to 288L,1023L to 1023L,4095L to 4095L,4096L to 0L,Long.MAX_VALUE to 4095L))
                assertEquals(expected,mathematical(name,Long.MAX_VALUE,code),"$name/$code")
        for((code,expected) in listOf(Long.MIN_VALUE to 7L,0L to 7L,16L to 1048583L,17L to 264L,
            288L to 1052678L,1023L to 198928L,-1L to 198928L,Long.MAX_VALUE to 198928L))
            assertEquals(expected,mathematical("resizedSizes",0L,code),"resizedSizes/$code")
        for((code,expected) in listOf(0L to 0L,288L to 16L,1023L to 9L))
            assertEquals(expected,mathematical("pureAfterResize",0L,code))
        for((raw,expected) in listOf(0L to 65795L,-1L to 258L,255L to 258L,256L to 65795L,
            Long.MAX_VALUE to 258L,Long.MIN_VALUE to 65795L))
            assertEquals(expected,mathematical("orderedSize",raw,0L),"orderedSize/$raw")
    }
    @Test fun exactOracleRejectsMissingDuplicatedReorderedMalformedAndWrongRows() {
        val lines=names.flatMap { name->inputs.map { (raw,code)->"$name\t$raw\t$code\t${mathematical(name,raw,code)}" } }
        fun verify(lines: List<String>)=checkedRows(lines.joinToString("\n",postfix="\n"))
        val expected=names.associateWith { name->inputs.map { (raw,code)->Row(raw,code,mathematical(name,raw,code)) } }
        assertEquals(expected,verify(lines));assertEquals(expected,checkedRows(lines.joinToString("\n")))
        fun reject(label: String,broken: List<String>) {
            assertThrows(IllegalArgumentException::class.java,{verify(broken)},label)
        }
        reject("missing",lines.drop(1));reject("extra duplicate",lines+lines.first())
        reject("duplicate replaces input",lines.toMutableList().apply { this[1]=first() })
        reject("reversed",lines.reversed())
        reject("swapped inputs",lines.toMutableList().apply { Collections.swap(this,0,1) })
        reject("swapped entries",lines.drop(inputs.size)+lines.take(inputs.size))
        for((column,value) in listOf(0 to "unknownSize",1 to "0",2 to "1",3 to "-1",
            1 to "not-an-int",2 to "9223372036854775808",3 to "",3 to "1.0",3 to "9223372036854775808")) {
            val fields=lines.first().split('\t').toMutableList();fields[column]=value
            reject("field $column/$value",listOf(fields.joinToString("\t"))+lines.drop(1))
        }
        reject("missing column",listOf(lines.first().substringBeforeLast('\t'))+lines.drop(1))
        reject("extra column",listOf(lines.first()+"\t0")+lines.drop(1))
        reject("blank row",listOf("")+lines.drop(1));reject("extra blank row",lines+"")
        reject("empty",emptyList())
    }
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        assertEquals(names,manifest["entries"])
        assertEquals(inputs.map { listOf(it.first,it.second) },manifest["inputs"])
        assertEquals((names.size*inputs.size).toLong(),manifest["nativeRows"])
        assertEquals(setOf("pre","post"),(manifest["stages"] as Map<*,*>).keys)
        assertEquals(setOf("pre","post").flatMap { stage->names.map { "$stage/$it" } }.toSet(),(manifest["audits"] as Map<*,*>).keys)
        ByteArrayFixtureEvidence.verify(root, "mutable-bytearray-size", manifest)
        val rows=checkedRows(File(root,"build/mutable-bytearray-size/oracle.tsv").readText())
        for((stage,paths) in manifest["stages"] as Map<String,List<String>>)for(name in names) {
            val cases=rows.getValue(name)
            val audit=Json.parse(File(root,"build/mutable-bytearray-size/$stage-$name.audit.json").readText()) as Map<*,*>
            assertEquals(true,audit["accepted"])
            val pure=name in listOf("pureSize","pureAfterResize")
            assertTrue((audit["primitives"] as List<Map<String,Any?>>).any { it["name"]==if(pure)"sizeofMutableByteArray#" else "getSizeofMutableByteArray#" })
            assertTrue((audit["reachableBindings"] as List<Map<String,Any?>>).any { (it["id"] as String).endsWith(if(pure)".pureSizeWorker" else ".getSizeWorker") })
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
                    if(name !in listOf("pureSize","pureAfterResize"))
                        assertTrue(allocations>0,"$label native retained getSizeWorker returns a Long tuple")
                    for(row in cases.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong();check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest")
                        assertEquals(targets,activeTargets(host),"$label active identities");valid(original,label);targets.forEach { valid(it,label) }
                    }

                    for(counter in listOf("unsupportedTraps","blackholes"))assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),label)
                    println("MutableByteArraySize PASS $label rows=${cases.size}")
                } finally {context.leave()}
            }
        }
    }
    private val arrayProof=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
    private val longProof=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
    private val stateProof=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
    private val closureProof=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
    private val tupleProof=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","primReps" to listOf("IntRep"),
        "components" to listOf(stateProof,longProof),"evaluated" to true)
    private fun synthetic(effectful: Boolean): Map<String,Any?> {
        val proofs=if(effectful)listOf(arrayProof,stateProof) else listOf(arrayProof)
        val params=proofs.mapIndexed {i,p->mapOf("id" to "p$i","lifted" to false,"rep" to p)}
        val app=listOf("app",listOf("prim",if(effectful)"getSizeofMutableByteArray#" else "sizeofMutableByteArray#"),
            params.map {listOf("var",it["id"],mapOf("rep" to it["rep"]))},List(proofs.size){false},false,false,
            mapOf("rep" to if(effectful)tupleProof else longProof))
        val binders=listOf(mapOf("id" to "s","lifted" to false,"rep" to stateProof),mapOf("id" to "n","lifted" to false,"rep" to longProof))
        val body=if(effectful)listOf("case",app,"pair",listOf(listOf("data","T2",listOf("s","n"),
            listOf("var","n",mapOf("rep" to longProof)),mapOf("binders" to binders))),
            mapOf("rep" to longProof,"binder" to mapOf("id" to "pair","lifted" to false,"rep" to tupleProof))) else app
        return mapOf("instrument" to true,"constructors" to listOf(mapOf("id" to "T2","kind" to "unboxed-tuple","arity" to 2,"tag" to 1)),
            "bindings" to listOf(mapOf("id" to "size","name" to "size","arity" to proofs.size,"lifted" to true,"rep" to closureProof,
                "expr" to listOf("lam",params,body,mapOf("rep" to closureProof,"resultRep" to longProof)))))
    }
    @Test fun effectfulSizeEvaluatesStateOnceBeforeTypedPublication() {
        val descriptor=FrameDescriptor.newBuilder().apply {repeat(2){addSlot(FrameSlotKind.Long,null,null)}}.build()
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),descriptor)
        for(mode in listOf("ok","throw","invalid")) {
            val events=mutableListOf<String>();FrameAccess.writeLong(frame,0,37L);FrameAccess.writeLong(frame,1,91L)
            val array=object: Expr() {override fun execute(frame: VirtualFrame): Any {events.add("array");return byteArrayOf(1,2,3)}}
            val state=object: Expr() {override fun execute(frame: VirtualFrame): Any {
                events.add("state");assertEquals(91L,frame.getLong(1))
                if(mode=="throw")throw RuntimeFault("state failed")
                return if(mode=="invalid")17L else Unit
            }}
            val expr=byteArrayExpression(ByteArrayOp.GET_SIZE_MUTABLE,CoreRepresentation.UNKNOWN,arrayOf(array,state))
            if(mode=="ok") {expr.executeTuple(frame,intArrayOf(0,1),1);assertEquals(3L,frame.getLong(1))}
            else {assertThrows(RuntimeFault::class.java){expr.executeTuple(frame,intArrayOf(0,1),1)};assertEquals(91L,frame.getLong(1))}
            assertEquals(37L,frame.getLong(0));assertEquals(listOf("array","state"),events)
            assertThrows(RuntimeFault::class.java){expr.execute(frame)}
        }
    }
    @Test fun typedEntriesReturnLongAndRejectInvalidStateAndCarriersWithoutLoans() {
        val sizes=listOf(0,1,7,8,15,16,31,32,255,256,4095)
        for(backend in listOf("ast","bytecode"))for(effectful in listOf(false,true))context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,synthetic(effectful),backend);val target=program.entryTarget("size")
                fun call(array: Any?,state: Any?=Unit): Any? = Calls.target(target,
                    if(effectful)arrayOf<Any?>(0L,array,state) else arrayOf<Any?>(0L,array))
                for(size in sizes)assertEquals(size.toLong(),call(ByteArray(size)))
                compile(target)
                for(size in sizes.reversed()) {
                    val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result=call(ByteArray(size));assertTrue(result is Long);assertEquals(size.toLong(),result)
                    assertEquals(before+1,(program.diagnostics().getValue("compiledEntries") as Number).toLong(),"$backend/$effectful/$size")
                    valid(target,"$backend/$effectful/$size");released(language)
                }
                if(effectful)for(state in listOf<Any?>(null,0L,Any())) {
                    val error=assertThrows(RuntimeFault::class.java){call(byteArrayOf(1),state)}
                    // Null may fail the local initialization guard before reaching the State primitive.
                    if(state!=null)assertTrue(error.message.orEmpty().contains("zero-width scalar carrier"),error.message)
                    released(language)
                }
                for(bad in listOf<Any?>(null,Any(),arrayOf<Any?>(1),longArrayOf(1))) {
                    assertThrows(RuntimeFault::class.java){call(bad)};released(language)
                }
                assertEquals(2L,call(byteArrayOf(7,8)));released(language)
            } finally {context.leave()}
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when(value) {
        is List<*> -> (if(value.firstOrNull()=="app")listOf(value as MutableList<Any?>) else emptyList())+value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun exactScalarAndStateLongPairContractsAreRequiredInBothLoaders() {
        for(backend in listOf("ast","bytecode"))for(effectful in listOf(false,true))context().use {context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(mutation in 0..8)for(diagnostic in listOf(false,true)) {
                    val module=Json.parse(Json.stringify(synthetic(effectful))) as MutableMap<String,Any?>
                    val app=applications(module).single();val args=app[2] as MutableList<Any?>;val flags=app[3] as MutableList<Any?>
                    val meta=CoreRepresentations.metadata(app) as MutableMap<String,Any?>
                    val empty=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any?>(),"primReps" to emptyList<String>(),"evaluated" to true)
                    when(mutation) {
                        0->{args.removeAt(args.lastIndex);flags.removeAt(flags.lastIndex)}
                        1->{args.add(args[0]);flags.add(false)}
                        2->meta.remove("rep")
                        3->flags[0]=true
                        4->(CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Lifted)")
                        5->(CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep Nothing")
                        6->if(effectful)(CoreRepresentations.metadata(args[1] as List<Any?>) as MutableMap<String,Any?>)["rep"]=empty else meta["rep"]=tupleProof
                        7->if(effectful)((meta["rep"] as MutableMap<String,Any?>)["components"] as MutableList<Any?>)[0]=empty else meta["rep"]=longProof+("primReps" to listOf("WordRep"))
                        8->if(effectful)meta["rep"]=longProof else meta["rep"]=longProof+("primReps" to listOf("Int64Rep"))
                    }
                    assertThrows(RuntimeFault::class.java,{program(language,module+("diagnosticUnsupported" to diagnostic),backend)},"$backend/$effectful/$mutation/$diagnostic")
                }
                val module=Json.parse(Json.stringify(synthetic(effectful))) as Map<String,Any?>
                val app=applications(module).single();val bare=(app[1] as List<*>).toList();app.clear();app.addAll(bare)
                assertThrows(UnsupportedCore::class.java){program(language,module,backend)}
            } finally {context.leave()}
        }
    }
}
