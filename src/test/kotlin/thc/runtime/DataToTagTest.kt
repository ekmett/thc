@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
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

class DataToTagTest {
    private val root=File(System.getProperty("thc.projectRoot"))
    private fun context(inlining: Boolean=true)=Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
        .option("engine.CompilationFailureAction","Throw").option("compiler.Inlining",inlining.toString()).build()
    private fun module(stage: String)=Json.parse(File(root,"build/data-to-tag/$stage/core/DataToTagAudit.json").readText()) as Map<String,Any?>
    private fun program(language: Language,module: Map<String,Any?>,backend: String): ExecutableProgram=
        if(backend=="ast") Program(language,module) else BytecodeProgram(language,module)
    private fun valid(target: RootCallTarget,label: String)=assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target),label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true);valid(target,"installed")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen=Collections.newSetFromMap(IdentityHashMap<RootCallTarget,Boolean>());val result=mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if(!seen.add(target)) return
            val body=target.rootNode
            val nodes=if(body is BytecodeRoot) listOf(body)+body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind==Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for(call in nodes.flatMap { NodeUtil.findAllNodeInstances(it,DirectCallNode::class.java) }) {
                val active=call.currentCallTarget as? RootCallTarget ?: continue
                if(active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry);return result
    }
    private fun count(p: ExecutableProgram)=(p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state=language.handoffState.get()
        assertEquals(0,state.arguments.depth);assertEquals(0,state.results.depth)
        assertEquals(0,state.arguments.retainedReferences());assertEquals(0,state.results.retainedReferences())
    }
    private fun evidence(): Map<String,Any?> {
        val m=Json.parse(File(root,"build/data-to-tag/manifest.json").readText()) as Map<String,Any?>
        for(key in listOf("inputHashes","artifactHashes")) for((path,expected) in m[key] as Map<String,String>) {
            val actual=MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected,actual,"Stale dataToTag evidence: $path")
        }
        assertEquals(293L,m["nativeRows"]);assertEquals(1L,m["nativeExceptionRows"])
        return m
    }
    @Test fun nativeFamiliesWithInlining()=native(true)
    @Test fun nativeFamiliesAcrossResidualCalls()=native(false)
    private fun native(inlining: Boolean) {
        evidence()
        val rows=File(root,"build/data-to-tag/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(293,rows.values.sumOf { it.size })
        for(stage in listOf("pre","post")) for(backend in listOf("ast","bytecode")) for((name,selected) in rows) context(inlining).use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p=program(language,CoreModules.reachable(module(stage),name)+("instrument" to true),backend)
                val entry=p.entryTarget(name);val host=p.hostEntryTarget(1);val function=context.asValue(EntryValue(p,name,1))
                val label="$stage/$backend/$name/inlining=$inlining"
                fun check(row: List<String>)=assertEquals(row[2].toLong(),function.execute(row[1].toLong()).asLong(),"$label/${row[1]}")
                selected.forEach(::check)
                val targets=activeTargets(host)
                assertTrue(targets.size>1,"$label observed guest target")
                targets.filter { it!==host }.forEach(::compile);assertTrue(function.invokeMember("compile").asBoolean())
                for(row in selected.asReversed()) {
                    val before=count(p);check(row)
                    assertTrue(count(p)>before,"$label/${row[1]} compiled guest entry")
                    assertEquals(targets,activeTargets(host),"$label target identities")
                    valid(entry,"$label original");targets.forEach { valid(it,"$label active") };released(language)
                }
                assertEquals(0L,(p.diagnostics().getValue("blackholes") as Number).toLong())
                assertEquals(0L,(p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                if(name=="forceCase") {
                    assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { function.execute(-1L) }
                    assertEquals(1L,(p.diagnostics().getValue("blackholes") as Number).toLong());released(language)
                }
            } finally { context.leave() }
        }
    }
    private val data=mapOf("kind" to "data","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to false)
    private val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
    private val closure=data+("kind" to "closure")+("evaluated" to true)
    private fun synthetic(): MutableMap<String,Any?> {
        val family=mapOf("typeConstructor" to "test:T","constructors" to listOf("A","B"),"smallFamilyLimit" to 7,"smallFamily" to true)
        val field=data+("kind" to "object")
        val cons=listOf("A","B").mapIndexed { index,id -> mapOf("id" to id,"name" to id,"arity" to 1,"tag" to index+1,
            "kind" to "boxed","fieldReps" to listOf(listOf("BoxedRep (Just Lifted)")),"fieldTypes" to listOf(field),
            "fieldLifted" to listOf(true),"strictFields" to listOf(false),"dataToTagFamily" to family) }
        val app=listOf("app",listOf("prim","dataToTagSmall#",mapOf("rep" to closure)),
            listOf(listOf("var","x",mapOf("rep" to data))),listOf(true),false,false,mapOf("rep" to long,"dataToTagFamily" to family))
        val binding=mapOf("id" to "tag","name" to "tag","lifted" to true,"rep" to closure,
            "expr" to listOf("lam",listOf(mapOf("id" to "x","name" to "x","lifted" to true,"rep" to data)),app,mapOf("rep" to closure,"resultRep" to long)))
        val bottom=mapOf("id" to "bottom","name" to "bottom","lifted" to true,"rep" to field,"expr" to listOf("var","bottom",mapOf("rep" to field)))
        val values=listOf("A","B").map { id -> mapOf("id" to "value$id","name" to "value$id","lifted" to true,"rep" to data,
            "expr" to listOf("app",listOf("con",id,1),listOf(listOf("var","bottom",mapOf("rep" to field))),listOf(true),true,true,mapOf("rep" to data))) }
        return Json.parse(Json.stringify(mapOf("schema" to 1,"ghc" to "9.14.1","bindings" to listOf(binding,bottom)+values,"constructors" to cons,"instrument" to true))) as MutableMap<String,Any?>
    }
    private fun app(m: Map<String,Any?>)=((m["bindings"] as List<Map<String,Any?>>).first()["expr"] as List<Any?>)[2] as MutableList<Any?>
    @Test fun demandedOuterValueLeavesFieldsLazyAndReturnsExactCompiledTags() {
        for(backend in listOf("ast","bytecode")) context().use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p=program(language,synthetic(),backend);val target=p.entryTarget("tag")
                val values=listOf("A","B").map { id -> Calls.target(p.hostEntryTarget(0),arrayOf(p.entryValue("value$id"),emptyArray<Any?>())) as DataValue }
                for((index,value) in values.withIndex()) assertEquals(index.toLong(),Calls.target(target,arrayOf(0L,value)))
                compile(target)
                for((index,value) in values.withIndex()) {
                    val before=count(p);assertEquals(index.toLong(),Calls.target(target,arrayOf(0L,value)))
                    assertEquals(before+1,count(p));valid(target,backend)
                    val payload=value.layout.read(value,0) as Thunk;assertEquals(0,payload.state)
                }
                var demanded=0
                val thunk=Thunk(object: RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any { demanded++;return values[1] }
                }.callTarget,null)
                assertEquals(1L,Calls.target(target,arrayOf(0L,thunk)));assertEquals(1,demanded)
                assertEquals(1L,Calls.target(target,arrayOf(0L,thunk)));assertEquals(1,demanded)
                val failure=Thunk(object: RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any = throw RuntimeFault("demanded bottom")
                }.callTarget,null)
                assertThrows(RuntimeFault::class.java) { Calls.target(target,arrayOf(0L,failure)) };released(language)
                assertEquals(0L,Calls.target(target,arrayOf(0L,values[0])))
                val other=DataLayout(language,"Other","Other",emptyArray()).allocate()
                for(value in listOf(17L,Unit,LiteralAddress.fromHex("6100"),other))
                    assertThrows(RuntimeException::class.java) { Calls.target(target,arrayOf(0L,value)) }
                released(language)
            } finally { context.leave() }
        }
    }
    @Test fun exactFamilyVariantAndMetadataRequiredAtLoad() {
        for(backend in listOf("ast","bytecode")) context().use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(variant in listOf("missing","variant","small","limit","float-limit","empty","reverse","missing-con","tag","float-tag","newtype","fields","truncated","extra","closure","word","generic","lifted","result","function","zero","two","bare")) {
                    val m=synthetic();val app=app(m);val meta=app[6] as MutableMap<String,Any?>
                    val family=meta["dataToTagFamily"] as MutableMap<String,Any?>
                    val cons=m["constructors"] as MutableList<MutableMap<String,Any?>>
                    when(variant) {
                        "missing" -> meta.remove("dataToTagFamily")
                        "variant" -> (app[1] as MutableList<Any?>)[1]="dataToTagLarge#"
                        "small" -> family["smallFamily"]=false
                        "limit" -> family["smallFamilyLimit"]=3L
                        "float-limit" -> family["smallFamilyLimit"]=7.0
                        "empty" -> family["constructors"]=emptyList<String>()
                        "reverse" -> (family["constructors"] as MutableList<*>).reverse()
                        "missing-con" -> cons.removeAt(1)
                        "tag" -> cons[0]["tag"]=2L
                        "float-tag" -> cons[0]["tag"]=1.0
                        "newtype" -> cons[0]["kind"]="newtype"
                        "fields" -> cons[0]["fieldReps"]=emptyList<Any?>()
                        "truncated" -> { family["constructors"]=listOf("A");cons[0]["dataToTagFamily"]=family.toMap() }
                        "extra" -> cons.add(cons[0].toMutableMap().apply { put("id","Extra") })
                        "closure","generic","word" -> {
                            val proof=when(variant) { "closure" -> closure; "generic" -> data+("primReps" to listOf("BoxedRep Nothing"));else -> long+("primReps" to listOf("WordRep")) }
                            ((app[2] as List<List<Any?>>)[0][2] as MutableMap<String,Any?>)["rep"]=proof
                            if(variant=="generic") {
                                val lambda=(m["bindings"] as List<Map<String,Any?>>).first()["expr"] as List<Any?>
                                (lambda[1] as List<MutableMap<String,Any?>>)[0]["rep"]=proof
                            }
                        }
                        "lifted" -> app[3]=listOf(false)
                        "result" -> meta["rep"]=long+("primReps" to listOf("WordRep"))
                        "function" -> (app[1] as MutableList<Any?>)[2]=mapOf("rep" to emptyList<Any?>())
                        "zero" -> { app[2]=emptyList<Any?>();app[3]=emptyList<Boolean>() }
                        "two" -> { app[2]=(app[2] as List<Any?>)+(app[2] as List<Any?>);app[3]=listOf(true,true) }
                        "bare" -> (((m["bindings"] as List<Map<String,Any?>>).first()["expr"]) as MutableList<Any?>)[2]=app[1]
                    }
                    assertThrows(RuntimeException::class.java,{ program(language,m,backend) },"$backend/$variant")
                }
                for(proof in listOf(null, mapOf("kind" to "unknown","primReps" to null,"evaluated" to false),
                    data+("kind" to "object")+("primReps" to listOf("BoxedRep Nothing")))) {
                    val m=synthetic();val app=app(m)
                    app[2]=listOf(listOf("var","x")+if(proof==null) emptyList() else listOf(mapOf("rep" to proof)))
                    val p=program(language,m,backend)
                    val value=Calls.target(p.hostEntryTarget(0),arrayOf(p.entryValue("valueA"),emptyArray<Any?>()))
                    assertEquals(0L,Calls.target(p.entryTarget("tag"),arrayOf(0L,value)))
                }
            } finally { context.leave() }
        }
    }
    @Test fun genuineInvalidFamilyAndBarePrimitiveFrontiersStayRejected() {
        val manifest=evidence()
        for(stage in listOf("pre","post")) for(backend in listOf("ast","bytecode")) context().use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for(name in manifest["frontiers"] as List<String>)
                    assertThrows(RuntimeException::class.java,{ program(language,CoreModules.reachable(module(stage),name),backend) },"$stage/$backend/$name")
            } finally { context.leave() }
        }
    }
    @Test fun typedDataOperandExecutesExactlyOnceBeforeTagSelection()=context().use { context ->
        context.initialize("thc");context.enter()
        try {
            val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val layout=DataLayout(language,"A","A",emptyArray());val value=layout.allocate();var calls=0;var fail=false
            val operand=object: Expr() {
                override fun execute(frame: VirtualFrame): Any=error("Generic operand path must not execute")
                override fun executeDataValue(frame: VirtualFrame): DataValue { calls++;if(fail) throw RuntimeFault("operand failed");return value }
            }
            val node=DataToTag(DataTagFamily(arrayOf(layout)),operand)
            val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),FrameDescriptor.newBuilder().build())
            assertEquals(0L,node.executeLong(frame));assertEquals(1,calls)
            fail=true;assertThrows(RuntimeFault::class.java) { node.executeLong(frame) };assertEquals(2,calls)
        } finally { context.leave() }
    }
}
