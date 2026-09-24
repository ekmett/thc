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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

class BoxedArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("boxedSTRecursive", "boxedZero", "boxedSnapshot", "boxedClosure")
    private fun manifest() = Json.parse(File(root,"build/boxed-arrays/manifest.json").readText()) as Map<String,Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root,it).readText()) as Map<String,Any?> })
    private fun program(language: Language, module: Map<String,Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language,module) else BytecodeProgram(language,module)
    private fun context(inlining: Boolean) = org.graalvm.polyglot.Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation","false")
        .option("engine.MultiTier","false").option("engine.SingleTierCompilationThreshold","10000")
        .option("engine.CompilationFailureAction","Throw").option("compiler.CompilationTimeout","30")
        .option("compiler.MaximumGraalGraphSize","100000").option("compiler.Inlining",inlining.toString()).build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target),label)
    private fun model(name: String, input: Long): Long {
        val x=BigInteger.valueOf(input)
        return when(name) {
            "boxedSTRecursive" -> x*BigInteger.valueOf(44)+BigInteger.valueOf(350)
            "boxedSnapshot" -> x*BigInteger.valueOf(15)+BigInteger.valueOf(207)
            "boxedClosure" -> x*BigInteger.TWO+BigInteger.ONE
            else -> x
        }.toLong()
    }
    @Test fun publicSTArrayAndLazyElementsWithInlining() = native(true)
    @Test fun publicSTArrayAndLazyElementsWithoutInlining() = native(false)
    private fun native(inlining: Boolean) {
        val manifest=manifest()
        for (key in listOf("inputHashes","artifactHashes")) for ((path,expected) in manifest[key] as Map<String,String>) {
            val hash=MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected,hash,"Stale boxed-array fixture $path")
        }
        val rows=File(root,"build/boxed-arrays/oracle.tsv").readLines().map { it.split('\t') }.filter { it[0] in names }.groupBy { it[0] }
        assertEquals(names.toSet(),rows.keys)
        assertEquals((manifest["supportedNativeRows"] as Number).toInt(),rows.values.sumOf { it.size })
        for ((stage,paths) in manifest["stages"] as Map<String,List<String>>) for (name in names) {
            val cases=rows.getValue(name).map { it[1].toLong() to it[3].toLong() }
            cases.forEach { (x,y) -> assertEquals(model(name,x),y,"native/model $name($x)") }
            for (backend in listOf("ast","bytecode")) context(inlining).use { context ->
                context.initialize("thc");context.enter()
                try {
                    val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program=program(language,CoreModules.reachable(merged(paths),name)+( "instrument" to true),backend)
                    val function=context.asValue(EntryValue(program,name,1));val host=program.hostEntryTarget(1)
                    val label="$stage/$backend/$name/inlining=$inlining"
                    fun check(row: Pair<Long,Long>)=assertEquals(row.second,function.execute(row.first).asLong(),"$label/${row.first}")
                    cases.forEach(::check)
                    assertTrue(function.invokeMember("compile").asBoolean(),"$label install")
                    val original=program.entryTarget(name)
                    fun active()=NodeUtil.findAllNodeInstances(host.rootNode,DirectCallNode::class.java)
                        .filter { it.callTarget===original }.map { it.currentCallTarget as RootCallTarget }
                    val active=active()
                    assertTrue(active.isNotEmpty(),"$label observed warmed host-to-entry DirectCallNode")
                    for (row in cases.asReversed()) {
                        val before=(program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong()>before,"$label compiled guest entry")
                        assertEquals(active,active(),"$label active identities")
                        valid(host,"$label host");valid(original,"$label original");active.forEach { valid(it,"$label active") }
                    }
                    for (counter in listOf("unsupportedTraps","blackholes")) assertEquals(0L,(program.diagnostics().getValue(counter) as Number).toLong(),"$label/$counter")
                    assertEquals(0,language.handoffState.get().results.depth)
                    assertEquals(0L,language.handoffState.get().results.allocations,"$label saturated primitive results write directly to locals")
                } finally { context.leave() }
            }
        }
    }

    @Test fun storageSharesInitialReferencesSnapshotsAndFreezeIdentityWithCheckedDomains() {
        val initial=Any();val replacement=Any()
        for (size in listOf(0L,1L,2L,7L,256L)) {
            val array=ManagedArray.allocate(size,initial)
            assertEquals(Array<Any?>::class.java,array.javaClass);assertEquals(size,array.size.toLong())
            assertSame(array,ManagedArray.freeze(array));assertNotSame(array,ManagedArray.allocate(size,initial))
            for (i in 0 until size) assertSame(initial,ManagedArray.read(array,i))
            if (size>0) {
                val alias=array;val old=ManagedArray.read(array,0)
                ManagedArray.write(alias,0,replacement)
                assertSame(initial,old);assertSame(replacement,ManagedArray.read(array,0))
            }
            for (index in listOf(Long.MIN_VALUE,-1L,size,Long.MAX_VALUE)) {
                val before=array.copyOf()
                assertThrows(RuntimeFault::class.java) { ManagedArray.read(array,index) }
                assertThrows(RuntimeFault::class.java) { ManagedArray.write(array,index,replacement) }
                assertArrayEquals(before,array)
            }
        }
        for (size in listOf(Long.MIN_VALUE,-1L,Int.MAX_VALUE.toLong()+1,Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedArray.allocate(size,initial) }
        for (value in listOf(Any(),byteArrayOf(1),longArrayOf(1),arrayOf("wrong JVM component type")))
            assertThrows(RuntimeFault::class.java) { ManagedArray.require(value) }
    }

    @Test fun stateIsEvaluatedBeforeEffectsAndFailuresDoNotPublishDestinations() {
        val builder=FrameDescriptor.newBuilder();val slot=builder.addSlot(FrameSlotKind.Object,"out",null)
        val frame=Truffle.getRuntime().createVirtualFrame(emptyArray(),builder.build())
        val old=Any();val replacement=Any();val storage=ManagedArray.allocate(1,old)
        val events=mutableListOf<String>()
        fun operand(name: String, value: () -> Any?): Expr=object: Expr() {
            override fun execute(frame: VirtualFrame): Any? { events.add(name);return value() }
        }
        fun write(state: () -> Any?)=arrayExpression(ArrayOp.WRITE,CoreRepresentation.UNKNOWN,arrayOf(
            operand("array") { storage },operand("index") { 0L },operand("value") { replacement },operand("state",state)))
        assertThrows(RuntimeFault::class.java) { write { assertSame(old,storage[0]);0L }.execute(frame) }
        assertSame(old,storage[0]);assertEquals(listOf("array","index","value","state"),events)
        events.clear();assertSame(Unit,write { assertSame(old,storage[0]);Unit }.execute(frame))
        assertSame(replacement,storage[0]);assertEquals(listOf("array","index","value","state"),events)
        for (op in listOf(ArrayOp.NEW,ArrayOp.READ,ArrayOp.FREEZE)) {
            frame.setObject(slot,old)
            val operands=when(op) {
                ArrayOp.NEW -> arrayOf(operand("size") { 1L },operand("initial") { replacement })
                ArrayOp.READ -> arrayOf(operand("array") { storage },operand("index") { 0L })
                else -> arrayOf(operand("array") { storage })
            } + operand("state") { throw RuntimeFault("state failure") }
            assertThrows(RuntimeFault::class.java) { arrayExpression(op,CoreRepresentation.UNKNOWN,operands).executeTuple(frame,intArrayOf(slot),0) }
            assertSame(old,frame.getObject(slot),"$op must not publish before State")
        }
    }

    @Test fun bothBackendsRejectBadStateAndBoundsBeforeWritingWithoutForcingPayloads() {
        val state=mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)
        val array=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Unlifted)"),"evaluated" to true)
        val lifted=mapOf("kind" to "object","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to false)
        val long=mapOf("kind" to "long","primReps" to listOf("IntRep"),"evaluated" to true)
        val closure=mapOf("kind" to "closure","primReps" to listOf("BoxedRep (Just Lifted)"),"evaluated" to true)
        val params=listOf("array" to array,"index" to long,"value" to lifted,"state" to state).map { (id,rep) -> mapOf("id" to id,"lifted" to (id=="value"),"rep" to rep) }
        val body=listOf("app",listOf("prim","writeArray#"),params.map { listOf("var",it["id"],mapOf("rep" to it["rep"])) },listOf(false,false,true,false),false,false,mapOf("rep" to state))
        val module=mapOf("bindings" to listOf(mapOf("id" to "write","name" to "write","lifted" to true,"rep" to closure,"arity" to 4,
            "expr" to listOf("lam",params,body,mapOf("rep" to closure,"resultRep" to state)))))
        for (backend in listOf("ast","bytecode")) executionContext().use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program=program(language,module,backend);val original=Any()
                var entered=0
                val replacement=Thunk(object: com.oracle.truffle.api.nodes.RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? { entered++;throw RuntimeFault("stored bottom entered") }
                }.callTarget,null)
                val storage=ManagedArray.allocate(1,original)
                fun call(value: Any?,index: Long,state: Any?)=Calls.target(program.hostEntryTarget(4),arrayOf(program.entryValue("write"),arrayOf(value,index,replacement,state)))
                assertThrows(RuntimeFault::class.java) { call(storage,0,1L) };assertSame(original,storage[0])
                for (index in listOf(Long.MIN_VALUE,-1L,1L,Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { call(storage,index,Unit) };assertSame(original,storage[0])
                }
                assertThrows(RuntimeFault::class.java) { call(byteArrayOf(1),0,Unit) }
                assertSame(Unit,call(storage,0,Unit));assertSame(replacement,storage[0])
                assertEquals(0,entered,"$backend must never enter the written thunk")
                assertEquals(0,language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when(value) {
        is List<*> -> (if(value.firstOrNull()=="app") listOf(value as MutableList<Any?>) else emptyList())+value.flatMap(::applications)
        is Map<*,*> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    @Test fun exactShapesLevityAndSaturationAreRequiredInBothLoadModes() {
        val paths=(manifest()["stages"] as Map<String,List<String>>).getValue("pre")
        for (backend in listOf("ast","bytecode")) executionContext().use { context ->
            context.initialize("thc");context.enter()
            try {
                val language=TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in listOf(ArrayOp.NEW, ArrayOp.READ, ArrayOp.WRITE, ArrayOp.FREEZE, ArrayOp.INDEX)) for (mutation in 0..7) for (diagnostic in listOf(false,true)) {
                    val module=CoreModules.reachable(merged(paths),"boxedSTRecursive")
                    val app=applications(module).first { (it[1] as List<*>).take(2)==listOf("prim",operation.primitive) }
                    val args=app[2] as MutableList<Any?>;val flags=app[3] as MutableList<Any?>
                    val metadata=CoreRepresentations.metadata(app) as MutableMap<String,Any?>
                    when(mutation) {
                        0 -> { args.removeAt(args.lastIndex);flags.removeAt(flags.lastIndex);metadata.remove("callDemand") }
                        1 -> { args.add(args[0]);flags.add(false);metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> flags[0]=!(flags[0] as Boolean)
                        4 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["kind"]="unknown"
                        5 -> { val proof=metadata["rep"] as MutableMap<String,Any?>
                            proof.clear();proof.putAll(mapOf("kind" to "unknown","aggregate" to "unboxed-tuple","components" to emptyList<Any?>(),"primReps" to emptyList<String>(),"evaluated" to true)) }
                        6 -> { val proof=metadata["rep"] as MutableMap<String,Any?>
                            if(operation.tuple) {
                                val fields=proof["components"] as MutableList<Any?>
                                if(operation==ArrayOp.INDEX) fields.add(0,mapOf("kind" to "void","primReps" to emptyList<String>(),"evaluated" to true)) else fields.removeAt(0)
                            } else proof["primReps"]=listOf("IntRep") }
                        7 -> { val proof=metadata["rep"] as MutableMap<String,Any?>
                            if(operation.tuple) {
                                val fields=proof["components"] as MutableList<MutableMap<String,Any?>>
                                fields.last()["primReps"]=listOf("BoxedRep Nothing")
                                proof["primReps"]=listOf("BoxedRep Nothing")
                            } else (CoreRepresentations.metadata(args[2] as List<Any?>)!!["rep"] as MutableMap<String,Any?>)["primReps"]=listOf("BoxedRep (Just Unlifted)") }
                    }
                    assertThrows(RuntimeFault::class.java,{ program(language,module+("diagnosticUnsupported" to diagnostic),backend) },"$backend/${operation.primitive}/$mutation/$diagnostic")
                }
                for (operation in listOf(ArrayOp.NEW, ArrayOp.READ, ArrayOp.WRITE, ArrayOp.FREEZE, ArrayOp.INDEX)) {
                    val module=CoreModules.reachable(merged(paths),"boxedSTRecursive")
                    val app=applications(module).first { (it[1] as List<*>).take(2)==listOf("prim",operation.primitive) }
                    val primitive=(app[1] as List<*>).toList();app.clear();app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language,module,backend) }
                }
                for (name in manifest()["frontiers"] as List<String>) assertThrows(UnsupportedCore::class.java,{ program(language,CoreModules.reachable(merged(paths),name),backend) },"$backend frontier $name")
            } finally { context.leave() }
        }
    }
}
