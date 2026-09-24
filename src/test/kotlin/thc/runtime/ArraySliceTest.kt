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
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ArraySliceTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private val operations=listOf(ArrayOp.CLONE,ArrayOp.FREEZE_COPY,ArrayOp.THAW)
    private val names=listOf("sliceSnapshots","lazySlices","closureSlices","zeroSlices","publicSlices")
    private fun context(inlining: Boolean=true)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun manifest()=Json.parse(File(root,"build/array-slices/manifest.json").readText()) as Map<String,Any?>
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
    @Test fun nativeSlicesAndPublicArrayConsumersWithInlining()=native(true)
    @Test fun nativeSlicesAndPublicArrayConsumersAcrossResidualCalls()=native(false)
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        for(kind in listOf("inputHashes","artifactHashes"))for((path,expected) in manifest[kind] as Map<String,String>) {
            val hash=MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected,hash,"Stale array-slice input $path")
        }
        val rows=File(root,"build/array-slices/oracle.tsv").readLines().map { it.split('\t') }.filter { it[0] in names }.groupBy { it[0] }
        assertEquals((manifest["supportedNativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        for((stage,paths) in manifest["stages"] as Map<String,List<String>>)for(name in names) {
            val cases=rows.getValue(name).map { it[1].toLong() to it[2].toLong() }
            val coefficients=mapOf("sliceSnapshots" to (56L to 1119L),"lazySlices" to (8L to 116L),"closureSlices" to (2L to 1L),"zeroSlices" to (1L to 0L),"publicSlices" to (15L to 207L))
            val (a,b)=coefficients.getValue(name)
            cases.forEach { (x,y)->assertEquals(a*x+b,y,"Native/model $name($x)") }
            val audit=Json.parse(File(root,"build/array-slices/$stage-$name.audit.json").readText()) as Map<*,*>
            assertEquals(true,audit["accepted"])
            for(backend in listOf("ast","bytecode"))context(inlining).use { context->
                context.initialize("thc");context.enter()
                try {
                    val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program=program(language,CoreModules.reachable(merged(paths),name)+("instrument" to true),backend)
                    val function=context.asValue(EntryValue(program,name,1));val host=program.hostEntryTarget(1);val original=program.entryTarget(name)
                    val label="$stage/$backend/$name/inlining=$inlining"
                    fun check(row: Pair<Long,Long>) { assertEquals(row.second,function.execute(row.first).asLong(),"$label/${row.first}");released(language) }
                    cases.forEach(::check)
                    val targets=activeTargets(host);assertTrue(targets.size>1,label)
                    targets.filter { it!==host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val allocations=language.handoffState.get().results.allocations
                    assertEquals(0L,allocations,"$label saturated primitive results use direct destinations")
                    for(row in cases.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong();check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest")
                        assertEquals(targets,activeTargets(host),"$label active identities");valid(original,label);targets.forEach { valid(it,label) }
                    }
                    assertEquals(allocations,language.handoffState.get().results.allocations,"$label pool reused")
                    for(counter in listOf("unsupportedTraps","blackholes"))assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),label)
                    println("ArraySlice PASS $label rows=${cases.size}")
                } finally { context.leave() }
            }
        }
    }
    @Test fun slicesCopyReferencesIntoIndependentStorageWithFullWidthBounds() {
        val a=Any();val unlifted=byteArrayOf(7);val b=Any();val source=arrayOf<Any?>(a,null,unlifted,b,a)
        for(start in 0..source.size)for(count in 0..source.size-start) {
            val copy=ManagedArray.slice(source,start.toLong(),count.toLong())
            assertNotSame(source,copy);assertEquals(count,copy.size);assertEquals(Array<Any?>::class.java,copy.javaClass)
            for(i in copy.indices)assertSame(source[start+i],copy[i])
            if(count>0) { val old=source[start];copy[0]=Any();assertSame(old,source[start]) }
        }
        assertNotSame(ManagedArray.slice(source,5,0),ManagedArray.slice(source,5,0))
        for((offset,count) in invalidRanges()) {
            val before=source.copyOf();assertThrows(RuntimeFault::class.java) { ManagedArray.slice(source,offset,count) };assertArrayEquals(before,source)
        }
    }
    private fun invalidRanges()=listOf(Long.MIN_VALUE to 0L,-1L to 0L,6L to 0L,0L to -1L,0L to Long.MIN_VALUE,0L to Long.MAX_VALUE,
        Long.MAX_VALUE to 1L,(1L shl 32) to 0L,1L to Long.MAX_VALUE,4L to 2L,5L to 1L)
    @Test fun stateFailureCannotPublishCopyAndOperandsAreEvaluatedInOrder() {
        val builder=FrameDescriptor.newBuilder();val slot=builder.addSlot(FrameSlotKind.Object,"destination",null)
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),builder.build());val sentinel=Any();val source=arrayOf<Any?>(Any())
        val events=mutableListOf<String>()
        fun operand(name: String,value: Any?)=object: Expr() { override fun execute(frame: VirtualFrame): Any? {events.add(name);return value} }
        for(operation in listOf(ArrayOp.FREEZE_COPY,ArrayOp.THAW)) {
            frame.setObject(slot,sentinel);events.clear()
            val expression=arrayExpression(operation,CoreRepresentation.UNKNOWN,arrayOf(operand("array",source),operand("offset",Long.MAX_VALUE),
                operand("count",Long.MAX_VALUE),object: Expr() { override fun execute(frame: VirtualFrame): Any? {events.add("state");throw RuntimeFault("state first")} }))
            val failure=assertThrows(RuntimeFault::class.java) { expression.executeTuple(frame,intArrayOf(slot),0) }
            assertEquals("state first",failure.message);assertSame(sentinel,frame.getObject(slot));assertEquals(listOf("array","offset","count","state"),events)
        }
    }
    private fun synthetic(operation: ArrayOp): Map<String,Any?> {
        val array=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
        val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
        val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
        val closure=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
        val proofs=listOf(array,long,long)+(if(operation.tuple)listOf(state) else emptyList())
        val params=proofs.mapIndexed { i,p->mapOf("id" to "p$i","lifted" to false,"rep" to p) }
        val tuple=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to listOf(state,array),"primReps" to array["primReps"],"evaluated" to true)
        val app=listOf("app",listOf("prim",operation.primitive),params.map { listOf("var",it["id"],mapOf("rep" to it["rep"])) },List(params.size){false},false,false,mapOf("rep" to if(operation.tuple)tuple else array))
        val body=if(!operation.tuple)app else listOf("case",app,"pair",listOf(listOf("data","Tuple2",listOf("state","copy"),listOf("var","copy",mapOf("rep" to array)),
            mapOf("binders" to listOf(mapOf("id" to "state","lifted" to false,"rep" to state),mapOf("id" to "copy","lifted" to false,"rep" to array))))),
            mapOf("rep" to array,"binder" to mapOf("id" to "pair","lifted" to false,"rep" to tuple)))
        return mapOf("instrument" to true,"constructors" to listOf(mapOf("id" to "Tuple2","kind" to "unboxed-tuple","arity" to 2,"fieldReps" to listOf(emptyList<String>(),listOf("BoxedRep (Just Unlifted)")),"fieldLifted" to listOf(false,false),"strictFields" to listOf(false,false))),
            "bindings" to listOf(mapOf("id" to "copy","name" to "copy","arity" to params.size,"lifted" to true,"rep" to closure,
                "expr" to listOf("lam",params,body,mapOf("rep" to closure,"resultRep" to array)))))
    }
    @Test fun bothBackendsPreserveLazyReferenceIdentityAndRejectInvalidStateAndSlices() {
        for(backend in listOf("ast","bytecode"))for(operation in operations)context().use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,synthetic(operation),backend);val entry=program.entryTarget("copy")
                var entered=0
                val bottom=Thunk(object: RootNode(null) { override fun execute(frame: VirtualFrame): Any? {entered++;throw RuntimeFault("copied thunk entered")} }.callTarget,null)
                val source=arrayOf<Any?>(bottom,Any(),byteArrayOf(1),bottom,null)
                fun call(offset: Long,count: Long,state: Any?=Unit,storage: Any?=source): Any? {
                    val args=if(operation.tuple)arrayOf<Any?>(0L,storage,offset,count,state) else arrayOf<Any?>(0L,storage,offset,count)
                    return Calls.target(entry,args)
                }
                for(start in 0..5)for(count in 0..5-start)call(start.toLong(),count.toLong())
                compile(entry)
                for(start in 0..5)for(count in 0..5-start) {
                    val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val result=ManagedArray.require(call(start.toLong(),count.toLong()))
                    assertNotSame(source,result);assertEquals(count,result.size)
                    for(i in result.indices)assertSame(source[start+i],result[i])
                    assertEquals(before+1,(program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    valid(entry,"$backend/$operation");released(language)
                }
                for((offset,count) in invalidRanges())assertThrows(RuntimeFault::class.java) { call(offset,count) }
                if(operation.tuple) {
                    val error=assertThrows(RuntimeFault::class.java) { call(Long.MAX_VALUE,Long.MAX_VALUE,17L) }
                    assertTrue(error.message.orEmpty().contains("zero-width scalar carrier"),error.message)
                }
                for(bad in listOf(Any(),byteArrayOf(1),arrayOf("bad component type")))assertThrows(RuntimeFault::class.java) { call(0,0,storage=bad) }
                assertEquals(0,entered);released(language)
            } finally {context.leave()}
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when(value) {
        is List<*> -> (if(value.firstOrNull()=="app")listOf(value as MutableList<Any?>) else emptyList())+value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun exactSliceSignaturesAndColdPublicFrontierRemainEnforced() {
        val paths=(manifest()["stages"] as Map<String,List<String>>).getValue("pre")
        for(backend in listOf("ast","bytecode"))context().use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(operation in operations)for(mutation in 0..10)for(diagnostic in listOf(false,true)) {
                    val module=CoreModules.reachable(merged(paths),"sliceSnapshots")
                    val app=applications(module).first { (it[1] as List<*>).take(2)==listOf("prim",operation.primitive) }
                    val args=app[2] as MutableList<Any?>;val flags=app[3] as MutableList<Any?>
                    val meta=CoreRepresentations.metadata(app) as MutableMap<String,Any?>
                    when(mutation) {
                        0->{args.removeAt(args.lastIndex);flags.removeAt(flags.lastIndex);meta.remove("callDemand")}
                        1->{args.add(args[0]);flags.add(false);meta.remove("callDemand")}
                        2->meta.remove("rep")
                        3->flags[0]=true
                        4->(CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Lifted)")
                        5,6,7,8->(CoreRepresentations.metadata(args[if(mutation%2==0)2 else 1] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf(if(mutation<7)"WordRep" else "Int64Rep")
                        9->meta["rep"]=mapOf("kind" to "object","primReps" to listOf("BoxedRep Nothing"),"evaluated" to true)
                        10->if(operation.tuple) {
                            val proof=meta["rep"] as MutableMap<String,Any?>
                            (proof["components"] as MutableList<Any?>)[0]=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","primReps" to emptyList<String>(),"components" to emptyList<Any?>(),"evaluated" to true)
                        } else meta["rep"]=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to listOf(meta["rep"]),"primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
                    }
                    assertThrows(RuntimeFault::class.java,{program(language,module+("diagnosticUnsupported" to diagnostic),backend)},"$operation/$mutation/$backend/$diagnostic")
                }
                for(operation in operations) {
                    val module=CoreModules.reachable(merged(paths),"sliceSnapshots");val app=applications(module).first { (it[1] as List<*>).take(2)==listOf("prim",operation.primitive) }
                    val primitive=(app[1] as List<*>).toList();app.clear();app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) {program(language,module,backend)}
                }
                assertThrows(UnsupportedCore::class.java) {program(language,CoreModules.reachable(merged(paths),"publicFreezeThaw"),backend)}
            } finally {context.leave()}
        }
    }
}
