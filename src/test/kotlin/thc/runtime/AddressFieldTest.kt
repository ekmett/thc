// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
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
import java.lang.reflect.Modifier
import java.security.MessageDigest

class AddressFieldTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun context(inline: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inline.toString()).build()
    private fun withLanguage(action: (Language) -> Unit) = context().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    private fun manifest() = Json.parse(File(root,"build/address-fields/manifest.json").readText()) as Map<String,Any?>
    private fun verify(manifest: Map<String,Any?>) {
        for (key in listOf("inputHashes", "artifactHashes")) for ((path,expected) in manifest[key] as Map<String,String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale address-field evidence: $path")
        }
    }
    @Test fun genuineAddressFieldsInline() = native(true)
    @Test fun genuineAddressFieldsResidual() = native(false)
    private fun native(inline: Boolean) {
        val manifest=manifest(); verify(manifest)
        val rows=File(root,"build/address-fields/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals((manifest["entries"] as List<String>).toSet(),rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        for ((stage,path) in manifest["stages"] as Map<String,String>) for ((name,inputs) in rows)
            for (backend in listOf("ast","bytecode")) context(inline).use { context ->
                context.initialize("thc");context.enter()
                try {
                    val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source=Json.parse(File(root,path).readText()) as Map<String,Any?>
                    val program=program(language,CoreModules.reachable(source,name)+( "instrument" to true),backend)
                    val value=context.asValue(EntryValue(program,name,1)); val host=program.hostEntryTarget(1)
                    val original=program.entryTarget(name); val label="$stage/$backend/$name/inline=$inline"
                    fun check(row: List<String>) = assertEquals(row[2].toLong(),value.execute(row[1].toLong()).asLong(),"$label/${row[1]}")
                    inputs.forEach(::check)
                    fun active()=NodeUtil.findAllNodeInstances(host.rootNode,DirectCallNode::class.java)
                        .filter { it.callTarget===original }.map { it.currentCallTarget as RootCallTarget }
                    val active=active(); assertTrue(active.isNotEmpty(),"$label observed host-to-entry call")
                    assertTrue(value.invokeMember("compile").asBoolean(),"$label installed")
                    for (row in inputs.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest entry")
                        assertEquals(active,active(),"$label active target identities")
                        valid(host,"$label host"); valid(original,"$label original"); active.forEach { valid(it,"$label active") }
                        released(language)
                    }
                    for(counter in listOf("unsupportedTraps","blackholes"))
                        assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),"$label/$counter")
                } finally { context.leave() }
            }
    }
    private fun proof(kind: String, reps: List<String>, evaluated: Boolean = true) =
        mapOf("kind" to kind,"primReps" to reps,"evaluated" to evaluated)
    private val address = proof("address",listOf("AddrRep"))
    private val long = proof("long",listOf("IntRep"))
    private val lazy = proof("data",listOf("BoxedRep (Just Lifted)"),false)
    private fun constructor(typed: Boolean = true): Map<String,Any?> = mapOf("id" to "Record","name" to "Record",
        "kind" to "boxed","arity" to 3,"tag" to 1,"fieldReps" to listOf(listOf("AddrRep"),listOf("IntRep"),listOf("BoxedRep (Just Lifted)")),
        "fieldLifted" to listOf(false,false,true),"strictFields" to listOf(false,false,false)) +
        if(typed) mapOf("fieldTypes" to listOf(address,long,lazy)) else emptyMap()
    private fun variable(id: String) = listOf("var",id)
    private fun binding(id: String, expr: List<Any?>, arity: Int = 0) = mapOf("id" to id,"name" to id,"lifted" to true,"arity" to arity,"expr" to expr)
    private fun module(typed: Boolean, partial: Boolean = false): Map<String,Any?> {
        val body=listOf("app",listOf("con","Record",3),listOf(variable("address"),listOf("lit","int","17"))+
            if(partial) emptyList() else listOf(variable("bottom")),if(partial) listOf(false,false) else listOf(false,false,true))
        return mapOf("schema" to 1,"ghc" to "9.14.1","constructors" to listOf(constructor(typed)),"bindings" to listOf(
            binding("bottom",variable("bottom")),binding("entry",listOf("lam",listOf(mapOf("id" to "address","lifted" to false)),body),1)))
    }
    @Test fun knownAddressStorageIsFinalAndPreciseEvenWithoutOptionalFieldTypes() = withLanguage { language ->
        for(typed in listOf(true,false)) {
            val fields=CoreFields(constructor(typed)); assertEquals(LiteralAddress::class.java,fields.referenceTypes[0])
            val layout=DataLayout(language,"Record$typed","Record",fields.storage,fields.referenceTypes)
            val address=LiteralAddress.fromHex("41ff0042").plus(1); val untouched=Any()
            val value=layout.create(arrayOf(address,17L,untouched))
            assertSame(address,layout.read(value,0)); assertSame(untouched,layout.read(value,2))
            assertEquals(255L,(layout.read(value,0) as LiteralAddress).indexChar(0))
            val stored=value.javaClass.declaredFields.sortedBy { it.name }
            assertEquals(listOf(LiteralAddress::class.java,java.lang.Long.TYPE,Any::class.java),stored.map { it.type })
            assertTrue(stored.all { Modifier.isFinal(it.modifiers) }); assertSame(layout,value.layout)
            for(fake in listOf(null,0L,Any(),byteArrayOf(65),java.nio.ByteBuffer.allocateDirect(8)))
                assertThrows(RuntimeFault::class.java) { layout.create(arrayOf(fake,17L,untouched)) }
        }
    }
    @Test fun bothBackendsRejectWrongCarriersAndKeepLazyNeighboursUnforced() = withLanguage { language ->
        for(typed in listOf(true,false)) for(backend in listOf("ast","bytecode")) {
            val program=program(language,module(typed),backend)
            fun call(value: Any?)=Calls.target(program.hostEntryTarget(1),arrayOf(program.entryValue("entry"),arrayOf(value)))
            val address=LiteralAddress.fromHex("ff0041")
            val result=call(address) as DataValue
            assertSame(address,result.layout.read(result,0));assertEquals(17L,result.layout.readLong(result,1))
            val bottom=program.entryValue("bottom") as Thunk
            assertSame(bottom,result.layout.read(result,2));assertEquals(0,bottom.state)
            repeat(12) { call(address) }
            program.entryTarget("entry").let {
                it.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(it,true)
                valid(it,"$backend/typed=$typed carrier check installed")
            }
            for(fake in listOf(0L,Any(),byteArrayOf(65),null)) assertThrows(RuntimeFault::class.java) { call(fake) }
            assertSame(address,(call(address) as DataValue).let { it.layout.read(it,0) });assertEquals(0,bottom.state)
            released(language)
        }
    }
    @Test fun constructorPartialApplicationRetainsAddressAndDefersLazyPayload() = withLanguage { language ->
        for(backend in listOf("ast","bytecode")) {
            val program=program(language,module(true,partial=true),backend); val address=LiteralAddress.fromHex("41")
            val pap=Calls.target(program.hostEntryTarget(1),arrayOf(program.entryValue("entry"),arrayOf(address))) as Closure
            assertSame(address,pap.supplied[0]); assertEquals(17L,pap.supplied[1])
            val bottom=program.entryValue("bottom") as Thunk; assertEquals(0,bottom.state)
            val result=Calls.target(program.hostEntryTarget(1),arrayOf(pap,arrayOf(bottom))) as DataValue
            assertSame(address,result.layout.read(result,0));assertSame(bottom,result.layout.read(result,2));assertEquals(0,bottom.state)
            released(language)
        }
    }
    @Test fun unliftedAddressComputationRunsBeforePartialApplicationPublication() = withLanguage { language ->
        val source=module(true,partial=true)
        val shifted=listOf("app",listOf("prim","plusAddr#"),
            listOf(listOf("lit","string-bytes","41"),variable("offset")),listOf(false,false))
        val partial=listOf("app",listOf("con","Record",3),listOf(shifted,listOf("lit","int","17")),listOf(false,false))
        val bindings=(source["bindings"] as List<Map<String,Any?>>).dropLast(1)+binding("entry",
            listOf("lam",listOf(mapOf("id" to "offset","lifted" to false)),partial),1)
        for(backend in listOf("ast","bytecode")) {
            val program=program(language,source+("bindings" to bindings),backend)
            fun call(offset: Long)=Calls.target(program.hostEntryTarget(1),arrayOf(program.entryValue("entry"),arrayOf(offset)))
            for(offset in listOf(-1L,3L,Long.MIN_VALUE,Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { call(offset) }
            val pap=call(1L) as Closure
            assertEquals(0L,(pap.supplied[0] as LiteralAddress).indexChar(0))
            val bottom=program.entryValue("bottom") as Thunk
            val result=Calls.target(program.hostEntryTarget(1),arrayOf(pap,arrayOf(bottom))) as DataValue
            assertEquals(0L,(result.layout.read(result,0) as LiteralAddress).indexChar(0));assertEquals(0,bottom.state)
            released(language)
        }
    }
    @Test fun tupleAddressesKeepTheirManagedCarrierAndReleaseResultLoans() = withLanguage { language ->
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(address, long),
            "primReps" to listOf("AddrRep", "IntRep"), "evaluated" to true)
        val shape = TupleShape(CoreRepresentations.parse(tuple), language)
        val builder = FrameDescriptor.newBuilder()
        val slots = IntArray(4) { builder.addSlot(FrameSlotKind.Illegal, "address tuple $it", null) }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), builder.build())
        val literal = LiteralAddress.fromHex("4100")
        fun field(value: Any?) = object : Expr() {
            init { representation = CoreRepresentations.parse(address) }
            override fun execute(frame: VirtualFrame): Any? = value
        }
        val number = object : Expr() {
            init { representation = CoreRepresentations.parse(long) }
            override fun execute(frame: VirtualFrame): Any = 17L
            override fun executeLong(frame: VirtualFrame): Long = 17L
        }
        TupleConstruct(shape, arrayOf(field(literal), number)).executeTuple(frame, slots, 0)
        val result = shape.finish(frame, slots)
        assertSame(TupleComplete, result)
        assertEquals(1, language.handoffState.get().results.retainedReferences())
        shape.consume(frame, result, slots, 2)
        assertSame(literal, frame.getObject(slots[2]))
        assertEquals(17L, frame.getLong(slots[3]))
        released(language)
        for (bad in listOf(null, 0L, Any(), byteArrayOf(65), java.nio.ByteBuffer.allocateDirect(8))) {
            assertThrows(RuntimeFault::class.java) { TupleConstruct(shape, arrayOf(field(bad), number)).executeTuple(frame, slots, 0) }
            val forged = shape.layout.create()
            shape.layout.setObject(forged, 0, bad)
            shape.layout.setLong(forged, 1, 17L)
            assertThrows(RuntimeFault::class.java) { shape.consume(frame, forged, slots, 2) }
            val pool = language.handoffState.get().results
            val loan = pool.acquire(shape.layout)
            shape.layout.setObject(loan, 0, bad)
            shape.layout.setLong(loan, 1, 17L)
            pool.complete(loan)
            assertThrows(RuntimeFault::class.java) { shape.consume(frame, TupleComplete, slots, 2) }
            released(language)
        }
    }
    @Test fun contradictoryFieldProofsAndAddressSumsStayRejected() = withLanguage { language ->
        val original=constructor()
        for(bad in listOf(
            original+("fieldTypes" to listOf(address+("kind" to "unknown"),long,lazy)),
            original+("fieldTypes" to listOf(address+("evaluated" to false),long,lazy)),
            original+("fieldTypes" to listOf(long,long,lazy)),
            original+("fieldLifted" to listOf(true,false,true)),
            (original-"fieldTypes")+("fieldLifted" to listOf(true,false,true)),
            (original-"fieldTypes")+("fieldLifted" to "invalid"))) {
            assertThrows(RuntimeFault::class.java) { CoreFields(bad) }
            for(backend in listOf("ast","bytecode")) assertThrows(RuntimeFault::class.java) {
                program(language,module(true)+( "constructors" to listOf(bad)),backend)
            }
        }
        val tuple=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to listOf(address),"primReps" to listOf("AddrRep"),"evaluated" to true)
        assertTrue(CoreRepresentations.parse(tuple).isTuple)
        // GHC places both AddrRep and IntRep payloads in the shared WordSlot;
        // the runtime still rejects address payloads before creating a carrier.
        val sum=mapOf("kind" to "unknown","aggregate" to "unboxed-sum","alternatives" to listOf(address,long),
            "primReps" to listOf("WordRep","WordRep"),"tagSlot" to 0,"alternativeSlots" to listOf(listOf(1),listOf(1)),"evaluated" to true)
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(sum) }
        // A valid tag-only sum occupies one word, but is not a scalar heap field.
        val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
        val empty=mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any>(),
            "primReps" to emptyList<String>(),"evaluated" to true)
        val tagOnly=sum+("alternatives" to listOf(state,empty))+("primReps" to listOf("WordRep"))+
            ("alternativeSlots" to listOf(emptyList<Int>(),emptyList<Int>()))
        assertTrue(CoreRepresentations.parse(tagOnly).isSum)
        assertThrows(UnsupportedCore::class.java) { CoreFields(mapOf("id" to "SumField","kind" to "boxed","arity" to 1,
            "fieldReps" to listOf(listOf("WordRep")),"fieldTypes" to listOf(tagOnly),
            "fieldLifted" to listOf(false),"strictFields" to listOf(false))) }
    }
}
