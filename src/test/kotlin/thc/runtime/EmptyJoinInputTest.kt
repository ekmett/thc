@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
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

class EmptyJoinInputTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
    private val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
    private val closure=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
    private fun tuple(vararg children: Map<String,Any?>): Map<String,Any?> = mapOf("kind" to "unknown","aggregate" to "unboxed-tuple",
        "primReps" to children.flatMap { it["primReps"] as List<String> },"components" to children.toList(),"evaluated" to true)
    private val empty=tuple()
    private fun meta(proof: Map<String,Any?>)=mapOf("rep" to proof)
    private fun variable(id: String,proof: Map<String,Any?>)=listOf("var",id,meta(proof))
    private fun parameter(id: String,proof: Map<String,Any?>)=mapOf("id" to id,"name" to id,"lifted" to false,"rep" to proof)
    private fun lambda(params: List<Map<String,Any?>>,body: List<Any?>)=listOf("lam",params,body,mapOf("rep" to closure,"resultRep" to long))
    private fun binding(id: String,body: List<Any?>)=mapOf("id" to id,"name" to id,"lifted" to true,"rep" to closure,"expr" to body)
    private fun fixture(formal: Map<String,Any?> = empty, actual: List<Any?> = listOf("con","Empty",0,meta(empty)), flags: List<Boolean> = listOf(false,false)): Map<String,Any?> {
        val join=binding("finish",lambda(listOf(parameter("unit",formal),parameter("n",long)),variable("n",long))) +
            mapOf("joinValueArity" to 2,"joinResultRep" to long)
        val call=listOf("app",variable("finish",closure),listOf(actual,variable("x",long)),flags,false,false,mapOf("rep" to long,"callStrict" to listOf(true,true)))
        val body=listOf("let",false,listOf(join),call,meta(long))
        return mapOf("bindings" to listOf(binding("entry",lambda(listOf(parameter("x",long)),body))),"instrument" to true,
            "constructors" to listOf(mapOf("id" to "Empty","kind" to "unboxed-tuple","arity" to 0,
                "fieldReps" to emptyList<Any>(),"fieldLifted" to emptyList<Any>(),"strictFields" to emptyList<Any>())))
    }
    private fun context(inlining: Boolean)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun program(language: Language,module: Map<String,Any?>,backend: String): ExecutableProgram=
        if(backend=="ast") Program(language,module) else BytecodeProgram(language,module)
    private fun valid(target: RootCallTarget,label: String)=assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target),label)
    private fun compile(target: RootCallTarget) { target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true);valid(target,"installed") }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen=Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>());val result=mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if(!seen.add(target))return
            val node=target.rootNode
            val nodes=if(node is BytecodeRoot) listOf(node)+node.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind==Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(node)
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
        assertEquals(0,state.arguments.depth);assertEquals(0,state.arguments.retainedReferences())
        assertEquals(0,state.results.depth);assertEquals(0,state.results.retainedReferences())
    }
    private fun module(stage: String)=Json.parse(File(root,"build/empty-join-input/$stage-core/EmptyJoinInputAudit.json").readText()) as Map<String,Any?>
    @Test fun genuineEmptyJoinInputsWithInlining()=native(true)
    @Test fun genuineEmptyJoinInputsAcrossResidualCalls()=native(false)
    private fun native(inlining: Boolean) {
        val provenance=Json.parse(File(root,"build/empty-join-input/provenance.json").readText()) as Map<String,Any?>
        for(kind in listOf("inputs","artifacts"))for(row in provenance[kind] as List<Map<String,String>>) {
            val hash=MessageDigest.getInstance("SHA-256").digest(File(root,row.getValue("path")).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(row["sha256"],hash,"Stale empty-join evidence ${row["path"]}")
        }
        for(stage in listOf("pre","post")) {
            val audit=Json.parse(File(root,"build/empty-join-input/$stage-audit.json").readText()) as Map<*,*>
            assertEquals(true,audit["accepted"],"$stage strict native audit")
        }
        val rows=File(root,"build/empty-join-input/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(58,rows.values.sumOf { it.size });assertEquals(9,rows.size)
        // Each retained OPAQUE helper occurs once; effectCase also invokes its retained runRW lambda.
        // Preparation proves that lambda and the effectful actuals survive in both stages. Local joins are not guest entries.
        val entries=mapOf("branchCase" to 1L,"swapCase" to 2L,"swapDepth" to 1L,"mutualCase" to 2L,
            "mutualDepth" to 1L,"nestedCase" to 1L,"lazyCase" to 2L,"effectCase" to 3L,"throwCase" to 3L)
        for(stage in listOf("pre","post"))for((name,cases) in rows)for(backend in listOf("ast","bytecode"))context(inlining).use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,CoreModules.reachable(module(stage),name)+("instrument" to true),backend)
                val function=context.asValue(EntryValue(program,name,1));val original=program.entryTarget(name);val host=program.hostEntryTarget(1)
                val label="$stage/$backend/$name/inlining=$inlining"
                fun check(row: List<String>) { assertEquals(row[2].toLong(),function.execute(row[1].toLong()).asLong(),"$label/${row[1]}");released(language) }
                cases.forEach(::check)
                val active=activeTargets(host);assertTrue(active.size>1,"$label observed guest targets")
                active.filter { it!==host }.forEach(::compile)
                assertTrue(function.invokeMember("compile").asBoolean())
                for(row in cases.asReversed()) {
                    val before=program.diagnostics()["compiledEntries"] as Long;check(row)
                    assertEquals(before+entries.getValue(name),program.diagnostics()["compiledEntries"],"$label/${row[1]} exact guest entries")
                    assertEquals(active,activeTargets(host),"$label active identities");valid(original,label);active.forEach { valid(it,label) }
                }
                assertTrue(program.diagnostics()["localJoinTransfers"] as Long>0,label)
                for(counter in listOf("unsupportedTraps","blackholes","tailBounces","papAllocations"))assertEquals(0L,program.diagnostics()[counter],"$label/$counter")
                println("EmptyJoin PASS $label rows=${cases.size}")
            } finally { context.leave() }
        }
    }
    @Test fun emptyOperandRunsInLogicalOrderBeforeParallelMovesAndFailureTransfersNothing() {
        val layout=FrameLayout();val a=layout.bind("a");val b=layout.bind("b");val first=layout.bind("first");val last=layout.bind("last")
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),layout.build())
        val scalar=CoreRepresentation(CoreKind.LONG,true,true,listOf("IntRep"))
        val zero=CoreRepresentation(CoreKind.UNKNOWN,true,true,emptyList(),emptyList())
        val target=LocalJoinTarget(Any(),1,intArrayOf(a,-1,b),arrayOf(scalar,zero,scalar));val events=mutableListOf<String>();val metrics=Metrics(true)
        fun scalarRead(label: String,slot: Int)=object: Expr() {
            override fun execute(frame: VirtualFrame): Any=executeLong(frame)
            override fun executeLong(frame: VirtualFrame): Long { events.add(label);return frame.getLong(slot) }
        }
        for(throws in listOf(false,true)) {
            events.clear();FrameAccess.writeLong(frame,a,11);FrameAccess.writeLong(frame,b,29)
            val zeroExpr=object: Expr() {
                override fun execute(frame: VirtualFrame): Any=error("Empty tuple must not use a scalar carrier")
                override fun executeTuple(frame: VirtualFrame,slots: IntArray,offset: Int): Any? {
                    assertEquals(0,slots.size);assertEquals(0,offset);events.add("empty")
                    assertEquals(11L,frame.getLong(a));assertEquals(29L,frame.getLong(b))
                    if(throws)throw RuntimeFault("empty operand failure")
                    return null
                }
            }
            val call=LocalJoinCall(target,arrayOf(scalarRead("first",b),zeroExpr,scalarRead("last",a)),intArrayOf(first,-1,last),metrics)
            val before=metrics.localJoinTransfers
            if(throws) {
                assertThrows(RuntimeFault::class.java) { call.execute(frame) };assertEquals(listOf("first","empty"),events)
                assertEquals(11L,frame.getLong(a));assertEquals(29L,frame.getLong(b));assertEquals(before,metrics.localJoinTransfers)
            } else {
                assertSame(target.jump,assertThrows(LocalJoinJump::class.java) { call.execute(frame) })
                assertEquals(listOf("first","empty","last"),events);assertEquals(29L,frame.getLong(a));assertEquals(11L,frame.getLong(b))
                assertFalse(frame.isLong(first));assertFalse(frame.isLong(last));assertEquals(before+1,metrics.localJoinTransfers)
            }
        }
        assertEquals(8,frame.frameDescriptor.numberOfSlots,"Four fixed frame slots plus two scalar formals and two scalar temporaries")
    }
    @Test fun exactEmptyInputHasNoLocalSlotOnEitherBackendAndKeepsLogicalArity() {
        for(backend in listOf("ast","bytecode"))context(false).use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p=program(language,fixture(),backend)
                assertEquals(37L,Calls.target(p.hostEntryTarget(1),arrayOf(p.entryValue("entry"),arrayOf(37L))))
                if(p is Program) {
                    val fd=p.entryTarget("entry").rootNode.frameDescriptor
                    val names=(0 until fd.numberOfSlots).map { fd.getSlotName(it) }
                    assertFalse("unit" in names);assertFalse("<join argument 0>" in names);assertTrue("<join argument 1>" in names)
                } else {
                    val dump=(p as BytecodeProgram).bytecodeDump()
                    assertFalse(dump.contains("join operand 0"));assertTrue(dump.contains("join operand 1"))
                }
                assertEquals(1L,p.diagnostics()["localJoinTransfers"]);released(language)
                val prototype=fixture()
                for(count in listOf(0,1,3)) {
                    val m=Json.parse(Json.stringify(prototype)) as MutableMap<String,Any?>
                    val body=(((m["bindings"] as List<Map<String,Any?>>)[0]["expr"] as List<*>)[2]) as MutableList<Any?>
                    val call=body[3] as MutableList<Any?>
                    if(count==0)body[3]=variable("finish",closure)
                    else {
                        val args=call[2] as MutableList<Any?>;val flags=call[3] as MutableList<Any?>
                        if(count==1) { args.removeLast();flags.removeLast() } else { args.add(variable("x",long));flags.add(false) }
                    }
                    assertThrows(RuntimeFault::class.java) { program(language,m,backend) }
                }
            } finally { context.leave() }
        }
    }
    @Test fun shapeLevityAndUnknownProofsRejectAtLoadWithoutWideningJoinFrontier() {
        for(backend in listOf("ast","bytecode"))context(false).use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(formal in listOf(tuple(state),tuple(empty),tuple(long),empty+("components" to null)))
                    assertThrows(RuntimeFault::class.java) { program(language,fixture(formal),backend) }
                for(actual in listOf(listOf("void",meta(state)),listOf("void"),listOf("lit","int","1",meta(long))))
                    assertThrows(RuntimeFault::class.java) { program(language,fixture(actual=actual),backend) }
                assertThrows(RuntimeFault::class.java) { program(language,fixture(formal=state),backend) }
                for(flags in listOf(listOf(true,false),emptyList()))
                    assertThrows(RuntimeFault::class.java) { program(language,fixture(flags=flags),backend) }
                val bad=Json.parse(Json.stringify(fixture())) as MutableMap<String,Any?>
                val let=(((bad["bindings"] as List<Map<String,Any?>>)[0]["expr"] as List<*>)[2]) as List<*>
                val join=(let[2] as List<Map<String,Any?>>)[0]["expr"] as List<*>
                ((join[1] as List<MutableMap<String,Any?>>)[0])["lifted"]=true
                assertThrows(RuntimeFault::class.java) { program(language,bad,backend) }
                val capturing=fixture()
                val capturedJoin=binding("capture",lambda(listOf(parameter("n",long)),listOf("case",variable("held",empty),"forced",
                    listOf(listOf("default",null,emptyList<String>(),variable("n",long))),
                    mapOf("rep" to long,"binder" to parameter("forced",empty))))) + mapOf("joinValueArity" to 1,"joinResultRep" to long)
                val captureCall=listOf("app",variable("capture",closure),listOf(variable("x",long)),listOf(false),false,false,meta(long))
                val region=listOf("let",false,listOf(capturedJoin),captureCall,meta(long))
                val body=listOf("case",listOf("con","Empty",0,meta(empty)),"held",listOf(listOf("default",null,emptyList<String>(),region)),
                    mapOf("rep" to long,"binder" to parameter("held",empty)))
                assertThrows(UnsupportedCore::class.java) { program(language,capturing+("bindings" to listOf(binding("entry",lambda(listOf(parameter("x",long)),body)))),backend) }
            } finally { context.leave() }
        }
    }
    @Test fun genuineThrowingEmptyOperandIsNotErasedAndRecoveryReleasesResults() {
        for(stage in listOf("pre","post"))for(backend in listOf("ast","bytecode"))context(false).use { context->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p=program(language,CoreModules.reachable(module(stage),"throwCase")+("instrument" to true),backend)
                fun call(x: Long)=Calls.target(p.hostEntryTarget(1),arrayOf(p.entryValue("throwCase"),arrayOf(x)))
                assertEquals(108L,call(7));assertEquals(1L,p.diagnostics()["localJoinTransfers"])
                assertThrows(GuestException::class.java) { call(-1) };released(language)
                assertEquals(1L,p.diagnostics()["localJoinTransfers"],"Throwing operand must not count a transfer")
                assertEquals(108L,call(7));released(language);assertEquals(2L,p.diagnostics()["localJoinTransfers"])
            } finally { context.leave() }
        }
    }
}
