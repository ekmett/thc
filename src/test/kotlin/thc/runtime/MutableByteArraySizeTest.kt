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
import java.security.MessageDigest
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
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        for(kind in listOf("inputHashes","artifactHashes"))for((path,expected) in manifest[kind] as Map<String,String>) {
            val hash=MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected,hash,"Stale mutable-size input $path")
        }
        val rows=File(root,"build/mutable-bytearray-size/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(names.toSet(),rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        for((stage,paths) in manifest["stages"] as Map<String,List<String>>)for(name in names) {
            val cases=rows.getValue(name).map { Row(it[1].toLong(),it[2].toLong(),it[3].toLong()) }
            val audit=Json.parse(File(root,"build/mutable-bytearray-size/$stage-$name.audit.json").readText()) as Map<*,*>
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
