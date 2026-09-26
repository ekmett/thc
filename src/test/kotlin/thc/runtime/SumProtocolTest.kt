// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class SumProtocolTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @BeforeEach fun verifyEvidence() = verifySumEvidence(root)
    private fun module(extended: Boolean = false) = Json.parse(File(root,
        "build/${if (extended) "sum-result" else "sum-layout"}/pre-core/${if (extended) "SumResultAudit" else "SumLayoutAudit"}.json").readText()) as MutableMap<String, Any?>
    private fun binding(module: Map<String, Any?>, name: String) = (module["bindings"] as List<MutableMap<String, Any?>>).single { it["name"] == name }
    private fun shape(module: Map<String, Any?>, name: String) = ((binding(module,name)["expr"] as List<*>)[3] as Map<*, *>)["resultRep"] as MutableMap<String, Any?>
    private fun context(inline: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining",inline.toString()).option("engine.BackgroundCompilation","false")
        .option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()
    private fun withLanguage(inline: Boolean = true, action: (Language) -> Unit) = context(inline).use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun program(language: Language, module: Map<String, Any?>, entry: String, backend: String): ExecutableProgram {
        val linked=CoreModules.reachable(module,entry)
        return if (backend=="ast") Program(language,linked) else BytecodeProgram(language,linked)
    }
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target,true)
        valid(target)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val state=language.handoffState.get()
        assertEquals(0,state.results.depth); assertEquals(0,state.results.retainedReferences())
        assertEquals(0,state.arguments.depth); assertEquals(0,state.arguments.retainedReferences())
    }
    private fun walk(value: Any?): Sequence<List<Any?>> = sequence {
        if (value is List<*>) { yield(value as List<Any?>); for (child in value) yieldAll(walk(child)) }
        if (value is Map<*,*>) for (child in value.values) yieldAll(walk(child))
    }

    @Test fun exactLogicalAndPhysicalProofsRejectMalformedOrUnsupportedSums() {
        val mutants: List<(MutableMap<String,Any?>)->Unit> = listOf(
            { it["tagSlot"] = 1L }, { it["tagSlot"] = 0.0 }, { it.remove("tagSlot") },
            { it["alternativeSlots"] = listOf(listOf(0L),listOf(1L)) },
            { it["alternativeSlots"] = listOf(listOf(1.0),listOf(1L)) },
            { it["alternativeSlots"] = listOf(listOf(1L,1L),listOf(1L)) },
            { it["primReps"] = listOf("WordRep","WordRep","WordRep") },
            { it["components"] = emptyList<Any>() }, { it["kind"] = "long" })
        for (mutate in mutants) {
            val proof=shape(module(),"returnedSum"); mutate(proof)
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(proof) }
        }
        for (name in listOf("nestedSum","narrowWideSum","threeWaySum","runtimePolymorphic","levityPolymorphic",
                "abstractSumIdentity","abstractRuntimeSum","abstractAlternative","addressResult","vectorResult"))
            assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(shape(module(),name)) }
        val proof=shape(module(),"returnedSum"); proof["alternativeSlots"]=null
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(proof) }
        withLanguage { language ->
            assertThrows(RuntimeFault::class.java) { TupleShape(CoreRepresentation(CoreKind.LONG,true,true,listOf("WordRep")),language) }
        }
    }

    @Test fun malformedColdConstructorAndCaseProofsRejectOnBothBackends() = withLanguage { language ->
        for (backend in listOf("ast","bytecode")) for (mode in 0..9) {
            val module=module()
            val worker=binding(module,"returnedSum")["expr"] as List<Any?>
            val constructors=module["constructors"] as List<MutableMap<String,Any?>>
            val second=constructors.single { it["kind"]=="unboxed-sum" && it["tag"]==2L && it["sumArity"]==2L }
            val consumer=binding(module,"sumCase")["expr"] as MutableList<Any?>
            val sumCase=consumer[2] as MutableList<Any?>
            val alternatives=sumCase[3] as MutableList<MutableList<Any?>>
            when(mode) {
                0 -> second["sumArity"]=3L
                1 -> second["tag"]=0L
                2 -> second["arity"]=1.0
                3 -> alternatives[1][1]=alternatives[0][1]
                4 -> alternatives[1][2]=emptyList<String>()
                5 -> {
                    // Int# and Word# deliberately share the Long carrier at
                    // runtime; reject a real scalar carrier mismatch instead.
                    alternatives[1][3]=listOf("lit","float","1",mapOf("rep" to mapOf("kind" to "float","primReps" to listOf("FloatRep"),"evaluated" to true)))
                }
                6 -> alternatives[1][3]=listOf("void",mapOf("rep" to mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)))
                9 -> { alternatives[1][0]="default"; alternatives[1][2]=emptyList<String>() }
                8 -> alternatives[1][3]=listOf("var", binding(module,"lazySum")["id"], mapOf("rep" to mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)))
                7 -> {
                    val app=walk(worker).first { it.firstOrNull()=="app" && (it[1] as? List<*>)?.firstOrNull()=="con" } as MutableList<Any?>
                    app[3]=listOf(true)
                }
            }
            assertThrows(RuntimeFault::class.java, { program(language,module,"sumCase",backend) }, "$backend/mutation$mode")
        }
    }

    @Test fun inactiveReferencesAreClearedBeforeResidualCompletionAndShapeFailureReleasesLoan() = withLanguage(false) { language ->
        for (backend in listOf("ast","bytecode")) {
            val module=module(true); val program=program(language,module,"lazyLeaf",backend)
            val target=program.entryTarget(binding(module,"lazyLeaf")["id"] as String)
            val shape=TupleShape(CoreRepresentations.parse(shape(module,"lazyLeaf")),language)
            assertEquals(listOf("long","reference","long"),shape.layout.reps)
            val layout=FrameLayout(); val slots=IntArray(shape.width) { layout.bind("sum$it") }; val descriptor=layout.build()
            fun call(x: Long): Any? {
                val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),descriptor)
                shape.consume(frame,Calls.target(target,arrayOf(0L,x)),slots,0)
                assertEquals(if(x<0) 1L else 2L,frame.getLong(slots[0]))
                val reference=frame.getObject(slots[1])
                if(x<0) assertTrue(reference is Thunk) else { assertNull(reference); assertEquals(x+9,frame.getLong(slots[2])) }
                released(language); return reference
            }
            val lazy=call(-1)
            call(1); compile(target)
            for(x in listOf(-7L,7L,-1L,0L)) { val value=call(x); if(x<0) assertSame(lazy,value); valid(target) }
            assertEquals(0L,(program.diagnostics()["blackholes"] as Number).toLong())
            val wrong=TupleShape(CoreRepresentations.parse(shape(module(),"returnedSum")),language)
            val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),descriptor)
            val completion=Calls.target(target,arrayOf(0L,-1L))
            assertThrows(IllegalStateException::class.java) { wrong.consume(frame,completion,slots,0) }
            released(language)
        }
    }

    private object Barrier {
        @Volatile var requested=false
        var materialized=0
        @CompilerDirectives.TruffleBoundary fun observe()=requested
    }
    private class ResultSlots(width: Int) {
        val layout=FrameLayout()
        @field:CompilationFinal(dimensions=1) val fields=IntArray(width) { layout.bind("sum$it") }
    }
    private class DeoptConsumer(language: Language, private val shape: TupleShape, target: RootCallTarget,
        private val slots: ResultSlots=ResultSlots(shape.width)) : RootNode(language,slots.layout.build()) {
        @Child private var call=DirectCallNode.create(target).also { it.forceInlining() }
        override fun execute(frame: VirtualFrame): Any? {
            val result=Calls.direct(call,arrayOf(0L,frame.arguments[0]))
            if(Barrier.observe()) CompilerDirectives.transferToInterpreterAndInvalidate()
            if(CompilerDirectives.inInterpreter() && result is HandoffStorage) Barrier.materialized++
            shape.consume(frame,result,slots.fields,0)
            check(frame.getLong(slots.fields[0])==1L)
            return frame.getObject(slots.fields[1])
        }
    }
    @Test fun realSumProducerMaterializesFreshCarrierAcrossDeoptWithoutPoolLoan() = withLanguage { language ->
        for(backend in listOf("ast","bytecode")) {
            val module=module(true); val program=program(language,module,"lazyLeaf",backend)
            val target=program.entryTarget(binding(module,"lazyLeaf")["id"] as String)
            val shape=TupleShape(CoreRepresentations.parse(shape(module,"lazyLeaf")),language)
            val consumer=DeoptConsumer(language,shape,target).callTarget
            Barrier.requested=false
            val pointer=consumer.call(-1L)
            repeat(20) { assertSame(pointer,consumer.call(-7L)); released(language) }
            compile(consumer)
            assertSame(pointer,consumer.call(-4097L)); valid(consumer); released(language)
            val before=Barrier.materialized
            Barrier.requested=true
            try { assertSame(pointer,consumer.call(-8193L)) } finally { Barrier.requested=false }
            assertEquals(before+1,Barrier.materialized,"$backend requires actual post-return carrier materialization")
            assertEquals(0L,(program.diagnostics()["blackholes"] as Number).toLong())
            released(language)
        }
    }

    @Test fun publicHostRejectsKnownAliasToSumResult() {
        for(backend in listOf("ast","bytecode")) context().use { context ->
            val module=module(true)
            val source=binding(module,"produce")
            val alias=linkedMapOf<String,Any?>("id" to "sum-alias", "name" to "sumAlias", "arity" to 1L,
                "lifted" to true,"rep" to source["rep"],"expr" to listOf("var",source["id"],mapOf("rep" to source["rep"])))
            (module["bindings"] as MutableList<Any?>).add(alias)
            val error=assertThrows(PolyglotException::class.java) { context.eval("thc",Json.stringify(mapOf("modules" to listOf(module),"entry" to "sumAlias","backend" to backend))) }
            assertTrue(error.message.orEmpty().contains("unboxed-sum (host result)"),error.message)
        }
    }
    @Test fun sumAndTuplePayloadBindingsShadowOuterJoinWithoutChangingProjectionSlots() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) for (tuple in listOf(false, true)) {
            val module=module(tuple)
            val name=if(tuple) "pairedInputs" else "sumCase"
            val lambda=binding(module,name)["expr"] as MutableList<Any?>
            val body=lambda[2] as MutableList<Any?>
            val sumArm=(body[3] as List<List<Any?>>)[0]
            val bindingArm=if(tuple) ((sumArm[3] as List<Any?>)[3] as List<List<Any?>>)[0] else sumArm
            val binder=(bindingArm[4] as Map<String,Any?>)["binders"] as List<Map<String,Any?>>
            val id=(bindingArm[2] as List<String>)[0]
            val proof=binder[0]["rep"]
            if(!tuple) (bindingArm as MutableList<Any?>)[3]=listOf("var",id,mapOf("rep" to proof))
            val join=mapOf("id" to id,"name" to "shadowedJoin","lifted" to false,"rep" to proof,
                "expr" to listOf("lit","int","99",mapOf("rep" to proof)),"joinValueArity" to 0L,
                "joinResultRep" to proof,"info" to mapOf("joinArity" to 0L))
            lambda[2]=listOf("let",false,listOf(join),body,mapOf("rep" to proof))
            val program=program(language,module,name,backend)
            val target=program.entryTarget(binding(module,name)["id"] as String)
            val actual=Calls.target(target,if(tuple) arrayOf(0L,1L,3L) else arrayOf(0L,-1L))
            assertEquals(if(tuple) 24L else -1L,actual,"$backend/tuple=$tuple")
            released(language)
        }
    }
    @Test fun inferredSumCannotCrossOrdinaryArgumentBoundaryWhenOuterProofIsOmitted() = withLanguage { language ->
        for(backend in listOf("ast","bytecode")) for(explicitUnknown in listOf(false,true)) {
            val module=module()
            val lambda=binding(module,"sumCase")["expr"] as MutableList<Any?>
            val body=lambda[2] as MutableList<Any?>
            val sum=shape(module,"returnedSum")
            val scalar=sum["alternatives"].let { it as List<Any?> }[0]
            val closure=binding(module,"returnedSum")["rep"]
            val constructor=(module["constructors"] as List<Map<String,Any?>>).single {
                it["kind"]=="unboxed-sum" && it["tag"]==1L && it["sumArity"]==2L
            }["id"]
            val literal=listOf("lit","int","1",mapOf("rep" to scalar))
            val constructed=listOf("app",listOf("con",constructor,1L,mapOf("rep" to closure)),
                listOf(literal),listOf(false),true,true,mapOf("rep" to sum))
            for(arm in body[3] as List<MutableList<Any?>>) arm[3]=constructed
            val metadata=body[4] as MutableMap<String,Any?>
            if(explicitUnknown) metadata["rep"]=mapOf("kind" to "unknown","primReps" to null,"evaluated" to false)
            else metadata.remove("rep")
            val function=listOf("lam",listOf(mapOf("id" to "ignored","lifted" to false,"rep" to scalar)),literal,
                mapOf("rep" to closure,"resultRep" to scalar))
            lambda[2]=listOf("app",function,listOf(body),listOf(false),false,false,mapOf("rep" to scalar))
            val error=assertThrows(UnsupportedCore::class.java) { program(language,module,"sumCase",backend) }
            assertTrue(error.message.orEmpty().contains("unboxed-sum (argument)"),"$backend/$explicitUnknown: ${error.message}")
        }
    }
    @Test fun intrinsicScalarColdArmCannotSatisfyAggregateResultWithoutMetadata() = withLanguage { language ->
        for(backend in listOf("ast","bytecode")) for(genericCase in listOf(false,true)) {
            val module=module()
            val sum=shape(module,"returnedSum")
            val name=if(genericCase) "returnedSum" else "sumCase"
            val lambda=binding(module,name)["expr"] as MutableList<Any?>
            val body=lambda[2] as MutableList<Any?>
            val alternatives=body[3] as List<MutableList<Any?>>
            if(!genericCase) {
                val scalar=(sum["alternatives"] as List<Any?>)[0]
                val closure=binding(module,"returnedSum")["rep"]
                val constructor=(module["constructors"] as List<Map<String,Any?>>).single {
                    it["kind"]=="unboxed-sum" && it["tag"]==1L && it["sumArity"]==2L
                }["id"]
                alternatives[0][3]=listOf("app",listOf("con",constructor,1L,mapOf("rep" to closure)),
                    listOf(listOf("lit","int","1",mapOf("rep" to scalar))),listOf(false),true,true,mapOf("rep" to sum))
                (body[4] as MutableMap<String,Any?>)["rep"]=sum
                (lambda[3] as MutableMap<String,Any?>)["resultRep"]=sum
            }
            alternatives[1][3]=listOf("lit","int","99")
            val error=assertThrows(RuntimeFault::class.java) { program(language,module,name,backend) }
            assertTrue(error.message.orEmpty().contains("scalar and aggregate"),"$backend/generic=$genericCase: ${error.message}")
        }
        val proof=CoreRepresentations.parse(shape(module(),"returnedSum"))
        assertEquals(proof,proof.refine(CoreRepresentation.UNKNOWN),"A genuinely unknown legacy proof adds no constraint")
    }
    @Test fun defaultAndNonExhaustiveSumCasesConsumeAndReleaseResults() = withLanguage { language ->
        for(backend in listOf("ast","bytecode")) for(default in listOf(false,true)) {
            val module=module()
            val lambda=binding(module,"sumCase")["expr"] as List<Any?>
            val body=lambda[2] as List<Any?>
            val alternatives=body[3] as MutableList<List<Any?>>
            if(default) {
                val scalar=(shape(module,"returnedSum")["alternatives"] as List<Any?>)[0]
                alternatives[1]=listOf("default",null,emptyList<String>(),
                    listOf("lit","int","77",mapOf("rep" to scalar)),mapOf("binders" to emptyList<Any>()))
            } else alternatives.removeAt(1)
            val program=program(language,module,"sumCase",backend)
            val target=program.entryTarget(binding(module,"sumCase")["id"] as String)
            fun call(x: Long)=Calls.target(target,arrayOf(0L,x))
            assertEquals(-4L,call(-1))
            if(default) {
                assertEquals(77L,call(1));compile(target)
                assertEquals(-4L,call(-1));valid(target)
                assertEquals(77L,call(1));valid(target)
            } else {
                val failure=assertThrows(RuntimeFault::class.java) { call(1) }
                assertTrue(failure.message.orEmpty().contains("Non-exhaustive"),failure.message)
            }
            released(language)
        }
    }
}
