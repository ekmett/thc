// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.BytecodeLabel
import com.oracle.truffle.api.bytecode.BytecodeLocal
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.source.SourceSection
import thc.Language

/**
 * Core lowers to real Bytecode DSL control flow and primitive operations. The parser is
 * replayable: targets, layouts and literal constants are prepared once; bytecode locals
 * and labels are created afresh on every replay. Runtime values and application use the
 * same selective captures, lazy update protocol and PAP convention as the AST backend.
 */
class BytecodeProgram internal constructor(private val language: Language, moduleData: Map<String, Any?>,
                                           private val checkpoint: BytecodeCheckpoint?,
                                           private val enableAsync: Boolean) : ExecutableProgram {
    init { thc.CoreForeignArtifacts.requireExecutableInput(moduleData) }
    constructor(language: Language, moduleData: Map<String, Any?>) : this(language, moduleData, null, false)
    constructor(language: Language, moduleData: Map<String, Any?>, enableAsync: Boolean) :
        this(language, moduleData, null, enableAsync)
    internal constructor(language: Language, moduleData: Map<String, Any?>, checkpoint: BytecodeCheckpoint) :
        this(language, moduleData, checkpoint, false)
    private val resumable = checkpoint != null || enableAsync
    private val stackTargetLayout = moduleData["targetLayout"]
    private val callDemandsEnabled = java.lang.Boolean.getBoolean(CALL_DEMANDS_PROPERTY)
    private val sources = CoreSources(moduleData)
    private val metrics = Metrics(moduleData["instrument"] != false)
    private val diagnosticUnsupported = moduleData["diagnosticUnsupported"] == true
    private val deferredUnsupported = linkedSetOf<String>()
    private val bindings = moduleData["bindings"] as? List<Map<String, Any?>> ?: throw RuntimeFault("Missing bindings")
    private val constructors = (moduleData["constructors"] as? List<Map<String, Any?>> ?: emptyList()).associateBy { it["id"] as String }
    private val dataLayouts = mutableMapOf<String, DataLayout>()
    private val globals = bindings.associate { it["id"] as String to GlobalBinding(it["name"] as String) }
    private val indices = bindings.withIndex().associate { it.value["id"] as String to it.index }
    private val names = bindings.withIndex().groupBy({ it.value["name"] as String }, { it.index })
    private val globalProofs = bindings.associate { binding ->
        val rhs = binding["expr"] as List<Any?>
        val proof = CoreRepresentations.binder(binding)
        binding["id"] as String to if (diagnosticUnsupported) CoreRepresentation.UNKNOWN
        else proof.copy(evaluated = binding["lifted"] == false || rhs[0] in listOf("lam", "lit", "con", "void"))
    }
    private val globalEntries = bindings.associate { it["id"] as String to CoreEntries.binding(it) }
    private val globalArityCertificates = bindings.associate { it["id"] as String to CoreApplicationCertificates.binding(it) }
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    private val roots = arrayListOf<BytecodeRoot>()
    private var nextLocal = 0
    private var localJoinCount = 0

    private data class Local(val id: Int, val name: String, val primitive: Boolean,
                             val proof: CoreRepresentation = CoreRepresentation.UNKNOWN,
                             val cell: Boolean = false, val entry: BooleanArray? = null,
                             val arityCertificate: CoreApplicationCertificates.Arity? = null) {
        // The denoted value can be primitive while a pre-publication capture
        // still holds its recursive cell. Raw captures must retain that cell.
        val directLong: Boolean get() = !cell && proof.isLong && proof.evaluated
        val directFloat: Boolean get() = !cell && proof.isFloat && proof.evaluated
        val directDouble: Boolean get() = !cell && proof.isDouble && proof.evaluated
    }
    private class FunctionContext(val formalArity: Int, val entryStrict: BooleanArray = BooleanArray(formalArity)) {
        var inputLayout: ArgumentLayout? = null
        var typedInput: TypedInputLayout? = null
        var typedArguments: List<Pair<Int, Local>> = emptyList()
        var arguments: List<Local?> = emptyList()
        var captures: List<Local> = emptyList()
        var captureLayout: CaptureLayout? = null
        var mayLoop = false
        var leadingCaseReturn: LeadingCaseReturn? = null
        var tuple: TupleShape? = null
    }
    private class Scope(val function: FunctionContext, val locals: MutableMap<String, Local> = linkedMapOf(),
                        val joins: MutableMap<String, JoinTarget> = linkedMapOf(),
                        val source: CoreSourceLocation? = null,
                        val tuples: MutableMap<String, Pair<CoreRepresentation, List<Local>>> = linkedMapOf()) {
        fun child() = Scope(function, LinkedHashMap(locals), LinkedHashMap(joins), source, LinkedHashMap(tuples))
        fun withSource(location: CoreSourceLocation?) = Scope(function, locals, joins, location, tuples)
        fun bindLocal(name: String, value: Local) { locals[name] = value; joins.remove(name); tuples.remove(name) }
        fun bindVoid(name: String, proof: CoreRepresentation) = bindLocal(name, Local(-1, name, false, proof.copy(evaluated = true)))
        fun bindTuple(name: String, proof: CoreRepresentation, fields: List<Local>) {
            tuples[name] = proof to fields; locals.remove(name); joins.remove(name)
        }
        fun bindJoin(name: String, value: JoinTarget) { joins[name] = value; locals.remove(name); tuples.remove(name) }
    }
    private class JoinRegion
    private class JoinTarget(val region: JoinRegion, val index: Int, val parameters: List<Map<String, Any?>>,
                             val locals: List<Local?>, val entryStrict: BooleanArray, val result: CoreRepresentation)
    private class JoinEmission(val selector: BytecodeLocal?, val next: BytecodeLabel?, val labels: List<BytecodeLabel>) {
        var emittedIndex = -1
    }
    private class Emission(val builder: BytecodeRootGen.Builder) {
        val locals = mutableMapOf<Int, BytecodeLocal>()
        var checkpointRootEntry: BytecodeLocal? = null
        var continueLabel: BytecodeLabel? = null
        var typedInputSlots: BytecodeTypedInputSlots? = null
        val joins = mutableMapOf<JoinRegion, JoinEmission>()
    }
    private fun interface Expression {
        fun emit(emission: Emission)
        fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) { throw RuntimeFault("Tuple expression lacks a destination writer") }
        val proof: CoreRepresentation get() = CoreRepresentation.UNKNOWN
        val source: CoreSourceLocation? get() = null
        val loweredCase: Boolean get() = false
    }
    private class LoweredCaseExpression(val expression: Expression) : Expression {
        override fun emit(emission: Emission) = expression.emit(emission)
        override fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) = expression.emitTuple(emission, destination)
        override val proof get() = expression.proof
        override val source get() = expression.source
        override val loweredCase get() = true
    }
    private class ProvenExpression(val expression: Expression, override val proof: CoreRepresentation) : Expression {
        override fun emit(emission: Emission) = expression.emit(emission)
        override fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) = expression.emitTuple(emission, destination)
        override val source get() = expression.source
        override val loweredCase get() = expression.loweredCase
    }
    /** Source operations are builder metadata; they emit no guest instruction. */
    private class SourcedExpression(val expression: Expression, override val source: CoreSourceLocation) : Expression {
        override val proof get() = expression.proof
        override val loweredCase get() = expression.loweredCase
        override fun emit(emission: Emission) = emitSource(emission) { expression.emit(emission) }
        override fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) = emitSource(emission) { expression.emitTuple(emission, destination) }
        private fun emitSource(emission: Emission, action: () -> Unit) {
            val sections = source.notes.map { it.section }.distinct().let {
                if (it.lastOrNull() == source.section) it else it + source.section
            }
            sections.forEach { BytecodeSources.begin(emission.builder, it) }
            action()
            sections.asReversed().forEach { _ -> BytecodeSources.end(emission.builder) }
        }
    }
    private class ResultExpression(val action: (Emission, List<BytecodeLocal>?) -> Unit) : Expression {
        override fun emit(emission: Emission) = action(emission, null)
        override fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) = action(emission, destination)
    }
    private fun emitResult(value: Expression, e: Emission, destination: List<BytecodeLocal>?) {
        if (destination == null) value.emit(e) else value.emitTuple(e, destination)
    }
    private fun tupleExpression(proof: CoreRepresentation, action: (Emission, List<BytecodeLocal>) -> Unit): Expression =
        ProvenExpression(ResultExpression { e, destination -> action(e, destination ?: throw RuntimeFault("Tuple result requires a destination")) }, proof.copy(evaluated = true))
    private fun tupleSlots(shape: TupleShape, locals: List<BytecodeLocal>, capturesYield: Boolean = false) =
        BytecodeTupleSlots(shape, locals.map(LocalAccessor::constantOf).toTypedArray(), capturesYield)
    private class LocalExpression(val local: Local, val resolve: Boolean) : Expression {
        override val proof get() = local.proof
        override fun emit(emission: Emission) {
            val b = emission.builder
            val integer = local.directLong || resolve && local.proof.isLong && local.proof.evaluated
            val floating = local.directFloat || resolve && local.proof.isFloat && local.proof.evaluated
            val double = local.directDouble || resolve && local.proof.isDouble && local.proof.evaluated
            if (integer) b.beginToLong()
            else if (floating) b.beginToFloat()
            else if (double) b.beginToDouble()
            if (resolve && local.cell) b.beginReadCellIfNeeded()
            b.emitLoadLocal(emission.locals.getValue(local.id))
            if (resolve && local.cell) b.endReadCellIfNeeded()
            if (integer) b.endToLong()
            else if (floating) b.endToFloat()
            else if (double) b.endToDouble()
        }
    }
    private data class FunctionSpec(val target: RootCallTarget, val captureLayout: CaptureLayout?, val captures: List<Local>)

    init {
        ArrayOp.validateApplications(bindings)
        CoreStackForeign.validateHeads(bindings)
        CoreStackInfoForeign.validateHeads(bindings)
        CoreOriginalStdio.validateHeads(bindings)
        CoreStablePointers.validateHeads(bindings)
        CoreManagedFiles.validateHeads(bindings)
        CoreMd5Foreign.validateHeads(bindings)
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(bindings, constructors)
            CoreInputCalls.validate(bindings, constructors)
        }
        val scope = Scope(FunctionContext(0))
        val initializers = bindings.map { binding ->
            CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding")
            val expr = binding["expr"] as List<Any?>
            CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding")
            val bindingScope = scope.withSource(sources.binding(binding))
            if (representation(binding) && expr[0] !in listOf("lam", "lit", "con", "void")) delay(expr, bindingScope, binding["name"] as String)
            else argument(expr, bindingScope, representation(binding), binding["name"] as String)
        }
        val body = Expression { e ->
            val b = e.builder
            b.beginBlock()
            bindings.forEachIndexed { index, binding ->
                b.beginInitializeGlobal(globals.getValue(binding["id"] as String))
                initializers[index].emit(e)
                b.endInitializeGlobal()
            }
            b.emitLoadConstant(Unit)
            b.endBlock()
        }
        // Publication stores lazy values, so the initializer deliberately has no WHNF return obligation.
        val initializer = build("Core module initialization", scope.function, body, forceResult = false)
        Calls.target(initializer, arrayOf(0L))
        bindings.forEach { binding ->
            CoreFunctionIdentity.install(moduleData, binding, globals.getValue(binding["id"] as String).read(),
                globalArityCertificates)
        }
    }

    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    @Synchronized override fun hostEntryTarget(arity: Int): RootCallTarget =
        hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
    override fun entryValue(name: String): Any? = globals.getValue(bindings[bindingIndex(name)]["id"] as String).read()
    override fun entryTarget(name: String): RootCallTarget {
        var value = entryValue(name)
        while (value is Thunk && value.state == 2) value = value.value
        return when (value) { is Closure -> value.target; is Thunk -> value.target ?: hostEntryTarget(0); else -> hostEntryTarget(0) }
    }
    override fun diagnostics(): Map<String, Any> = linkedMapOf(
        "backend" to "bytecode", "bytecodeRootCount" to roots.size,
        "sourceNotesEnabled" to sources.enabled, "sourceSpanCount" to sources.spanCount,
        "sourceRootCount" to roots.count { it.bytecodeNode.hasSourceInformation() && it.sourceSection != null }, "localJoinCount" to localJoinCount,
        "localJoinTransfers" to metrics.localJoinTransfers,
        "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkCountsSnapshot(),
        "compiledEntries" to metrics.compiledEntries, "leadingCaseReturns" to metrics.leadingCaseReturns, "thunkEvaluations" to metrics.thunkEvaluations,
        "thunkHits" to metrics.thunkHits, "blackholes" to metrics.blackholes, "directCacheMisses" to metrics.directCacheMisses,
        "indirectCalls" to metrics.indirectCalls, "tailBounces" to metrics.tailBounces,
        "selfTailReentries" to metrics.selfTailReentries, "trampolineIterations" to metrics.trampolineIterations,
        "papAllocations" to metrics.papAllocations,
        "unsupportedPolicy" to (if (diagnosticUnsupported) "diagnostic-traps" else "reject-at-load"),
        "deferredUnsupported" to deferredUnsupported.toList(), "unsupportedTraps" to metrics.unsupportedTraps,
        "frames" to "Bytecode DSL primitive locals; selective StaticShape captures",
        "stackPolicy" to "tail-safe; non-tail calls and nested thunk forcing use host stack", "threadPolicy" to "single guest thread")

    /** Actual decoded instruction listings, available without a Graal graph viewer. */
    fun bytecodeDump(): String = roots.joinToString("\n\n") { "${it.name}\n${it.bytecodeNode.dump()}" }

    private fun bind(scope: Scope, name: String, primitive: Boolean,
                     proof: CoreRepresentation = CoreRepresentation.UNKNOWN, cell: Boolean = false,
                     entry: BooleanArray? = null,
                     arityCertificate: CoreApplicationCertificates.Arity? = null): Local =
        Local(nextLocal++, name, !cell && (if (proof.present) proof.isLong else primitive), proof, cell, entry,
            arityCertificate).also { scope.bindLocal(name, it) }
    private fun representation(binding: Map<String, Any?>): Boolean = binding["lifted"] as? Boolean
        ?: throw UnsupportedCore("Unknown levity for ${binding["id"]}")
    private fun freeVariables(expr: List<Any?>): Set<String> = when (expr[0]) {
        "var" -> setOf(expr[1] as String)
        "lam" -> freeVariables(expr[2] as List<Any?>) - (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }.toSet()
        "app" -> freeVariables(expr[1] as List<Any?>) + (expr[2] as List<List<Any?>>).flatMap { freeVariables(it) }
        "let" -> {
            val group = expr[2] as List<Map<String, Any?>>
            val ids = group.map { it["id"] as String }.toSet()
            val rhs = group.flatMap { freeVariables(it["expr"] as List<Any?>) }.toSet()
            (if (expr[1] == true) rhs - ids else rhs) + (freeVariables(expr[3] as List<Any?>) - ids)
        }
        "case" -> freeVariables(expr[1] as List<Any?>) + (expr[3] as List<List<Any?>>).flatMap {
            freeVariables(it[3] as List<Any?>) - (it[2] as List<String>).toSet() - (expr[2] as String)
        }
        else -> emptySet()
    }

    private fun function(label: String, args: List<Map<String, Any?>>, expression: List<Any?>, outer: Scope,
                         resultProof: CoreRepresentation = CoreRepresentations.expression(expression),
                         entryStrict: BooleanArray = BooleanArray(args.size)): FunctionSpec {
        CoreRepresentations.requireNoVector(resultProof, "function result")
        if (entryStrict.size != args.size) throw RuntimeFault("Function entry contract arity mismatch")
        val context = FunctionContext(args.size, entryStrict.copyOf())
        val scope = Scope(context, source = outer.source)
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        args.forEach { CoreRepresentations.requireInput(CoreRepresentations.binder(it)) }
        context.inputLayout = ArgumentLayout.fromProofs(args.map(CoreRepresentations::binder))
        if ((free - argumentIds).any { it in outer.tuples }) throw UnsupportedCore("Unsupported Core aggregate capture: unboxed-tuple")
        val freeLocals = (free - argumentIds).filter { it in outer.locals }.map { outer.locals.getValue(it) }
        freeLocals.filter { it.id < 0 && it.proof.kind == CoreKind.VOID }.forEach { scope.bindVoid(it.name, it.proof) }
        val captureSources = freeLocals.filter { it.id >= 0 || it.proof.kind != CoreKind.VOID }
        captureSources.forEach { CoreRepresentations.requireNoVector(it.proof, "capture") }
        context.captures = captureSources.map { bind(scope, it.name, it.primitive, it.proof, it.cell, it.entry, it.arityCertificate) }
        context.captureLayout = if (captureSources.isEmpty()) null else CaptureLayout(language, captureSources.map { it.primitive }.toBooleanArray(),
            captureSources.map { it.directLong }.toBooleanArray(),
            captureSources.map { if (it.cell) null else it.proof.referenceCarrier() }.toTypedArray(),
            captureSources.map { it.directFloat }.toBooleanArray(),
            captureSources.map { it.directDouble }.toBooleanArray())
        context.typedInput = TypedInputLayout.create(language, context.inputLayout, context.captureLayout != null)
        val physicalArguments = arrayListOf<Pair<Int, Local>>()
        context.arguments = args.mapIndexed { index, arg ->
            val lifted = representation(arg)
            val proof = CoreRepresentations.binder(arg).copy(evaluated = !lifted || context.entryStrict[index])
            val offset = ArgumentLayout.offset(context.inputLayout, index)
            if (proof.isTuple) {
                if (lifted) throw RuntimeFault("Tuple formal cannot be lifted")
                val fields = if (arg["id"] in free) ArgumentLayout.leaves(proof).mapIndexed { leaf, field ->
                    Local(nextLocal++, "${arg["id"]} field $leaf", field.isLong, field).also {
                        physicalArguments += (offset + leaf) to it
                    }
                } else emptyList()
                scope.bindTuple(arg["id"] as String, proof, fields)
                null
            } else if (arg["id"] in free || enableAsync && context.entryStrict[index]) bind(scope, arg["id"] as String, !lifted && arg["coercion"] != true, proof).also {
                physicalArguments += offset to it
            } else null
        }
        context.typedArguments = physicalArguments
        val compiled = compile(expression, scope, true)
        if ((!enableAsync || context.entryStrict.none { it }) && compiled.loweredCase && context.inputLayout == null) context.leadingCaseReturn = LeadingCaseReturn.discover(args, expression,
            resultProof, if (context.captureLayout == null) 1 else 2, free.intersect(argumentIds), context.captureLayout != null,
            ::dataLayout, sources, compiled.source)
        if ((compiled.proof.isSum || resultProof.isSum) && (!compiled.proof.isSum || !resultProof.isSum))
            throw RuntimeFault("Sum function requires exact body and declared result proofs")
        val body = ProvenExpression(compiled, compiled.proof.refine(resultProof).copy(evaluated = compiled.proof.evaluated))
        CoreRepresentations.requireNoVector(body.proof, "function result")
        context.tuple = if (body.proof.isAggregate) TupleShape(body.proof, language) else null
        return FunctionSpec(build(label, context, body, forceResult = !body.proof.evaluated), context.captureLayout, captureSources)
    }

    private fun build(label: String, context: FunctionContext, body: Expression, forceResult: Boolean = true): RootCallTarget {
        val source = body.source
        val config = if (sources.enabled && sources.spanCount > 0) BytecodeConfig.WITH_SOURCE else BytecodeConfig.DEFAULT
        var typedBloom: LocalAccessor? = null
        val root = BytecodeRootGen.create(language, config) { b ->
            source?.let { BytecodeSources.begin(b, it.section) }
            b.beginRoot()
            val e = Emission(b)
            b.emitEnterRoot(metrics)
            if (resumable) {
                // Only proof roots pay for a mask snapshot. A yielded caller
                // parks to this root's entry mask before its frame is captured.
                e.checkpointRootEntry = b.createLocal("checkpoint root entry mask", "object").also {
                    b.beginStoreLocal(it); b.emitCurrentMask(); b.endStoreLocal()
                }
            }
            for (local in context.captures + context.typedArguments.map { it.second }.ifEmpty { context.arguments.filterNotNull() }) {
                e.locals[local.id] = b.createLocal(local.name, if (local.primitive) "primitive" else "object")
            }
            val typed = context.typedInput
            if (typed != null) {
                val bloom = LocalAccessor.constantOf(b.createLocal("typed input bloom", "primitive"))
                typedBloom = bloom
                val physical = context.typedArguments
                val deferredStrict = context.arguments.mapIndexedNotNull { index, local ->
                    if (enableAsync && context.entryStrict[index] && local != null && !local.primitive) local.id else null
                }.toSet()
                val slots = BytecodeTypedInputSlots(typed, bloom,
                    physical.map { LocalAccessor.constantOf(e.locals.getValue(it.second.id)) }.toTypedArray(),
                    physical.map { it.first }.toIntArray(), physical.map {
                        if (it.second.id in deferredStrict) it.second.proof.copy(evaluated = false) else it.second.proof
                    }.toTypedArray(),
                    context.captureLayout,
                    context.captures.map { LocalAccessor.constantOf(e.locals.getValue(it.id)) }.toTypedArray(),
                    context.captures.map { if (it.cell) CoreRepresentation.UNKNOWN else it.proof }.toTypedArray())
                e.typedInputSlots = slots
                b.emitRestoreTypedInput(slots)
            } else {
                context.captures.forEachIndexed { index, local ->
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
                    b.emitLoadArgument(1)
                    if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
                    b.endStoreLocal()
                }
                val offset = if (context.captureLayout == null) 1 else 2
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    restoreArgument(e, local, enableAsync && context.entryStrict[index]) {
                        b.emitLoadArgument(ArgumentLayout.offset(context.inputLayout, index) + offset)
                    }
                } }
            }
            if (context.mayLoop) {
                b.beginWhile()
                b.emitLoadConstant(true)
                b.beginBlock()
                e.continueLabel = b.createLabel()
            }
            if (enableAsync) emitAsyncPoll(e)
            if (enableAsync) emitEntryStrictDemands(e, context)
            val tuple = context.tuple
            if (tuple != null) {
                val result = List(tuple.width) { b.createLocal("tuple result $it", null) }
                body.emitTuple(e, result)
                b.beginReturn(); b.emitFinishTuple(tupleSlots(tuple, result)); b.endReturn()
            } else {
                b.beginReturn()
                if (forceResult) force(body).emit(e) else body.emit(e)
                b.endReturn()
            }
            if (context.mayLoop) {
                b.emitLabel(e.continueLabel!!)
                if (enableAsync) emitAsyncPoll(e)
                b.endBlock()
                b.endWhile()
                // The loop condition is true; retain an explicit terminating operation for the builder.
                b.beginReturn()
                b.emitFailCase()
                b.endReturn()
            }
            b.endRoot()
            source?.let { BytecodeSources.end(b) }
        }.getNode(0)
        root.setLabel(label)
        root.configureAsync(enableAsync)
        root.configureEntry(context.entryStrict, context.captureLayout != null)
        root.configureInput(context.inputLayout)
        root.configureTypedInput(context.typedInput)
        root.configureTypedBloom(typedBloom)
        root.configureLeadingCaseReturn(context.leadingCaseReturn)
        root.configureTupleResult(context.tuple)
        roots += root
        return root.callTarget
    }

    /** The cold Yield carries the exact bytecode frame; ordinary polls allocate no packet. */
    private fun emitAsyncPoll(e: Emission) {
        val b = e.builder
        b.beginBlock()
        val request = b.createLocal("pending async request", "object")
        val active = b.createLocal("async logical mask", "object")
        b.beginIfThen()
        b.emitPollAsync(request)
        b.beginBlock()
        b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal()
        b.beginReenterCallMask()
        b.beginYield()
        b.beginParkAsyncMask()
        b.emitLoadLocal(request)
        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
        b.endParkAsyncMask()
        b.endYield()
        b.emitLoadLocal(active)
        b.endReenterCallMask()
        b.endBlock()
        b.endIfThen()
        b.endBlock()
    }

    /** Blocking MVar operands are evaluated once; only an uncommitted cell request is retried. */
    private fun emitBlockingMVar(e: Emission, operands: List<Expression>,
                                 result: Boolean, operation: (List<BytecodeLocal>) -> Unit) {
        val b = e.builder
        b.beginBlock()
        val values = operands.mapIndexed { index, operand ->
            b.createLocal("MVar operand $index", null).also {
                b.beginStoreLocal(it); operand.emit(e); b.endStoreLocal()
            }
        }
        val retry = b.createLocal("MVar request pending", "primitive")
        val request = b.createLocal("MVar async request", "object")
        val active = b.createLocal("MVar logical mask", "object")
        val discard = b.createLocal("MVar resume value", "object")
        b.beginStoreLocal(retry); b.emitLoadConstant(true); b.endStoreLocal()
        b.beginWhile()
        b.emitLoadLocal(retry)
        b.beginBlock()
        b.beginTryCatch()
        b.beginBlock()
        operation(values)
        b.beginStoreLocal(retry); b.emitLoadConstant(false); b.endStoreLocal()
        b.endBlock()
        b.beginBlock()
        b.beginStoreLocal(request)
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
        b.endStoreLocal()
        b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal()
        b.beginStoreLocal(discard)
        b.beginReenterCallMask()
        b.beginYield()
        b.beginParkAsyncMask()
        b.emitLoadLocal(request)
        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
        b.endParkAsyncMask()
        b.endYield()
        b.emitLoadLocal(active)
        b.endReenterCallMask()
        b.endStoreLocal()
        b.endBlock()
        b.endTryCatch()
        b.endBlock()
        b.endWhile()
        if (result) b.emitLoadConstant(Unit)
        b.endBlock()
    }

    /** Only a foreign owner wait may restart; the producer/local operand stays saved. */
    private fun emitOwnerWaitRetry(e: Emission, attempt: () -> Unit) {
        if (!enableAsync) { attempt(); return }
        val b = e.builder
        b.beginBlock()
        val retry = b.createLocal("owner wait pending", "primitive")
        val request = b.createLocal("owner wait async request", "object")
        val active = b.createLocal("owner wait logical mask", "object")
        val discard = b.createLocal("owner wait resume value", "object")
        b.beginStoreLocal(retry); b.emitLoadConstant(true); b.endStoreLocal()
        b.beginWhile()
        b.emitLoadLocal(retry)
        b.beginBlock()
        b.beginTryCatch()
        b.beginBlock()
        attempt()
        b.beginStoreLocal(retry); b.emitLoadConstant(false); b.endStoreLocal()
        b.endBlock()
        b.beginBlock()
        b.beginStoreLocal(request)
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
        b.endStoreLocal()
        b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal()
        b.beginStoreLocal(discard)
        b.beginReenterCallMask()
        b.beginYield()
        b.beginParkAsyncMask()
        b.emitLoadLocal(request)
        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
        b.endParkAsyncMask()
        b.endYield()
        b.emitLoadLocal(active)
        b.endReenterCallMask()
        b.endStoreLocal()
        b.endBlock()
        b.endTryCatch()
        b.endBlock()
        b.endWhile()
        b.endBlock()
    }

    /** Choose checked reference identities while emitting code, never by a guest-time enum switch. */
    private fun restoreArgument(e: Emission, local: Local, deferStrictDemand: Boolean = false, value: () -> Unit) {
        val b = e.builder
        val reference = if (local.cell || deferStrictDemand) null else local.proof.referenceCarrier()
        b.beginStoreLocal(e.locals.getValue(local.id))
        when {
            local.directLong -> { b.beginToLong(); value(); b.endToLong() }
            local.directFloat -> { b.beginToFloat(); value(); b.endToFloat() }
            local.directDouble -> { b.beginToDouble(); value(); b.endToDouble() }
            reference == DataValue::class.java -> { b.beginRequireData(); value(); b.endRequireData() }
            reference == Closure::class.java -> { b.beginRequireClosure(); value(); b.endRequireClosure() }
            reference == ManagedAddress::class.java -> { b.beginRequireAddress(); value(); b.endRequireAddress() }
            else -> value()
        }
        b.endStoreLocal()
    }

    private fun sourced(value: Expression, source: CoreSourceLocation?): Expression =
        if (source == null || value.source == source) value else SourcedExpression(value, source)
    private tailrec fun undecorated(value: Expression): Expression = when (value) {
        is ProvenExpression -> undecorated(value.expression)
        is SourcedExpression -> undecorated(value.expression)
        else -> value
    }
    private fun read(local: Local, resolve: Boolean = true): Expression =
        if (local.id < 0 && local.proof.kind == CoreKind.VOID) ProvenExpression(constant(Unit), local.proof)
        else LocalExpression(local, resolve)
    private fun evaluated(value: Expression): Expression = ProvenExpression(value, value.proof.copy(evaluated = true))
    /** Async callees demand their own CBV formals at a captured bytecode cut.
     * The caller has already transferred PAP prefixes and typed input fields.
     * Recheck the declared carrier only after the resumable force completes. */
    private fun emitEntryStrictDemands(e: Emission, context: FunctionContext) {
        context.arguments.forEachIndexed { index, local ->
            if (!context.entryStrict[index] || local == null || local.primitive || local.proof.isAggregate) return@forEachIndexed
            val raw = ProvenExpression(read(local), local.proof.copy(evaluated = false))
            restoreArgument(e, local) { force(raw).emit(e) }
        }
    }
    private fun force(value: Expression): Expression {
        if (value.proof.isAggregate) return value
        if (value.proof.evaluated) return value
        return evaluated(ResultExpression { e, destination ->
            if (destination != null) value.emitTuple(e, destination) else {
                val b = e.builder
                val localValue = undecorated(value)
                if (localValue is LocalExpression && localValue.resolve) {
                    val local = e.locals.getValue(localValue.local.id)
                    if (!resumable) {
                        b.beginForceLocal(metrics, local, localValue.local.cell, false)
                        b.emitLoadLocal(local)
                        b.endForceLocal()
                    } else {
                        val result = b.createLocal("forced local result", null)
                        val suspended = b.createLocal("forced local suspension", "object")
                        b.beginBlock()
                        emitOwnerWaitRetry(e) {
                        b.beginTryCatch()
                        b.beginStoreLocal(result)
                        b.beginForceLocal(metrics, local, localValue.local.cell, enableAsync)
                        b.emitLoadLocal(local)
                        b.endForceLocal()
                        b.endStoreLocal()
                        b.beginBlock()
                        b.beginStoreLocal(suspended)
                        b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly()
                        b.endStoreLocal()
                        b.beginStoreLocal(result)
                        b.beginResumeForcedLocal(local, localValue.local.cell)
                        b.emitLoadLocal(suspended)
                        b.beginYield()
                        b.emitLoadLocal(suspended)
                        b.endYield()
                        b.endResumeForcedLocal()
                        b.endStoreLocal()
                        b.endBlock()
                        b.endTryCatch()
                        }
                        b.emitLoadLocal(result)
                        b.endBlock()
                    }
                } else {
                    if (!resumable) {
                        b.beginForceValue(metrics, false); value.emit(e); b.endForceValue()
                    } else {
                        val operand = b.createLocal("saved force operand", null)
                        val result = b.createLocal("forced value result", null)
                        val suspended = b.createLocal("forced value suspension", "object")
                        b.beginBlock()
                        // Evaluate the producer once, before any child ownership is claimed.
                        b.beginStoreLocal(operand); value.emit(e); b.endStoreLocal()
                        emitOwnerWaitRetry(e) {
                        b.beginTryCatch()
                        b.beginStoreLocal(result)
                        b.beginForceValue(metrics, enableAsync); b.emitLoadLocal(operand); b.endForceValue()
                        b.endStoreLocal()
                        b.beginBlock()
                        b.beginStoreLocal(suspended)
                        b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly()
                        b.endStoreLocal()
                        b.beginStoreLocal(result)
                        b.beginResumeForcedValue()
                        b.emitLoadLocal(operand)
                        b.emitLoadLocal(suspended)
                        b.beginYield(); b.emitLoadLocal(suspended); b.endYield()
                        b.endResumeForcedValue()
                        b.endStoreLocal()
                        b.endBlock()
                        b.endTryCatch()
                        }
                        b.emitLoadLocal(result)
                        b.endBlock()
                    }
                }
            }
        }).let { sourced(ProvenExpression(it, value.proof.copy(evaluated = true)), value.source) }
    }
    private fun requireClosure(value: Expression) = Expression { e ->
        e.builder.beginRequireClosure(); force(value).emit(e); e.builder.endRequireClosure()
    }
    private fun forceSavedCallback(e: Emission, slot: BytecodeLocal, proof: CoreRepresentation) {
        // The Java invoke operation cannot capture a suspension raised while
        // forcing its own callback operand. Enter the enclosing catch/mask
        // scope first, then use the bytecode force path and its resumable yield.
        val saved = ProvenExpression(Expression { it.builder.emitLoadLocal(slot) },
            proof.copy(evaluated = false))
        force(saved).emit(e)
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expression {
        val fn = function(label, emptyList(), expr, scope)
        (fn.target.rootNode as GuestRoot).tupleResult?.let { CoreRepresentations.requireScalar(it.proof, "thunk") }
        val template = BytecodeRoot.ClosureTemplate(fn.target, 0, fn.captureLayout)
        return sourced(Expression { e ->
            e.builder.beginMakeThunk(template)
            fn.captures.forEach { read(it, false).emit(e) }
            e.builder.endMakeThunk()
        }, sources.expression(expr, scope.source))
    }
    private fun closure(fn: FunctionSpec, arity: Int): Expression {
        val template = BytecodeRoot.ClosureTemplate(fn.target, arity, fn.captureLayout)
        return evaluated(Expression { e ->
            e.builder.beginMakeClosure(template)
            fn.captures.forEach { read(it, false).emit(e) }
            e.builder.endMakeClosure()
        })
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk", allowEmpty: Boolean = false, declaredLifted: Boolean = lifted): Expression {
        fun check(value: CoreRepresentation) {
            if (allowEmpty) CoreRepresentations.requireInput(value) else CoreRepresentations.requireScalar(value, "argument")
            if (value.isTuple && declaredLifted) throw RuntimeFault("Tuple argument cannot be lifted")
        }
        val proof = CoreRepresentations.expression(expr)
        check(proof)
        val lexical = if (expr[0] == "var") scope.tuples[expr[1]]?.first ?: scope.locals[expr[1]]?.proof else null
        lexical?.let(::check)
        fun lowered(): Expression = compile(expr, scope, false).also { check(it.proof) }
        if (proof.isTuple || lexical?.isTuple == true) return lowered().also {
            if (!it.proof.isTuple) throw RuntimeFault("Missing exact tuple argument proof")
        }
        // Lowering can expose a tuple behind omitted outer case metadata. It
        // must remain a destination writer and may never be forced or delayed.
        if (!lifted) return force(lowered())
        val head = (expr.getOrNull(1) as? List<*>)?.takeIf { expr[0] == "app" && it.firstOrNull() == "var" }
        val headId = head?.getOrNull(1) as? String
        val arityCertificate = headId?.let { id ->
            if (id in scope.locals) scope.locals.getValue(id).arityCertificate else globalArityCertificates[id]
        }
        if (CoreApplicationCertificates.eagerApplication(expr, arityCertificate)) return lowered()
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> lowered(); else -> delay(expr, scope, label) }
    }
    private fun literal(kind: String, value: String): Any = when (kind) {
        "int8" -> int8Literal(value)
        "int16" -> int16Literal(value)
        "int32" -> int32Literal(value)
        "int64" -> int64Literal(value)
        "word64" -> word64Literal(value)
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
        "float" -> value.toFloat()
        "double" -> value.toDouble()
        "word8", "word16", "word32" -> narrowWordLiteral(kind, value)
        "string-bytes" -> ManagedAddress.fromHex(value)
        "null-addr" -> if (value == "0") ManagedAddress.nullAddress() else throw UnsupportedCore("Malformed null Addr# literal")
        "bignat" -> BigNatLiterals.decode(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun constant(value: Any) = ProvenExpression(Expression { it.builder.emitLoadConstant(value) },
        CoreRepresentation(when (value) {
            is Long -> CoreKind.LONG; is Float -> CoreKind.FLOAT; is Double -> CoreKind.DOUBLE
            is ManagedAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
        }, evaluated = true))
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expression {
        val source = sources.expression(expr, scope.source)
        val value = try {
            val lowered = compileSupported(expr, scope.withSource(source), tail)
            val proof = CoreRepresentations.expression(expr)
            // Core describes the denoted value; our lowering may introduce an extra
            // thunk (notably for CAFs). Only the lowered storage proves WHNF here.
            val diagnosticGlobal = diagnosticUnsupported && expr[0] == "var" && expr[1] !in scope.locals && expr[1] !in scope.joins && expr[1] in globals
            if (proof.present && !diagnosticGlobal) ProvenExpression(lowered, lowered.proof.refine(proof).copy(evaluated = lowered.proof.evaluated)) else lowered
        } catch (gap: UnsupportedCore) {
            if (!diagnosticUnsupported) throw gap
            val message = gap.message ?: "Unsupported Core"
            deferredUnsupported += message
            val body = sourced(Expression { it.builder.emitUnsupported(message, metrics) }, source)
            val target = build("unsupported: $message", FunctionContext(0), body)
            val template = BytecodeRoot.ClosureTemplate(target, 0, null)
            object : Expression {
                override fun emit(emission: Emission) {
                    emission.builder.beginMakeThunk(template); emission.builder.endMakeThunk()
                }
                override fun emitTuple(emission: Emission, destination: List<BytecodeLocal>) {
                    // Unsupported never returns. Discard its scalar operation result
                    // locally, without inventing or touching a tuple destination.
                    val b = emission.builder
                    b.beginBlock()
                    b.beginStoreLocal(b.createLocal("unsupported tuple", null))
                    b.emitUnsupported(message, metrics)
                    b.endStoreLocal()
                    b.endBlock()
                }
            }
        }
        return sourced(value, source)
    }

    private fun compactArguments(e: Emission, function: Expression, arguments: List<Expression>,
                                 layout: ArgumentLayout, emit: (BytecodeLocal, List<BytecodeLocal>) -> Unit) {
        val b = e.builder
        b.beginBlock()
        val fn = b.createLocal("compact function", null)
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal()
        val values = arrayListOf<BytecodeLocal>()
        arguments.forEachIndexed { index, argument ->
            if (layout.isEmpty(index)) argument.emitTuple(e, emptyList())
            else values += b.createLocal("compact operand $index", null).also { local ->
                b.beginStoreLocal(local); argument.emit(e); b.endStoreLocal()
            }
        }
        emit(fn, values)
        b.endBlock()
    }

    /** Aggregate operands are written directly into replay-local typed slots. */
    private fun typedArguments(e: Emission, function: Expression, arguments: List<Expression>,
                               layout: ArgumentLayout, tail: Boolean, destination: BytecodeTupleSlots? = null) {
        val b = e.builder
        b.beginBlock()
        val fn = b.createLocal("typed function", null)
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal()
        val values = List(layout.physicalArity) { b.createLocal("typed input $it", null) }
        arguments.forEachIndexed { index, argument ->
            val offset = layout.offset(index)
            if (layout.isTuple(index)) argument.emitTuple(e, values.subList(offset, layout.offset(index + 1)))
            else {
                b.beginStoreLocal(values[offset]); argument.emit(e); b.endStoreLocal()
            }
        }
        val source = BytecodeInputSource(layout, values.map(LocalAccessor::constantOf).toTypedArray())
        if (destination == null) {
            b.beginApplyTypedInput(source, tail, metrics)
            b.emitLoadLocal(fn); b.endApplyTypedInput()
        } else {
            b.beginApplyTypedInputTuple(source, destination, tail, metrics)
            b.emitLoadLocal(fn); b.endApplyTypedInputTuple()
        }
        b.endBlock()
    }

    private fun restoreTailArguments(e: Emission, context: FunctionContext, transfer: BytecodeLocal) {
        val b = e.builder
        val typed = e.typedInputSlots
        if (typed != null) {
            b.beginRestoreTypedTail(typed); b.emitLoadLocal(transfer); b.endRestoreTypedTail()
            return
        }
        // Preserve this activation's ancestry when replacing captures/formals.
        context.captures.forEachIndexed { index, local ->
            b.beginStoreLocal(e.locals.getValue(local.id))
            if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
            b.beginTailArgument(1); b.emitLoadLocal(transfer); b.endTailArgument()
            if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
            b.endStoreLocal()
        }
        val offset = if (context.captureLayout == null) 1 else 2
        context.arguments.forEachIndexed { index, local -> if (local != null) {
            restoreArgument(e, local, enableAsync && context.entryStrict[index]) {
                b.beginTailArgument(ArgumentLayout.offset(context.inputLayout, index) + offset)
                b.emitLoadLocal(transfer); b.endTailArgument()
            }
        } }
    }

    private fun savedApply(e: Emission, fn: BytecodeLocal, values: List<BytecodeLocal>,
                           layout: ArgumentLayout?, evaluatedArguments: BooleanArray,
                           arity: Int, tail: Boolean) {
        val b = e.builder
        val typedSource = if (layout?.requiresTyped == true)
            BytecodeInputSource(layout, values.map(LocalAccessor::constantOf).toTypedArray()) else null
        when {
            typedSource != null -> {
                b.beginApplyTypedInput(typedSource, tail, metrics)
                b.emitLoadLocal(fn)
                b.endApplyTypedInput()
            }
            layout != null -> {
                b.beginApplyCompact(layout, tail, metrics, evaluatedArguments)
                b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
                b.endApplyCompact()
            }
            else -> {
                b.beginApply(arity, tail, metrics, evaluatedArguments)
                b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
                b.endApply()
            }
        }
    }

    /** One exact call or PAP step; a yielded callee is captured before any suffix runs. */
    private fun checkpointedCall(e: Emission, fn: BytecodeLocal, values: List<BytecodeLocal>,
                                 layout: ArgumentLayout?, evaluatedArguments: BooleanArray,
                                 arity: Int, callerMask: BytecodeLocal) {
        val b = e.builder
        b.beginBlock()
        val result = b.createLocal("captured application result", "object")
        val suspended = b.createLocal("captured application suspension", "object")
        b.beginTryCatch()
        b.beginStoreLocal(result)
        b.beginCaptureApplicationResult(arity)
        b.emitLoadLocal(fn)
        savedApply(e, fn, values, layout, evaluatedArguments, arity, false)
        b.emitLoadLocal(callerMask)
        b.endCaptureApplicationResult()
        b.endStoreLocal()
        b.beginBlock()
        b.beginStoreLocal(suspended)
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
        b.endStoreLocal()
        b.beginStoreLocal(result)
        b.beginResumeApplication()
        b.emitLoadLocal(suspended)
        b.beginReenterCallMask()
        b.beginYield()
        b.beginParkCallMask()
        b.emitLoadLocal(suspended)
        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
        b.emitLoadLocal(callerMask)
        b.endParkCallMask()
        b.endYield()
        b.emitLoadLocal(callerMask)
        b.endReenterCallMask()
        b.endResumeApplication()
        b.endStoreLocal()
        b.endBlock()
        b.endTryCatch()
        b.emitLoadLocal(result)
        b.endBlock()
    }

    /** A saved application can cross several exact call boundaries without replaying operands. */
    private fun stagedOverapplication(e: Emission, fn: BytecodeLocal, values: List<BytecodeLocal>,
                                      layout: ArgumentLayout?, evaluatedArguments: BooleanArray,
                                      callerMask: BytecodeLocal, result: BytecodeLocal, count: Int,
                                      tail: Boolean,
                                      finishTuple: ((List<BytecodeLocal>, ArgumentLayout?, Int) -> Unit)? = null) {
        val b = e.builder
        b.beginBlock()
        val arity = b.createLocal("remaining closure arity", "primitive")
        val stages = List(count) { b.createLabel() }
        val complete = b.createLabel()
        b.emitBranch(stages[0])
        stages.forEachIndexed { start, stage ->
            b.emitLabel(stage)
            if (enableAsync) emitAsyncPoll(e)
            // A zero-arity closure consumes no supplied arguments. Truffle DSL
            // requires a structured loop for that genuine backward edge.
            b.beginWhile()
            b.beginMatchLiteral(0L)
            b.beginClosureArity(); b.emitLoadLocal(fn); b.endClosureArity()
            b.endMatchLiteral()
            b.beginBlock()
            if (enableAsync) emitAsyncPoll(e)
            b.beginStoreLocal(result)
            checkpointedCall(e, fn, emptyList(), null, booleanArrayOf(), 0, callerMask)
            b.endStoreLocal()
            b.beginStoreLocal(fn)
            requireClosure(Expression { it.builder.emitLoadLocal(result) }).emit(e)
            b.endStoreLocal()
            b.endBlock()
            b.endWhile()
            b.beginStoreLocal(arity); b.beginClosureArity(); b.emitLoadLocal(fn); b.endClosureArity(); b.endStoreLocal()
            val remaining = count - start
            for (take in 1 until remaining) {
                b.beginIfThen()
                b.beginMatchLiteral(take.toLong()); b.emitLoadLocal(arity); b.endMatchLiteral()
                b.beginBlock()
                val from = ArgumentLayout.offset(layout, start)
                val until = ArgumentLayout.offset(layout, start + take)
                val input = layout?.let { ArgumentLayout.fromProofs((start until start + take).map(it::proof)) }
                b.beginStoreLocal(result)
                checkpointedCall(e, fn, values.subList(from, until), input,
                    evaluatedArguments.copyOfRange(start, start + take), take, callerMask)
                b.endStoreLocal()
                // Only the demanded intermediate function is forced. A yielded force
                // resumes from its saved result local, never from the original call.
                b.beginStoreLocal(fn)
                requireClosure(Expression { it.builder.emitLoadLocal(result) }).emit(e)
                b.endStoreLocal()
                b.emitBranch(stages[start + take])
                b.endBlock()
                b.endIfThen()
            }
            val from = ArgumentLayout.offset(layout, start)
            val input = layout?.let { ArgumentLayout.fromProofs((start until count).map(it::proof)) }
            val suffix = values.subList(from, values.size)
            if (finishTuple != null) finishTuple(suffix, input, remaining)
            else {
                b.beginStoreLocal(result)
                if (tail) savedApply(e, fn, suffix, input,
                    evaluatedArguments.copyOfRange(start, count), remaining, true)
                else checkpointedCall(e, fn, suffix, input,
                    evaluatedArguments.copyOfRange(start, count), remaining, callerMask)
                b.endStoreLocal()
            }
            b.emitBranch(complete)
        }
        b.emitLabel(complete)
        if (finishTuple == null) b.emitLoadLocal(result)
        b.endBlock()
    }

    /** Capture callees after evaluating operands once, including compact and typed inputs. */
    private fun checkpointedApplication(e: Emission, function: Expression, arguments: List<Expression>,
                                        evaluatedArguments: BooleanArray, layout: ArgumentLayout?, tail: Boolean) {
        val b = e.builder
        b.beginBlock()
        val fn = b.createLocal("captured application function", "object")
        val callerMask = b.createLocal("captured application caller mask", "object")
        val result = b.createLocal("captured application result", "object")
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal()
        val values = if (layout?.requiresTyped == true) {
            List(layout.physicalArity) { b.createLocal("captured typed input $it", null) }.also { slots ->
                arguments.forEachIndexed { index, argument ->
                    val offset = layout.offset(index)
                    if (layout.isTuple(index)) argument.emitTuple(e, slots.subList(offset, layout.offset(index + 1)))
                    else {
                        b.beginStoreLocal(slots[offset]); argument.emit(e); b.endStoreLocal()
                    }
                }
            }
        } else {
            arrayListOf<BytecodeLocal>().also { slots ->
                arguments.forEachIndexed { index, argument ->
                    if (layout?.isEmpty(index) == true) argument.emitTuple(e, emptyList())
                    else slots += b.createLocal("captured application operand $index", null).also { local ->
                        b.beginStoreLocal(local); argument.emit(e); b.endStoreLocal()
                    }
                }
            }
        }
        b.beginStoreLocal(callerMask); b.emitCurrentMask(); b.endStoreLocal()
        if (arguments.isEmpty()) {
            if (tail) savedApply(e, fn, values, layout, evaluatedArguments, 0, true)
            else checkpointedCall(e, fn, values, layout, evaluatedArguments, 0, callerMask)
            b.endBlock()
            return
        }
        b.beginConditional()
        b.beginMatchLiteral(1L)
        b.beginLessThan()
        b.beginClosureArity(); b.emitLoadLocal(fn); b.endClosureArity()
        b.emitLoadConstant(arguments.size.toLong())
        b.endLessThan()
        b.endMatchLiteral()
        stagedOverapplication(e, fn, values, layout, evaluatedArguments, callerMask, result, arguments.size, tail)
        if (tail) savedApply(e, fn, values, layout, evaluatedArguments, arguments.size, true)
        else checkpointedCall(e, fn, values, layout, evaluatedArguments, arguments.size, callerMask)
        b.endConditional()
        b.endBlock()
    }

    private fun application(function: Expression, arguments: List<Expression>, scope: Scope, tail: Boolean): Expression {
        val context = scope.function
        val evaluatedArguments = arguments.map { it.proof.evaluated }.toBooleanArray()
        val inputLayout = ArgumentLayout.fromProofs(arguments.map { it.proof })
        val loop = tail && context.inputLayout == null && inputLayout == null && arguments.size <= context.formalArity && context.formalArity > 0
        // Even a root without a direct self-call can receive A -> B -> ... -> A.
        if (tail) context.mayLoop = true
        return evaluated(Expression { e ->
            val b = e.builder
            if (enableAsync) {
                b.beginBlock()
                emitAsyncPoll(e)
            }
            val reentryResult = if (tail) {
                b.beginBlock()
                b.createLocal("tail result", null).also { b.beginStoreLocal(it) }
            } else null
            if (resumable && !loop) {
                checkpointedApplication(e, function, arguments, evaluatedArguments, inputLayout, tail)
            } else if (inputLayout?.requiresTyped == true) {
                typedArguments(e, function, arguments, inputLayout, tail)
            } else if (inputLayout != null) {
                compactArguments(e, function, arguments, inputLayout) { fn, values ->
                    b.beginApplyCompact(inputLayout, tail, metrics, evaluatedArguments)
                    b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
                    b.endApplyCompact()
                }
            } else if (!loop) {
                b.beginApply(arguments.size, tail, metrics, evaluatedArguments)
                requireClosure(function).emit(e)
                arguments.forEach { it.emit(e) }
                b.endApply()
            } else {
                // Evaluate every operand before assigning any formal: tail calls are parallel moves.
                b.beginBlock()
                val fn = b.createLocal("tail function", null)
                b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal()
                val args = arguments.mapIndexed { index, arg ->
                    b.createLocal("tail operand $index", null).also { local ->
                        b.beginStoreLocal(local); arg.emit(e); b.endStoreLocal()
                    }
                }
                b.beginConditional()
                b.beginIsSelf(arguments.size, context.formalArity); b.emitLoadLocal(fn); b.endIsSelf()
                b.beginBlock()
                val prefix = context.formalArity - arguments.size
                // Direct self-entry bypasses Dispatch. Enforce the full contract,
                // including unused formals and a PAP's previously supplied prefix,
                // before replacing any capture or argument in this activation.
                val strictArguments = context.entryStrict.mapIndexed { index, strict ->
                    if (!strict || index >= prefix && arguments[index - prefix].proof.evaluated) null
                    else b.createLocal("strict tail operand $index", null).also { temporary ->
                        b.beginStoreLocal(temporary)
                        if (enableAsync) {
                            force(Expression { reentry ->
                                val builder = reentry.builder
                                if (index < prefix) {
                                    builder.beginReadSupplied(index); builder.emitLoadLocal(fn); builder.endReadSupplied()
                                } else builder.emitLoadLocal(args[index - prefix])
                            }).emit(e)
                        } else {
                            b.beginForceValue(metrics, false)
                            if (index < prefix) {
                                b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied()
                            } else b.emitLoadLocal(args[index - prefix])
                            b.endForceValue()
                        }
                        b.endStoreLocal()
                    }
                }
                context.captures.forEachIndexed { index, local ->
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
                    b.beginClosureEnvironment(); b.emitLoadLocal(fn); b.endClosureEnvironment()
                    if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
                    b.endStoreLocal()
                }
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    restoreArgument(e, local) {
                        if (strictArguments[index] != null) b.emitLoadLocal(strictArguments[index]!!)
                        else if (index < prefix) {
                            b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied()
                        } else b.emitLoadLocal(args[index - prefix])
                    }
                } }
                b.emitBranch(e.continueLabel!!)
                // Unreachable value satisfies the expression shape of Conditional's then branch.
                b.emitLoadConstant(Unit)
                b.endBlock()
                if (resumable) {
                    val savedFunction = ProvenExpression(Expression { it.builder.emitLoadLocal(fn) },
                        function.proof.copy(evaluated = true))
                    val savedArguments = args.mapIndexed { index, local ->
                        ProvenExpression(Expression { it.builder.emitLoadLocal(local) }, arguments[index].proof)
                    }
                    checkpointedApplication(e, savedFunction, savedArguments, evaluatedArguments, null, true)
                } else {
                    b.beginApply(arguments.size, true, metrics, evaluatedArguments)
                    b.emitLoadLocal(fn)
                    args.forEach { b.emitLoadLocal(it) }
                    b.endApply()
                }
                b.endConditional()
                b.endBlock()
            }
            if (reentryResult != null) {
                b.endStoreLocal()
                b.beginConditional()
                b.beginIsTailReentry(); b.emitLoadLocal(reentryResult); b.emitLoadConstant(false); b.endIsTailReentry()
                b.beginBlock()
                restoreTailArguments(e, context, reentryResult)
                b.emitBranch(e.continueLabel!!)
                b.emitLoadConstant(Unit)
                b.endBlock()
                b.emitLoadLocal(reentryResult)
                b.endConditional()
                b.endBlock()
            }
            if (enableAsync) b.endBlock()
        })
    }

    /** A join transfer is a parallel move into this activation followed by bytecode control flow. */
    private fun joinCall(target: JoinTarget, arguments: List<Expression>): Expression {
        if (arguments.size != target.parameters.size) throw UnsupportedCore("Local join is not exactly saturated")
        arguments.forEachIndexed { index, actual ->
            CoreRepresentations.requireJoinArgument(CoreRepresentations.binder(target.parameters[index]), actual.proof)
        }
        return ProvenExpression(ResultExpression { e, destination ->
            val b = e.builder
            val region = e.joins[target.region] ?: throw RuntimeFault("Local join escapes its owning activation")
            b.beginBlock()
            // Saving all operands first is required for swaps and mutually recursive joins.
            val temporaries = arguments.mapIndexed { index, argument ->
                if (target.locals[index] == null) {
                    argument.emitTuple(e, emptyList())
                    null
                } else b.createLocal("join operand $index", null).also { temporary ->
                    b.beginStoreLocal(temporary)
                    argument.emit(e)
                    b.endStoreLocal()
                }
            }
            target.locals.forEachIndexed { index, local ->
                if (local != null) restoreArgument(e, local) { b.emitLoadLocal(temporaries[index]!!) }
            }
            // A failed operand is not a transfer. Count only after all parallel moves succeed.
            b.emitJoinTransfer(metrics)
            if (target.index > region.emittedIndex) {
                b.emitBranch(region.labels[target.index])
            } else {
                val selector = region.selector ?: throw RuntimeFault("Backward transfer into a nonrecursive join group")
                val next = region.next ?: throw RuntimeFault("Missing recursive join continuation")
                b.beginStoreLocal(selector); b.emitLoadConstant(target.index.toLong()); b.endStoreLocal()
                b.emitBranch(next)
            }
            // Unreachable value preserves the surrounding expression's builder signature.
            if (destination == null) b.emitLoadConstant(Unit)
            b.endBlock()
        }, target.result.copy(evaluated = true))
    }

    private fun joinRegion(group: List<Map<String, Any?>>, expression: List<Any?>, recursive: Boolean,
                           scope: Scope, tail: Boolean): Expression {
        CoreJoins.validate(group, expression, recursive)
        val definitions = CoreJoins.definitions(group) ?: throw RuntimeFault("Missing local join definitions")
        val shadowed = if (recursive) definitions.map { it.id }.toSet() else emptySet()
        val capturedTupleFields = linkedMapOf<Int, Local>()
        definitions.forEach { definition ->
            definition.parameters.forEach {
                val proof = CoreRepresentations.binder(it)
                CoreRepresentations.requireJoinInput(proof)
                if (proof.isEmptyTuple && representation(it)) throw RuntimeFault("Tuple join formal must be unlifted")
            }
            val formals = definition.parameters.map { it["id"] as String }.toSet()
            (freeVariables(definition.body) - formals - shadowed).forEach { id ->
                scope.tuples[id]?.let { (proof, fields) ->
                    CoreRepresentations.requireInput(proof)
                    val leaves = TupleShape.flatten(proof)
                    if (fields.size != leaves.size) throw RuntimeFault("Tuple join capture disagrees with its physical slots")
                    fields.forEachIndexed { index, field ->
                        if (!field.proof.present || !TupleShape.compatible(leaves[index], field.proof))
                            throw RuntimeFault("Tuple join capture has a mismatched leaf proof")
                        capturedTupleFields[field.id]?.let { previous ->
                            if (!TupleShape.compatible(previous.proof, field.proof))
                                throw RuntimeFault("Tuple join captures disagree on a shared physical slot")
                        }
                        capturedTupleFields[field.id] = field
                    }
                }
            }
        }
        val region = JoinRegion()
        val local = scope.child()
        val targets = definitions.mapIndexed { index, definition ->
            val entryStrict = CoreEntries.join(definition)
            val parameters = definition.parameters.mapIndexed { parameterIndex, parameter ->
                // Join formal names have lexical scope only in their own body.
                val proof = CoreRepresentations.binder(parameter).copy(evaluated = !representation(parameter) || entryStrict[parameterIndex])
                if (proof.isEmptyTuple) null else Local(nextLocal++, parameter["id"] as String,
                    if (proof.present) proof.isLong else !representation(parameter) && parameter["coercion"] != true, proof)
            }
            JoinTarget(region, index, definition.parameters, parameters, entryStrict, definition.result).also { local.bindJoin(definition.id, it) }
        }
        localJoinCount += targets.size
        val bodies = definitions.mapIndexed { index, definition ->
            val bodyScope = (if (recursive) local else scope).child()
            targets[index].locals.forEachIndexed { parameterIndex, parameter ->
                if (parameter != null) bodyScope.bindLocal(parameter.name, parameter)
                else {
                    val raw = definition.parameters[parameterIndex]
                    bodyScope.bindTuple(raw["id"] as String, CoreRepresentations.binder(raw).copy(evaluated = true), emptyList())
                }
            }
            val body = compile(definition.body, bodyScope.withSource(sources.binding(definition.binding, scope.source)), tail)
            CoreRepresentations.requireNoVector(body.proof, "join result")
            CoreRepresentations.requireNoSum(body.proof, "join result")
            ProvenExpression(body, body.proof.refine(definition.result.copy(evaluated = false)))
        }
        val entry = compile(expression, local, tail)
        CoreRepresentations.requireNoVector(entry.proof, "join result")
        CoreRepresentations.requireNoSum(entry.proof, "join result")
        val proof = entry.proof.refine(CoreRepresentations.expression(expression).copy(evaluated = false))
        bodies.forEach { TupleShape.requireCompatible(proof, it.proof) }
        return ProvenExpression(ResultExpression { e, destination ->
            if (proof.isTuple != (destination != null)) throw RuntimeFault("Join result destination disagrees with its representation")
            // A local join branches inside the current root. Every captured leaf
            // must still name an active typed local in that root's lexical scope.
            capturedTupleFields.values.forEach { if (it.id !in e.locals) throw RuntimeFault("Tuple join capture escaped its lexical slots") }
            val b = e.builder
            b.beginBlock()
            val result = if (destination == null) b.createLocal("join result", null) else null
            val selector = if (recursive) b.createLocal("join selector", "primitive") else null
            val exit = b.createLabel()
            targets.flatMap { it.locals.filterNotNull() }.forEach { e.locals[it.id] = b.createLocal(it.name, if (it.primitive) "primitive" else "object") }
            if (selector != null) {
                b.beginStoreLocal(selector); b.emitLoadConstant(-1L); b.endStoreLocal()
                b.beginWhile()
                b.emitLoadConstant(true)
                b.beginBlock()
            }
            val next = if (recursive) b.createLabel() else null
            val labels = targets.map { b.createLabel() }
            val active = JoinEmission(selector, next, labels)
            e.joins[region] = active
            // Nonrecursive RHSs cannot jump into their own group. They need only
            // forward branches; only recursive groups need a selector/backedge.
            if (selector != null) targets.forEachIndexed { index, _ ->
                b.beginIfThen()
                b.beginMatchLiteral(index.toLong()); b.emitLoadLocal(selector); b.endMatchLiteral()
                b.emitBranch(labels[index])
                b.endIfThen()
            }
            if (result != null) { b.beginStoreLocal(result); entry.emit(e); b.endStoreLocal() }
            else entry.emitTuple(e, destination!!)
            b.emitBranch(exit)
            targets.forEachIndexed { index, _ ->
                b.emitLabel(labels[index])
                active.emittedIndex = index
                if (result != null) { b.beginStoreLocal(result); bodies[index].emit(e); b.endStoreLocal() }
                else bodies[index].emitTuple(e, destination!!)
                b.emitBranch(exit)
            }
            if (next != null) {
                b.emitLabel(next)
                if (enableAsync) emitAsyncPoll(e)
                b.endBlock()
                b.endWhile()
            }
            b.emitLabel(exit)
            if (result != null) b.emitLoadLocal(result)
            b.endBlock()
            e.joins.remove(region)
            targets.flatMap { it.locals.filterNotNull() }.forEach { e.locals.remove(it.id) }
        }, proof.copy(evaluated = entry.proof.evaluated && bodies.all { it.proof.evaluated }))
    }

    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expression = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            CoreVectors.requireVariableProof(scope.locals[id]?.proof ?: globalProofs[id], CoreRepresentations.expression(expr))
            scope.tuples[id]?.let { (proof, fields) ->
                TupleShape.requireCompatible(proof, CoreRepresentations.expression(expr))
                tupleExpression(proof) { e, destination ->
                fields.forEachIndexed { index, field ->
                    e.builder.beginStoreLocal(destination[index]); read(field).emit(e); e.builder.endStoreLocal()
                }
            } } ?: scope.joins[id]?.let { joinCall(it, emptyList()) }
                ?: scope.locals[id]?.let { read(it) } ?: globals[id]?.let { binding ->
                    val stored = globalProofs.getValue(id)
                    ProvenExpression(if (stored.isLong && stored.evaluated) Expression { it.builder.emitReadGlobalLong(binding) }
                    else Expression { it.builder.emitReadGlobal(binding) }, stored)
                } ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> constant(literal(expr[1] as String, expr[2] as String)).let {
            if (expr[1] in listOf("int8", "word8", "int16", "word16", "int32", "word32")) ProvenExpression(it, CoreRepresentations.narrowLiteralProof(expr))
            else if (expr[1] == "bignat") ProvenExpression(it, BigNatLiterals.proof(expr)) else it
        }
        "void" -> constant(Unit)
        "lam" -> {
            val args = expr[1] as List<Map<String, Any?>>
            closure(function("lambda ${args.joinToString { it["name"].toString() }}", args, expr[2] as List<Any?>, scope, CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr)), args.size)
        }
        "app" -> {
            val fn = expr[1] as List<Any?>; val args = expr[2] as List<List<Any?>>
            val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Application lacks representation flags")
            if (flags.size != args.size) throw RuntimeFault("Application representation flag count mismatch")
            val callStrict = CoreCallDemands.lowerApplication(expr, callDemandsEnabled)
            val tupleProof = CoreRepresentations.expression(expr)
            val tupleOperation = if (fn[0] == "prim") TupleArithmeticOp.named(fn[1] as String) else null
            val defined = fn[0] == "var" && (fn[1] in globals || fn[1] in scope.locals)
            val stackClone = CoreStackForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags)
            val stackInfo = CoreStackInfoForeign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val originalStdio = CoreOriginalStdio.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val stableFree = CoreStablePointers.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val managedFile = CoreManagedFiles.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep"))
            val javascript = if (!stackClone && stackInfo == null && originalStdio == null && managedFile == null) CoreJavaScript.validate(expr, defined) else null
            val md5 = if (javascript == null) CoreMd5Foreign.validate(CoreRepresentations.metadata(expr),
                args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags, CoreRepresentations.metadata(expr)?.get("rep")) else null
            val polyglot = if (!stackClone && stackInfo == null && originalStdio == null && !stableFree && managedFile == null && javascript == null && md5 == null) CorePolyglot.validate(expr, defined) else null
            if (stackClone) {
                CoreStackForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val state = args.single()
                CoreStackForeign.validateBinding(if (state[0] == "var")
                    scope.locals[state[1]]?.proof ?: globalProofs[state[1]] else null)
                val operand = compile(state, scope, false)
                CoreStackForeign.validateState(operand.proof)
                tupleExpression(tupleProof) { e, destination ->
                    e.builder.beginCloneMyStack(destination.single())
                    operand.emit(e)
                    e.builder.endCloneMyStack()
                }
            } else if (stackInfo != null) {
                val layout = CoreStackInfoForeign.requireLayout(stackTargetLayout)
                CoreStackInfoForeign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        CoreStackInfoForeign.validateOperand(stackInfo, index, operand.proof,
                            if (argument[0] == "var") scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                if (stackInfo == OriginalStackInfoOp.STACK_INFO) ProvenExpression(Expression { e ->
                    e.builder.beginOriginalStackInfo(layout)
                    operands.single().emit(e)
                    e.builder.endOriginalStackInfo()
                }, tupleProof.copy(evaluated = true)) else if (stackInfo == OriginalStackInfoOp.STACK_FIELDS)
                    ProvenExpression(Expression { e ->
                        e.builder.beginOriginalStackFields(layout)
                        operands.single().emit(e)
                        e.builder.endOriginalStackFields()
                    }, tupleProof.copy(evaluated = true))
                else if (!stackInfo.tupleResult) ProvenExpression(Expression { e ->
                    e.builder.beginOriginalStackIncompatibleGetter(layout, stackInfo)
                    operands.forEach { it.emit(e) }
                    e.builder.endOriginalStackIncompatibleGetter()
                }, tupleProof.copy(evaluated = true)) else tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    if (stackInfo == OriginalStackInfoOp.FRAME_INFO)
                        b.beginOriginalStackFrameInfo(layout, destination[0], destination[1])
                    else if (stackInfo == OriginalStackInfoOp.SMALL_BITMAP)
                        b.beginOriginalStackSmallBitmap(layout, destination[0], destination[1])
                    else if (stackInfo == OriginalStackInfoOp.ADVANCE)
                        b.beginOriginalStackAdvance(layout, destination[0], destination[1], destination[2])
                    else if (stackInfo == OriginalStackInfoOp.LOOKUP_IPE)
                        b.beginOriginalStackLookupIpe(layout, destination.single())
                    else b.beginOriginalStackIncompatibleTupleGetter(layout, stackInfo)
                    operands.forEach { it.emit(e) }
                    if (stackInfo == OriginalStackInfoOp.FRAME_INFO) b.endOriginalStackFrameInfo()
                    else if (stackInfo == OriginalStackInfoOp.SMALL_BITMAP) b.endOriginalStackSmallBitmap()
                    else if (stackInfo == OriginalStackInfoOp.ADVANCE) b.endOriginalStackAdvance()
                    else if (stackInfo == OriginalStackInfoOp.LOOKUP_IPE) b.endOriginalStackLookupIpe()
                    else b.endOriginalStackIncompatibleTupleGetter()
                }
            } else if (originalStdio != null) {
                CoreOriginalStdio.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.mapIndexed { index, argument ->
                    compile(argument, scope, false).also { operand ->
                        if (originalStdio.readiness || originalStdio.iconv) CoreOriginalStdio.validateReadyOperand(originalStdio, index,
                            operand.proof, if (argument[0] == "var")
                                scope.locals[argument[1]]?.proof ?: globalProofs[argument[1]] else null)
                    }
                }
                tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    val result = destination.single()
                    val status = originalStdio == OriginalStdioOp.ERRNO || originalStdio == OriginalStdioOp.ISATTY ||
                        originalStdio == OriginalStdioOp.CLOSE
                    if (originalStdio == OriginalStdioOp.LOCALE) b.beginOriginalLocale(result)
                    else if (originalStdio == OriginalStdioOp.ICONV_OPEN) b.beginOriginalIconvOpen(result)
                    else if (originalStdio == OriginalStdioOp.ICONV_CLOSE) b.beginOriginalIconvClose(result)
                    else if (originalStdio == OriginalStdioOp.ICONV) b.beginOriginalIconv(result)
                    else if (originalStdio.readiness) b.beginOriginalStdioReady(result)
                    else if (originalStdio == OriginalStdioOp.SEEK) b.beginFileSeek(result)
                    else if (originalStdio == OriginalStdioOp.TRUNCATE) b.beginFileSetSize(result)
                    else if (status) b.beginOriginalStdioStatus(result, originalStdio)
                    else b.beginOriginalStdioTransfer(result,
                        originalStdio == OriginalStdioOp.READ_SAFE || originalStdio == OriginalStdioOp.READ_UNSAFE)
                    // errno's original ABI has only State#. This internal zero
                    // fills the shared instruction's unused typed descriptor lane.
                    if (originalStdio == OriginalStdioOp.ERRNO) b.emitLoadConstant(0L)
                    if (originalStdio == OriginalStdioOp.SEEK) {
                        operands.take(3).forEach { it.emit(e) }
                        // FileSeek already has four typed lanes. Keep the State#
                        // check before the effect, then use its final internal
                        // lane as a constant original-ABI selector.
                        b.beginBlock()
                        b.beginRequireIOState(); operands[3].emit(e); b.endRequireIOState()
                        b.emitLoadConstant(OriginalStdioOp.SEEK)
                        b.endBlock()
                    } else if (originalStdio == OriginalStdioOp.TRUNCATE) {
                        operands.take(2).forEach { it.emit(e) }
                        b.beginBlock()
                        b.beginRequireIOState(); operands[2].emit(e); b.endRequireIOState()
                        b.emitLoadConstant(OriginalStdioOp.TRUNCATE)
                        b.endBlock()
                    } else operands.forEach { it.emit(e) }
                    if (originalStdio == OriginalStdioOp.LOCALE) b.endOriginalLocale()
                    else if (originalStdio == OriginalStdioOp.ICONV_OPEN) b.endOriginalIconvOpen()
                    else if (originalStdio == OriginalStdioOp.ICONV_CLOSE) b.endOriginalIconvClose()
                    else if (originalStdio == OriginalStdioOp.ICONV) b.endOriginalIconv()
                    else if (originalStdio.readiness) b.endOriginalStdioReady()
                    else if (originalStdio == OriginalStdioOp.SEEK) b.endFileSeek()
                    else if (originalStdio == OriginalStdioOp.TRUNCATE) b.endFileSetSize()
                    else if (status) b.endOriginalStdioStatus() else b.endOriginalStdioTransfer()
                }
            } else if (stableFree) {
                CoreStablePointers.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.map { compile(it, scope, false) }
                tupleExpression(tupleProof) { e, destination ->
                    if (destination.isNotEmpty()) throw RuntimeFault("StablePtr free has no result field")
                    e.builder.beginFreeStablePointer()
                    operands.forEach { it.emit(e) }
                    e.builder.endFreeStablePointer()
                }
            } else if (managedFile != null) {
                CoreManagedFiles.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.map { compile(it, scope, false) }
                tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    val result = destination.single()
                    when (managedFile) {
                        ManagedFileOp.OPEN -> b.beginFileOpen(result)
                        ManagedFileOp.READ -> b.beginFileRead(result)
                        ManagedFileOp.WRITE -> b.beginFileWrite(result)
                        ManagedFileOp.CLOSE -> b.beginFileClose(result)
                        ManagedFileOp.ERROR_KIND -> b.beginFileErrorKind(result)
                        ManagedFileOp.ERROR_MESSAGE -> b.beginFileErrorMessage(result)
                        ManagedFileOp.SEEK -> b.beginFileSeek(result)
                        ManagedFileOp.SIZE -> b.beginFileSize(result)
                        ManagedFileOp.SET_SIZE -> b.beginFileSetSize(result)
                        ManagedFileOp.IS_TERMINAL -> b.beginFileIsTerminal(result)
                        ManagedFileOp.DEVICE_TYPE -> b.beginFileDeviceType(result)
                    }
                    operands.forEach { it.emit(e) }
                    when (managedFile) {
                        ManagedFileOp.OPEN -> b.endFileOpen()
                        ManagedFileOp.READ -> b.endFileRead()
                        ManagedFileOp.WRITE -> b.endFileWrite()
                        ManagedFileOp.CLOSE -> b.endFileClose()
                        ManagedFileOp.ERROR_KIND -> b.endFileErrorKind()
                        ManagedFileOp.ERROR_MESSAGE -> b.endFileErrorMessage()
                        ManagedFileOp.SEEK -> b.endFileSeek()
                        ManagedFileOp.SIZE -> b.endFileSize()
                        ManagedFileOp.SET_SIZE -> b.endFileSetSize()
                        ManagedFileOp.IS_TERMINAL -> b.endFileIsTerminal()
                        ManagedFileOp.DEVICE_TYPE -> b.endFileDeviceType()
                    }
                }
            } else if (md5 != null) {
                CoreMd5Foreign.validateHead(fn, fn.getOrNull(1) in scope.locals || fn.getOrNull(1) in scope.joins || fn.getOrNull(1) in globals)
                val operands = args.map { compile(it, scope, false) }
                tupleExpression(tupleProof) { e, _ ->
                    when (md5) {
                        Md5ForeignOp.INIT -> e.builder.beginMd5Init()
                        Md5ForeignOp.UPDATE -> e.builder.beginMd5Update()
                        Md5ForeignOp.FINAL -> e.builder.beginMd5Final()
                    }
                    operands.forEach { it.emit(e) }
                    when (md5) {
                        Md5ForeignOp.INIT -> e.builder.endMd5Init()
                        Md5ForeignOp.UPDATE -> e.builder.endMd5Update()
                        Md5ForeignOp.FINAL -> e.builder.endMd5Final()
                    }
                }
            } else if (javascript != null) {
                val operands = args.map { argument(it, scope, false) }
                tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    val locals = operands.mapIndexed { index, operand ->
                        val local = b.createLocal("JavaScript operand $index", null)
                        b.beginStoreLocal(local)
                        when (javascript.arguments.getOrNull(index)) {
                            CoreKind.LONG -> { b.beginToLong(); operand.emit(e); b.endToLong() }
                            CoreKind.DOUBLE -> { b.beginToDouble(); operand.emit(e); b.endToDouble() }
                            else -> operand.emit(e)
                        }
                        b.endStoreLocal()
                        LocalAccessor.constantOf(local)
                    }
                    val source = BytecodeJavaScriptArguments(javascript, locals.dropLast(1).toTypedArray(), locals.last())
                    when (javascript.result) {
                        CoreKind.LONG -> b.emitJavaScriptInt(source, destination.single())
                        CoreKind.DOUBLE -> b.emitJavaScriptDouble(source, destination.single())
                        CoreKind.VOID -> b.emitJavaScriptVoid(source)
                        else -> throw RuntimeFault("Unsupported JavaScript result")
                    }
                }
            } else if (polyglot != null) {
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                tupleExpression(tupleProof) { e, destination ->
                    when (polyglot) {
                        PolyglotOp.EVAL -> e.builder.beginPolyglotEval(destination[0])
                        PolyglotOp.READ_MEMBER -> e.builder.beginPolyglotReadMember(destination[0])
                        PolyglotOp.EXECUTE_INT -> e.builder.beginPolyglotExecuteInt(destination[0])
                    }
                    operands.forEach { it.emit(e) }
                    when (polyglot) {
                        PolyglotOp.EVAL -> e.builder.endPolyglotEval()
                        PolyglotOp.READ_MEMBER -> e.builder.endPolyglotReadMember()
                        PolyglotOp.EXECUTE_INT -> e.builder.endPolyglotExecuteInt()
                    }
                }
            } else if (fn[0] == "prim" && fn[1] == "tagToEnum#") {
                if (args.size != 1) throw RuntimeFault("tagToEnum#: Exactly one operand required")
                val operand = compile(args[0], scope, false)
                val ids = CoreEnums.validate(expr, operand.proof, constructors)
                val family = EnumFamily(ids.map { dataLayout(it).allocate() }.toTypedArray())
                ProvenExpression(Expression { e ->
                    e.builder.beginTagToEnum(family); operand.emit(e); e.builder.endTagToEnum()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] in CoreDataTags.operations) {
                if (args.size != 1) throw RuntimeFault("dataToTag: Exactly one operand required")
                val operand = argument(args[0], scope, false)
                val ids = CoreDataTags.validate(expr, operand.proof, constructors)
                val family = DataTagFamily(ids.map(::dataLayout).toTypedArray())
                ProvenExpression(Expression { e ->
                    e.builder.beginDataToTag(family); operand.emit(e); e.builder.endDataToTag()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] in CoreVectors.operations) {
                val name = fn[1] as String
                CoreVectors.validate(name, args.map(CoreVectors::argumentProof), tupleProof)
                CoreVectors.validateFlags(flags)
                vectorPrimitive(name, args.map { compile(it, scope, false) })
            } else if (fn[0] == "prim" && fn[1] in setOf("raiseIO#", "catch#", "getMaskingState#",
                    "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#")) {
                val name = fn[1] as String
                CoreSynchronousExceptions.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    when (name) {
                        "raiseIO#" -> {
                            b.beginRaiseIO(); operands.forEach { it.emit(e) }; b.endRaiseIO()
                        }
                        "getMaskingState#" -> {
                            b.beginGetMaskingState(destination[0]); operands[0].emit(e); b.endGetMaskingState()
                        }
                        else -> {
                            // All operands and the State# check precede the protected
                            // action, exactly as in the original synchronous primop.
                            val action = b.createLocal("IO action", "object")
                            b.beginStoreLocal(action); operands[0].emit(e); b.endStoreLocal()
                            val handler = if (name == "catch#") b.createLocal("IO handler", "object").also {
                                b.beginStoreLocal(it); operands[1].emit(e); b.endStoreLocal()
                            } else null
                            b.beginRequireIOState()
                            operands[if (handler == null) 1 else 2].emit(e)
                            b.endRequireIOState()
                            val slots = tupleSlots(TupleShape(tupleProof, language), destination)
                            if (handler != null) {
                                b.beginTryCatch()
                                if (!resumable) {
                                    b.beginInvokeIOAction(slots, metrics)
                                    forceSavedCallback(e, action, operands[0].proof); b.emitLoadNull()
                                    b.endInvokeIOAction()
                                } else {
                                    b.beginBlock()
                                    val callerMask = b.createLocal("caught IO action caller mask", "object")
                                    val suspended = b.createLocal("caught IO action suspension", "object")
                                    b.beginStoreLocal(callerMask); b.emitCurrentMask(); b.endStoreLocal()
                                    b.beginTryCatch()
                                    b.beginInvokeIOActionCheckpoint(slots, metrics)
                                    forceSavedCallback(e, action, operands[0].proof); b.emitLoadConstant(true)
                                    b.endInvokeIOActionCheckpoint()
                                    b.beginBlock()
                                    b.beginStoreLocal(suspended)
                                    b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
                                    b.endStoreLocal()
                                    b.beginResumeIOAction(slots)
                                    b.emitLoadLocal(suspended)
                                    b.beginReenterCallMask()
                                    b.beginYield()
                                    b.beginParkCallMask()
                                    b.emitLoadLocal(suspended)
                                    b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
                                    b.emitLoadLocal(callerMask)
                                    b.endParkCallMask()
                                    b.endYield()
                                    b.emitLoadLocal(callerMask)
                                    b.endReenterCallMask()
                                    b.endResumeIOAction()
                                    b.endBlock()
                                    b.endTryCatch()
                                    b.endBlock()
                                }
                                b.beginBlock()
                                val payload = b.createLocal("caught exception payload", "object")
                                b.beginStoreLocal(payload)
                                if (!resumable) {
                                    b.beginRequireGuestFailure(); b.emitLoadException(); b.endRequireGuestFailure()
                                } else {
                                    b.beginRequireCaughtIOFailure(); b.emitLoadException(); b.endRequireCaughtIOFailure()
                                }
                                b.endStoreLocal()
                                val prior = b.createLocal("handler caller mask", "object")
                                b.beginStoreLocal(prior); b.emitEnterHandlerMask(); b.endStoreLocal()
                                b.beginTryFinally(Runnable {
                                    b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask()
                                })
                                if (!resumable) {
                                    b.beginInvokeIOHandler(slots, metrics)
                                    forceSavedCallback(e, handler, operands[1].proof)
                                    b.emitLoadLocal(payload); b.emitLoadLocal(prior)
                                    b.endInvokeIOHandler()
                                } else {
                                    b.beginBlock()
                                    val handlerMask = b.createLocal("caught IO handler active mask", "object")
                                    val suspended = b.createLocal("caught IO handler suspension", "object")
                                    b.beginStoreLocal(handlerMask); b.emitCurrentMask(); b.endStoreLocal()
                                    b.beginTryCatch()
                                    b.beginInvokeIOHandlerCheckpoint(slots, metrics)
                                    forceSavedCallback(e, handler, operands[1].proof)
                                    b.emitLoadLocal(payload); b.emitLoadLocal(prior)
                                    b.endInvokeIOHandlerCheckpoint()
                                    b.beginBlock()
                                    b.beginStoreLocal(suspended)
                                    b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
                                    b.endStoreLocal()
                                    b.beginResumeTupleApplication(slots)
                                    b.emitLoadLocal(suspended)
                                    b.beginReenterCallMask()
                                    b.beginYield()
                                    b.beginParkCallMask()
                                    b.emitLoadLocal(suspended)
                                    b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
                                    b.emitLoadLocal(handlerMask)
                                    b.endParkCallMask()
                                    b.endYield()
                                    b.emitLoadLocal(handlerMask)
                                    b.endReenterCallMask()
                                    b.endResumeTupleApplication()
                                    b.endBlock()
                                    b.endTryCatch()
                                    b.endBlock()
                                }
                                b.endTryFinally()
                                b.endBlock()
                                b.endTryCatch()
                            } else {
                                val target = when (name) {
                                    "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE
                                    "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE
                                    else -> MaskingState.UNMASKED
                                }
                                val prior = b.createLocal("mask caller state", "object")
                                b.beginStoreLocal(prior); b.emitEnterMask(target); b.endStoreLocal()
                                b.beginTryFinally(Runnable {
                                    b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask()
                                })
                                if (!resumable) {
                                    b.beginInvokeIOAction(slots, metrics)
                                    forceSavedCallback(e, action, operands[0].proof); b.emitLoadLocal(prior)
                                    b.endInvokeIOAction()
                                } else {
                                    b.beginBlock()
                                    val actionMask = b.createLocal("masked IO action active mask", "object")
                                    val suspended = b.createLocal("masked IO action suspension", "object")
                                    b.beginStoreLocal(actionMask); b.emitCurrentMask(); b.endStoreLocal()
                                    b.beginTryCatch()
                                    b.beginInvokeMaskedIOActionCheckpoint(slots, metrics)
                                    forceSavedCallback(e, action, operands[0].proof); b.emitLoadLocal(prior)
                                    b.endInvokeMaskedIOActionCheckpoint()
                                    b.beginBlock()
                                    b.beginStoreLocal(suspended)
                                    b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
                                    b.endStoreLocal()
                                    b.beginResumeTupleApplication(slots)
                                    b.emitLoadLocal(suspended)
                                    b.beginReenterCallMask()
                                    b.beginYield()
                                    b.beginParkCallMask()
                                    b.emitLoadLocal(suspended)
                                    b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
                                    b.emitLoadLocal(actionMask)
                                    b.endParkCallMask()
                                    b.endYield()
                                    b.emitLoadLocal(actionMask)
                                    b.endReenterCallMask()
                                    b.endResumeTupleApplication()
                                    b.endBlock()
                                    b.endTryCatch()
                                    b.endBlock()
                                }
                                b.endTryFinally()
                            }
                        }
                    }
                }
            } else if (fn[0] == "prim" && fn[1] == "noDuplicate#") {
                CoreNoDuplicate.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operand = argument(args[0], scope, false)
                ProvenExpression(Expression { e ->
                    val b = e.builder
                    if (checkpoint == null) {
                        b.beginNoDuplicate(); operand.emit(e); b.endNoDuplicate()
                    } else {
                        b.beginBlock()
                        b.beginNoDuplicate(); operand.emit(e); b.endNoDuplicate()
                        b.beginConditional()
                        b.emitCheckpointArmed(checkpoint)
                        b.beginYield(); b.emitLoadConstant(Unit); b.endYield()
                        b.emitLoadConstant(Unit)
                        b.endConditional()
                        b.endBlock()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] == "yield#") {
                CoreYield.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operand = argument(args[0], scope, false)
                ProvenExpression(Expression { e ->
                    val b = e.builder
                    b.beginBlock()
                    b.beginYieldThread(); operand.emit(e); b.endYieldThread()
                    if (enableAsync) emitAsyncPoll(e)
                    b.emitLoadConstant(Unit)
                    b.endBlock()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] == "getCurrentCCS#") {
                CoreCurrentCCS.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                argument(args[0], scope, true) // Compile/prove the lifted dummy, never enter it.
                val state = argument(args[1], scope, false)
                tupleExpression(tupleProof) { e, destination ->
                    e.builder.beginGetCurrentCCS(destination[0])
                    state.emit(e)
                    e.builder.endGetCurrentCCS()
                }
            } else if (fn[0] == "prim" && fn[1] in listOf("fork#", "myThreadId#", "killThread#")) {
                val name = fn[1] as String
                CoreGuestThreads.validate(name, args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (name == "killThread#") {
                    if (!enableAsync) throw UnsupportedCore("killThread# requires resumable bytecode async delivery")
                    ProvenExpression(Expression { e ->
                        val b = e.builder
                        b.beginBlock()
                        val values = operands.mapIndexed { index, operand ->
                            b.createLocal("killThread operand $index", null).also {
                                b.beginStoreLocal(it); operand.emit(e); b.endStoreLocal()
                            }
                        }
                        val sent = b.createLocal("killThread sent request", "object")
                        b.beginStoreLocal(sent)
                        b.beginThreadPrimitive(BytecodeRoot.ThreadPrimitiveKind.BEGIN_KILL)
                        values.forEach(b::emitLoadLocal)
                        b.endThreadPrimitive()
                        b.endStoreLocal()
                        val retry = b.createLocal("killThread wait pending", "primitive")
                        val incoming = b.createLocal("killThread incoming request", "object")
                        val active = b.createLocal("killThread logical mask", "object")
                        val discard = b.createLocal("killThread resume value", "object")
                        b.beginStoreLocal(retry); b.emitLoadConstant(true); b.endStoreLocal()
                        b.beginWhile()
                        b.emitLoadLocal(retry)
                        b.beginBlock()
                        b.beginTryCatch()
                        b.beginBlock()
                        b.beginStoreLocal(discard)
                        b.beginThreadPrimitive(BytecodeRoot.ThreadPrimitiveKind.FINISH_KILL)
                        b.emitLoadLocal(sent); b.emitLoadConstant(Unit); b.emitLoadConstant(Unit)
                        b.endThreadPrimitive()
                        b.endStoreLocal()
                        b.beginStoreLocal(retry); b.emitLoadConstant(false); b.endStoreLocal()
                        b.endBlock()
                        b.beginBlock()
                        b.beginStoreLocal(incoming)
                        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
                        b.endStoreLocal()
                        b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal()
                        b.beginStoreLocal(discard)
                        b.beginReenterCallMask()
                        b.beginYield()
                        b.beginParkAsyncMask()
                        b.emitLoadLocal(incoming)
                        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
                        b.endParkAsyncMask()
                        b.endYield()
                        b.emitLoadLocal(active)
                        b.endReenterCallMask()
                        b.endStoreLocal()
                        b.endBlock()
                        b.endTryCatch()
                        b.endBlock()
                        b.endWhile()
                        emitAsyncPoll(e) // Self-target delivery occurs only after enqueue.
                        b.emitLoadConstant(Unit)
                        b.endBlock()
                    }, tupleProof.copy(evaluated = true))
                } else tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    b.beginStoreLocal(destination[0])
                    b.beginThreadPrimitive(if (name == "fork#") BytecodeRoot.ThreadPrimitiveKind.FORK
                        else BytecodeRoot.ThreadPrimitiveKind.MY)
                    if (name == "fork#") operands[0].emit(e) else b.emitLoadConstant(Unit)
                    operands.last().emit(e)
                    b.emitLoadConstant(Unit)
                    b.endThreadPrimitive()
                    b.endStoreLocal()
                }
            } else if (fn[0] == "prim" && MVarOp.named(fn[1] as String) != null) {
                val operation = MVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                operation.validateBindings(args.map(CoreRepresentations::expression), args.map {
                    if (it[0] == "var") scope.locals[it[1]]?.proof ?: globalProofs[it[1]] else null
                })
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                operation.validate(operands.map { it.proof }, flags, tupleProof)
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    if (enableAsync && operation in setOf(MVarOp.TAKE, MVarOp.READ)) {
                        emitBlockingMVar(e, operands, false) { values ->
                            e.builder.beginReadMVar(destination[0], operation == MVarOp.TAKE, true)
                            values.forEach(e.builder::emitLoadLocal)
                            e.builder.endReadMVar()
                        }
                    } else {
                    when (operation) {
                        MVarOp.NEW -> e.builder.beginNewMVar(destination[0])
                        MVarOp.TAKE, MVarOp.READ -> e.builder.beginReadMVar(destination[0], operation == MVarOp.TAKE, false)
                        MVarOp.TRY_TAKE, MVarOp.TRY_READ ->
                            e.builder.beginTryReadMVar(destination[0], destination[1], operation == MVarOp.TRY_TAKE)
                        MVarOp.TRY_PUT -> e.builder.beginTryPutMVar(destination[0])
                        MVarOp.IS_EMPTY -> e.builder.beginIsEmptyMVar(destination[0])
                        else -> error("Not a tuple MVar operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        MVarOp.NEW -> e.builder.endNewMVar()
                        MVarOp.TAKE, MVarOp.READ -> e.builder.endReadMVar()
                        MVarOp.TRY_TAKE, MVarOp.TRY_READ -> e.builder.endTryReadMVar()
                        MVarOp.TRY_PUT -> e.builder.endTryPutMVar()
                        MVarOp.IS_EMPTY -> e.builder.endIsEmptyMVar()
                        else -> error("Not a tuple MVar operation")
                    }
                    }
                } else ProvenExpression(Expression { e ->
                    if (enableAsync && operation == MVarOp.PUT) {
                        emitBlockingMVar(e, operands, true) { values ->
                            e.builder.beginPutMVar(true)
                            values.forEach(e.builder::emitLoadLocal)
                            e.builder.endPutMVar()
                        }
                    } else {
                        e.builder.beginPutMVar(false); operands.forEach { it.emit(e) }; e.builder.endPutMVar()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && MutVarOp.named(fn[1] as String) != null) {
                val operation = MutVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        MutVarOp.NEW -> e.builder.beginNewMutVar(destination[0])
                        MutVarOp.READ -> e.builder.beginReadMutVar(destination[0])
                        MutVarOp.SWAP -> e.builder.beginSwapMutVar(destination[0])
                        else -> error("Not a tuple MutVar operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        MutVarOp.NEW -> e.builder.endNewMutVar()
                        MutVarOp.READ -> e.builder.endReadMutVar()
                        MutVarOp.SWAP -> e.builder.endSwapMutVar()
                        else -> error("Not a tuple MutVar operation")
                    }
                } else ProvenExpression(Expression { e ->
                    e.builder.beginWriteMutVar(); operands.forEach { it.emit(e) }; e.builder.endWriteMutVar()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && StablePointerOp.named(fn[1] as String) != null) {
                val operation = StablePointerOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    e.builder.beginStablePointerTuple(destination.single(), operation)
                    operands.forEach { it.emit(e) }
                    e.builder.endStablePointerTuple()
                } else ProvenExpression(Expression { e ->
                    e.builder.beginEqualStablePointers()
                    operands.forEach { it.emit(e) }
                    e.builder.endEqualStablePointers()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ArrayOp.named(fn[1] as String) != null) {
                val operation = ArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        ArrayOp.NEW -> e.builder.beginNewArray(destination[0])
                        ArrayOp.READ -> e.builder.beginReadArray(destination[0])
                        ArrayOp.FREEZE, ArrayOp.UNSAFE_THAW -> e.builder.beginFreezeArray(destination[0])
                        ArrayOp.FREEZE_COPY, ArrayOp.THAW, ArrayOp.CLONE_MUTABLE -> e.builder.beginCopyArraySlice(destination[0])
                        ArrayOp.INDEX -> e.builder.beginIndexArray(destination[0])
                        else -> error("Not a tuple array operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ArrayOp.NEW -> e.builder.endNewArray()
                        ArrayOp.READ -> e.builder.endReadArray()
                        ArrayOp.FREEZE, ArrayOp.UNSAFE_THAW -> e.builder.endFreezeArray()
                        ArrayOp.FREEZE_COPY, ArrayOp.THAW, ArrayOp.CLONE_MUTABLE -> e.builder.endCopyArraySlice()
                        ArrayOp.INDEX -> e.builder.endIndexArray()
                        else -> error("Not a tuple array operation")
                    }
                } else ProvenExpression(Expression { e ->
                    when (operation) {
                        ArrayOp.CLONE -> e.builder.beginCloneArray()
                        ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> e.builder.beginSizeArray()
                        ArrayOp.COPY, ArrayOp.COPY_MUTABLE -> e.builder.beginTransferArray(operation == ArrayOp.COPY_MUTABLE)
                        else -> e.builder.beginWriteArray()
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ArrayOp.CLONE -> e.builder.endCloneArray()
                        ArrayOp.SIZE, ArrayOp.SIZE_MUTABLE -> e.builder.endSizeArray()
                        ArrayOp.COPY, ArrayOp.COPY_MUTABLE -> e.builder.endTransferArray()
                        else -> e.builder.endWriteArray()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && SmallArrayOp.named(fn[1] as String) != null) {
                val operation = SmallArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        SmallArrayOp.NEW -> e.builder.beginNewSmallArray(destination[0])
                        SmallArrayOp.READ -> e.builder.beginReadSmallArray(destination[0])
                        SmallArrayOp.INDEX -> e.builder.beginIndexSmallArray(destination[0])
                        SmallArrayOp.FREEZE, SmallArrayOp.UNSAFE_THAW -> e.builder.beginFreezeSmallArray(destination[0])
                        SmallArrayOp.GET_SIZE_MUTABLE -> e.builder.beginGetSizeSmallMutableArray(destination[0])
                        SmallArrayOp.CLONE_MUTABLE, SmallArrayOp.SAFE_FREEZE, SmallArrayOp.THAW ->
                            e.builder.beginCopySmallArraySlice(destination[0])
                        else -> error("Not a tuple SmallArray operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        SmallArrayOp.NEW -> e.builder.endNewSmallArray()
                        SmallArrayOp.READ -> e.builder.endReadSmallArray()
                        SmallArrayOp.INDEX -> e.builder.endIndexSmallArray()
                        SmallArrayOp.FREEZE, SmallArrayOp.UNSAFE_THAW -> e.builder.endFreezeSmallArray()
                        SmallArrayOp.GET_SIZE_MUTABLE -> e.builder.endGetSizeSmallMutableArray()
                        SmallArrayOp.CLONE_MUTABLE, SmallArrayOp.SAFE_FREEZE, SmallArrayOp.THAW ->
                            e.builder.endCopySmallArraySlice()
                        else -> error("Not a tuple SmallArray operation")
                    }
                } else ProvenExpression(Expression { e ->
                    when (operation) {
                        SmallArrayOp.WRITE -> e.builder.beginWriteSmallArray()
                        SmallArrayOp.CLONE -> e.builder.beginCloneSmallArray()
                        SmallArrayOp.COPY, SmallArrayOp.COPY_MUTABLE -> e.builder.beginTransferSmallArray(operation == SmallArrayOp.COPY_MUTABLE)
                        else -> e.builder.beginSizeSmallArray()
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        SmallArrayOp.WRITE -> e.builder.endWriteSmallArray()
                        SmallArrayOp.CLONE -> e.builder.endCloneSmallArray()
                        SmallArrayOp.COPY, SmallArrayOp.COPY_MUTABLE -> e.builder.endTransferSmallArray()
                        else -> e.builder.endSizeSmallArray()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && VectorByteArrayOp.named(fn[1] as String) != null) {
                val operation = VectorByteArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                vectorByteArray(operation, args.map { compile(it, scope, false) })
            } else if (fn[0] == "prim" && fn[1] == "touch#") {
                CoreTouch.validateRaw(args.map { CoreRepresentations.metadata(it)?.get("rep") }, flags,
                    CoreRepresentations.metadata(expr)?.get("rep"))
                val lowered = argument(args[0], scope, flags[0] as Boolean)
                // A newly delayed lifted expression has an untyped thunk carrier.
                // Its body was checked by lowering; refine without asserting WHNF
                // or permitting an incompatible known stored representation.
                val kept = ProvenExpression(lowered,
                    lowered.proof.refine(CoreRepresentations.expression(args[0]).copy(evaluated = false)))
                val state = compile(args[1], scope, false)
                CoreTouch.validate(listOf(kept.proof, state.proof), flags, tupleProof)
                ProvenExpression(Expression { e ->
                    e.builder.beginTouch()
                    kept.emit(e); state.emit(e)
                    e.builder.endTouch()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && fn[1] == "keepAlive#") {
                CoreKeepAlive.validate(args.map(CoreRepresentations::expression), flags, tupleProof,
                    args.getOrNull(2)?.let { CoreRepresentations.knownFunctionSignature(it, bindings) })
                val kept = argument(args[0], scope, flags[0] as Boolean)
                val state = compile(args[1], scope, false)
                val function = argument(args[2], scope, true)
                fun emitKeepAlive(e: Emission, destination: List<BytecodeLocal>?) {
                    val b = e.builder
                    b.beginBlock()
                    val reference = b.createLocal("kept alive reference", "object")
                    val stateLocal = b.createLocal("keepAlive state", "object")
                    b.beginStoreLocal(reference); kept.emit(e); b.endStoreLocal()
                    b.beginStoreLocal(stateLocal); state.emit(e); b.endStoreLocal()
                    // The State# check precedes continuation forcing. A bytecode
                    // finally owns the fence even when that force yields or fails
                    // before the Java KeepAlive operation can be entered.
                    b.beginRequireIOState(); b.emitLoadLocal(stateLocal); b.endRequireIOState()
                    b.beginTryFinally(Runnable {
                        b.beginStoreLocal(b.createLocal("keepAlive fence", null))
                        b.beginTouch(); b.emitLoadLocal(reference); b.emitLoadLocal(stateLocal); b.endTouch()
                        b.endStoreLocal()
                    })
                    val result = if (destination == null) b.createLocal("keepAlive result", null) else null
                    if (result != null) b.beginStoreLocal(result)
                    if (resumable) {
                        val stateArgument = ProvenExpression(Expression { it.builder.emitLoadLocal(stateLocal) },
                            state.proof.copy(evaluated = true))
                        if (destination != null) {
                            checkpointedTupleApplication(e, TupleShape(tupleProof, language), function,
                                listOf(stateArgument), null, destination)
                        } else checkpointedApplication(e, function, listOf(stateArgument), booleanArrayOf(true), null, false)
                    } else {
                        if (destination != null) b.beginKeepAliveTuple(tupleSlots(TupleShape(tupleProof, language), destination), metrics)
                        else b.beginKeepAlive(metrics)
                        b.emitLoadLocal(reference); b.emitLoadLocal(stateLocal); force(function).emit(e)
                        if (destination != null) b.endKeepAliveTuple() else b.endKeepAlive()
                    }
                    if (result != null) b.endStoreLocal()
                    b.endTryFinally()
                    if (result != null) b.emitLoadLocal(result)
                    b.endBlock()
                }
                if (tupleProof.isAggregate) tupleExpression(tupleProof) { e, destination ->
                    emitKeepAlive(e, destination)
                } else ProvenExpression(Expression { e -> emitKeepAlive(e, null) },
                    tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && FloatingAddressOp.named(fn[1] as String) != null) {
                val operation = FloatingAddressOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { compile(it, scope, false) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    if (operation.floating) e.builder.beginReadFloatOffAddr(destination[0])
                    else e.builder.beginReadDoubleOffAddr(destination[0])
                    operands.forEach { it.emit(e) }
                    if (operation.floating) e.builder.endReadFloatOffAddr()
                    else e.builder.endReadDoubleOffAddr()
                } else ProvenExpression(Expression { e ->
                    if (operation.write) {
                        if (operation.floating) e.builder.beginWriteFloatOffAddr()
                        else e.builder.beginWriteDoubleOffAddr()
                    } else {
                        if (operation.floating) e.builder.beginIndexFloatOffAddr()
                        else e.builder.beginIndexDoubleOffAddr()
                    }
                    operands.forEach { it.emit(e) }
                    if (operation.write) {
                        if (operation.floating) e.builder.endWriteFloatOffAddr()
                        else e.builder.endWriteDoubleOffAddr()
                    } else {
                        if (operation.floating) e.builder.endIndexFloatOffAddr()
                        else e.builder.endIndexDoubleOffAddr()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && PinnedMemoryOp.named(fn[1] as String) != null) {
                val operation = PinnedMemoryOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { compile(it, scope, false) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        PinnedMemoryOp.NEW -> e.builder.beginNewPinnedByteArray(destination[0])
                        PinnedMemoryOp.NEW_ALIGNED -> e.builder.beginNewAlignedPinnedByteArray(destination[0])
                        PinnedMemoryOp.READ, PinnedMemoryOp.READ_CHAR -> e.builder.beginReadWord8OffAddr(destination[0])
                        PinnedMemoryOp.READ_INT8 -> e.builder.beginReadInt8OffAddr(destination[0])
                        PinnedMemoryOp.READ_ADDR -> e.builder.beginReadAddrOffAddr(destination[0])
                        PinnedMemoryOp.READ_ADDR_ARRAY -> e.builder.beginReadAddrArray(destination[0])
                        PinnedMemoryOp.READ_WORD16, PinnedMemoryOp.READ_INT16,
                        PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WIDE_CHAR, PinnedMemoryOp.READ_WORD,
                        PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT,
                        PinnedMemoryOp.READ_INT64, PinnedMemoryOp.READ_WORD64 ->
                            e.builder.beginReadManagedAddress(operation.addressRead!!, destination[0])
                        else -> error("Scalar pinned memory operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        PinnedMemoryOp.NEW -> e.builder.endNewPinnedByteArray()
                        PinnedMemoryOp.NEW_ALIGNED -> e.builder.endNewAlignedPinnedByteArray()
                        PinnedMemoryOp.READ, PinnedMemoryOp.READ_CHAR -> e.builder.endReadWord8OffAddr()
                        PinnedMemoryOp.READ_INT8 -> e.builder.endReadInt8OffAddr()
                        PinnedMemoryOp.READ_ADDR -> e.builder.endReadAddrOffAddr()
                        PinnedMemoryOp.READ_ADDR_ARRAY -> e.builder.endReadAddrArray()
                        PinnedMemoryOp.READ_WORD16, PinnedMemoryOp.READ_INT16,
                        PinnedMemoryOp.READ_WORD32, PinnedMemoryOp.READ_WIDE_CHAR, PinnedMemoryOp.READ_WORD,
                        PinnedMemoryOp.READ_INT32, PinnedMemoryOp.READ_INT,
                        PinnedMemoryOp.READ_INT64, PinnedMemoryOp.READ_WORD64 -> e.builder.endReadManagedAddress()
                        else -> error("Scalar pinned memory operation")
                    }
                } else ProvenExpression(Expression { e ->
                    when (operation) {
                        PinnedMemoryOp.CONTENTS, PinnedMemoryOp.MUTABLE_CONTENTS -> e.builder.beginByteArrayContents()
                        PinnedMemoryOp.WRITE_ADDR -> e.builder.beginAddressWrite()
                        PinnedMemoryOp.COPY_ADDR_NON_OVERLAPPING -> e.builder.beginAddressWrite()
                        PinnedMemoryOp.WRITE_ADDR_ARRAY -> e.builder.beginWriteAddrArray()
                        PinnedMemoryOp.WRITE_INT16, PinnedMemoryOp.WRITE_WORD16 -> e.builder.beginWriteWord16OffAddr()
                        PinnedMemoryOp.WRITE_INT32, PinnedMemoryOp.WRITE_WORD32,
                        PinnedMemoryOp.WRITE_WIDE_CHAR -> e.builder.beginWriteNativeScalarOffAddr(4)
                        PinnedMemoryOp.WRITE_INT, PinnedMemoryOp.WRITE_WORD,
                        PinnedMemoryOp.WRITE_INT64, PinnedMemoryOp.WRITE_WORD64 -> e.builder.beginWriteNativeScalarOffAddr(8)
                        PinnedMemoryOp.INDEX_ADDR_OFF -> e.builder.beginIndexAddrOffAddr()
                        PinnedMemoryOp.INDEX_ADDR_ARRAY -> e.builder.beginIndexAddrArray()
                        PinnedMemoryOp.INDEX_INT32, PinnedMemoryOp.INDEX_WORD32, PinnedMemoryOp.INDEX_WIDE_CHAR,
                        PinnedMemoryOp.INDEX_INT, PinnedMemoryOp.INDEX_WORD,
                        PinnedMemoryOp.INDEX_INT64, PinnedMemoryOp.INDEX_WORD64 ->
                            e.builder.beginIndexManagedAddress(operation.addressRead!!)
                        else -> e.builder.beginWriteWord8OffAddr()
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        PinnedMemoryOp.CONTENTS, PinnedMemoryOp.MUTABLE_CONTENTS -> e.builder.endByteArrayContents()
                        PinnedMemoryOp.WRITE_ADDR -> e.builder.endAddressWrite()
                        PinnedMemoryOp.COPY_ADDR_NON_OVERLAPPING -> e.builder.endAddressWrite()
                        PinnedMemoryOp.WRITE_ADDR_ARRAY -> e.builder.endWriteAddrArray()
                        PinnedMemoryOp.WRITE_INT16, PinnedMemoryOp.WRITE_WORD16 -> e.builder.endWriteWord16OffAddr()
                        PinnedMemoryOp.WRITE_INT32, PinnedMemoryOp.WRITE_WORD32, PinnedMemoryOp.WRITE_WIDE_CHAR,
                        PinnedMemoryOp.WRITE_INT, PinnedMemoryOp.WRITE_WORD,
                        PinnedMemoryOp.WRITE_INT64, PinnedMemoryOp.WRITE_WORD64 -> e.builder.endWriteNativeScalarOffAddr()
                        PinnedMemoryOp.INDEX_ADDR_OFF -> e.builder.endIndexAddrOffAddr()
                        PinnedMemoryOp.INDEX_ADDR_ARRAY -> e.builder.endIndexAddrArray()
                        PinnedMemoryOp.INDEX_INT32, PinnedMemoryOp.INDEX_WORD32, PinnedMemoryOp.INDEX_WIDE_CHAR,
                        PinnedMemoryOp.INDEX_INT, PinnedMemoryOp.INDEX_WORD,
                        PinnedMemoryOp.INDEX_INT64, PinnedMemoryOp.INDEX_WORD64 -> e.builder.endIndexManagedAddress()
                        else -> e.builder.endWriteWord8OffAddr()
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ByteArrayOp.named(fn[1] as String) != null) {
                val operation = ByteArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { compile(it, scope, false) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        ByteArrayOp.NEW -> e.builder.beginNewByteArray(destination[0])
                        ByteArrayOp.RESIZE -> e.builder.beginResizeByteArray(false, destination[0])
                        ByteArrayOp.GET_SIZE_MUTABLE -> e.builder.beginGetSizeMutableByteArray(destination[0])
                        ByteArrayOp.FREEZE -> e.builder.beginFreezeByteArray(destination[0])
                        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD,
                        ByteArrayOp.READ_INT64, ByteArrayOp.READ_WORD64,
                        ByteArrayOp.FETCH_ADD_INT -> e.builder.beginIntArrayAccess(
                            operation == ByteArrayOp.FETCH_ADD_INT, destination[0])
                        ByteArrayOp.READ_DOUBLE, ByteArrayOp.READ_WORD8_AS_DOUBLE ->
                            e.builder.beginReadDoubleArray(operation == ByteArrayOp.READ_WORD8_AS_DOUBLE, destination[0])
                        ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD8_AS_FLOAT ->
                            e.builder.beginReadFloatArray(operation == ByteArrayOp.READ_WORD8_AS_FLOAT, destination[0])
                        ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8, ByteArrayOp.READ_CHAR ->
                            e.builder.beginReadByteArray(operation != ByteArrayOp.READ_INT8, destination[0])
                        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16,
                        ByteArrayOp.READ_WORD8_AS_INT16, ByteArrayOp.READ_WORD8_AS_WORD16 ->
                            e.builder.beginReadInt16Array(
                                operation == ByteArrayOp.READ_WORD16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16,
                                operation == ByteArrayOp.READ_WORD8_AS_INT16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16,
                                destination[0])
                        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32,
                        ByteArrayOp.READ_WORD8_AS_INT32, ByteArrayOp.READ_WORD8_AS_WORD32 ->
                            e.builder.beginReadInt32Array(
                                operation == ByteArrayOp.READ_WORD32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32,
                                operation == ByteArrayOp.READ_WORD8_AS_INT32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32,
                                destination[0])
                        else -> error("Scalar ByteArray operation")
                    }
                    if (operation == ByteArrayOp.FETCH_ADD_INT) operands.forEach { it.emit(e) }
                    else if (operation in setOf(ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD,
                            ByteArrayOp.READ_INT64, ByteArrayOp.READ_WORD64)) {
                        operands[0].emit(e)
                        operands[1].emit(e)
                        e.builder.emitLoadConstant(0L)
                        operands[2].emit(e)
                    } else operands.forEach { it.emit(e) }
                    when (operation) {
                        ByteArrayOp.NEW -> e.builder.endNewByteArray()
                        ByteArrayOp.RESIZE -> e.builder.endResizeByteArray()
                        ByteArrayOp.GET_SIZE_MUTABLE -> e.builder.endGetSizeMutableByteArray()
                        ByteArrayOp.FREEZE -> e.builder.endFreezeByteArray()
                        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD,
                        ByteArrayOp.READ_INT64, ByteArrayOp.READ_WORD64,
                        ByteArrayOp.FETCH_ADD_INT -> e.builder.endIntArrayAccess()
                        ByteArrayOp.READ_DOUBLE, ByteArrayOp.READ_WORD8_AS_DOUBLE -> e.builder.endReadDoubleArray()
                        ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD8_AS_FLOAT -> e.builder.endReadFloatArray()
                        ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8, ByteArrayOp.READ_CHAR -> e.builder.endReadByteArray()
                        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16,
                        ByteArrayOp.READ_WORD8_AS_INT16, ByteArrayOp.READ_WORD8_AS_WORD16 -> e.builder.endReadInt16Array()
                        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32,
                        ByteArrayOp.READ_WORD8_AS_INT32, ByteArrayOp.READ_WORD8_AS_WORD32 -> e.builder.endReadInt32Array()
                        else -> error("Scalar ByteArray operation")
                    }
                } else ProvenExpression(Expression { e ->
                    when (operation) {
                        ByteArrayOp.COMPARE -> e.builder.beginCompareByteArrays()
                        ByteArrayOp.SHRINK -> {
                            e.builder.beginBlock()
                            e.builder.beginResizeByteArray(true, e.builder.createLocal("shrink has no result", null))
                        }
                        ByteArrayOp.COPY -> e.builder.beginCopyByteArray()
                        ByteArrayOp.SET -> e.builder.beginSetByteArray()
                        ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING ->
                            e.builder.beginCopyMutableByteArray(operation == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING)
                        ByteArrayOp.WRITE, ByteArrayOp.WRITE_INT8, ByteArrayOp.WRITE_CHAR -> e.builder.beginWriteByteArray()
                        ByteArrayOp.SIZE, ByteArrayOp.SIZE_MUTABLE -> e.builder.beginSizeByteArray()
                        ByteArrayOp.INDEX, ByteArrayOp.INDEX_CHAR -> e.builder.beginIndexByteArray()
                        ByteArrayOp.INDEX_INT8 -> e.builder.beginIndexSignedByteArray()
                        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD,
                        ByteArrayOp.WRITE_INT64, ByteArrayOp.WRITE_WORD64 -> e.builder.beginWriteIntArray()
                        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD,
                        ByteArrayOp.INDEX_INT64, ByteArrayOp.INDEX_WORD64 -> e.builder.beginIndexIntArray()
                        ByteArrayOp.WRITE_DOUBLE, ByteArrayOp.WRITE_WORD8_AS_DOUBLE ->
                            e.builder.beginWriteDoubleArray(operation == ByteArrayOp.WRITE_WORD8_AS_DOUBLE)
                        ByteArrayOp.INDEX_DOUBLE, ByteArrayOp.INDEX_WORD8_AS_DOUBLE ->
                            e.builder.beginIndexDoubleArray(operation == ByteArrayOp.INDEX_WORD8_AS_DOUBLE)
                        ByteArrayOp.WRITE_FLOAT, ByteArrayOp.WRITE_WORD8_AS_FLOAT ->
                            e.builder.beginWriteFloatArray(operation == ByteArrayOp.WRITE_WORD8_AS_FLOAT)
                        ByteArrayOp.INDEX_FLOAT, ByteArrayOp.INDEX_WORD8_AS_FLOAT ->
                            e.builder.beginIndexFloatArray(operation == ByteArrayOp.INDEX_WORD8_AS_FLOAT)
                        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16,
                        ByteArrayOp.WRITE_WORD8_AS_INT16, ByteArrayOp.WRITE_WORD8_AS_WORD16 ->
                            e.builder.beginWriteInt16Array(
                                operation == ByteArrayOp.WRITE_WORD8_AS_INT16 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD16)
                        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32,
                        ByteArrayOp.WRITE_WORD8_AS_INT32, ByteArrayOp.WRITE_WORD8_AS_WORD32 ->
                            e.builder.beginWriteInt32Array(
                                operation == ByteArrayOp.WRITE_WORD8_AS_INT32 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD32)
                        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16,
                        ByteArrayOp.INDEX_WORD8_AS_INT16, ByteArrayOp.INDEX_WORD8_AS_WORD16 ->
                            e.builder.beginIndexInt16Array(
                                operation == ByteArrayOp.INDEX_WORD16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16,
                                operation == ByteArrayOp.INDEX_WORD8_AS_INT16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16)
                        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32,
                        ByteArrayOp.INDEX_WORD8_AS_INT32, ByteArrayOp.INDEX_WORD8_AS_WORD32 ->
                            e.builder.beginIndexInt32Array(
                                operation == ByteArrayOp.INDEX_WORD32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32,
                                operation == ByteArrayOp.INDEX_WORD8_AS_INT32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32)
                        else -> error("Tuple ByteArray operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ByteArrayOp.COMPARE -> e.builder.endCompareByteArrays()
                        ByteArrayOp.SHRINK -> {
                            e.builder.endResizeByteArray()
                            e.builder.emitLoadConstant(Unit)
                            e.builder.endBlock()
                        }
                        ByteArrayOp.COPY -> e.builder.endCopyByteArray()
                        ByteArrayOp.SET -> e.builder.endSetByteArray()
                        ByteArrayOp.COPY_MUTABLE, ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING -> e.builder.endCopyMutableByteArray()
                        ByteArrayOp.WRITE, ByteArrayOp.WRITE_INT8, ByteArrayOp.WRITE_CHAR -> e.builder.endWriteByteArray()
                        ByteArrayOp.SIZE, ByteArrayOp.SIZE_MUTABLE -> e.builder.endSizeByteArray()
                        ByteArrayOp.INDEX, ByteArrayOp.INDEX_CHAR -> e.builder.endIndexByteArray()
                        ByteArrayOp.INDEX_INT8 -> e.builder.endIndexSignedByteArray()
                        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD,
                        ByteArrayOp.WRITE_INT64, ByteArrayOp.WRITE_WORD64 -> e.builder.endWriteIntArray()
                        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD,
                        ByteArrayOp.INDEX_INT64, ByteArrayOp.INDEX_WORD64 -> e.builder.endIndexIntArray()
                        ByteArrayOp.WRITE_DOUBLE, ByteArrayOp.WRITE_WORD8_AS_DOUBLE -> e.builder.endWriteDoubleArray()
                        ByteArrayOp.INDEX_DOUBLE, ByteArrayOp.INDEX_WORD8_AS_DOUBLE -> e.builder.endIndexDoubleArray()
                        ByteArrayOp.WRITE_FLOAT, ByteArrayOp.WRITE_WORD8_AS_FLOAT -> e.builder.endWriteFloatArray()
                        ByteArrayOp.INDEX_FLOAT, ByteArrayOp.INDEX_WORD8_AS_FLOAT -> e.builder.endIndexFloatArray()

                        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16,
                        ByteArrayOp.WRITE_WORD8_AS_INT16, ByteArrayOp.WRITE_WORD8_AS_WORD16 -> e.builder.endWriteInt16Array()
                        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32,
                        ByteArrayOp.WRITE_WORD8_AS_INT32, ByteArrayOp.WRITE_WORD8_AS_WORD32 -> e.builder.endWriteInt32Array()
                        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16,
                        ByteArrayOp.INDEX_WORD8_AS_INT16, ByteArrayOp.INDEX_WORD8_AS_WORD16 -> e.builder.endIndexInt16Array()
                        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32,
                        ByteArrayOp.INDEX_WORD8_AS_INT32, ByteArrayOp.INDEX_WORD8_AS_WORD32 -> e.builder.endIndexInt32Array()
                        else -> error("Tuple ByteArray operation")
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (tupleOperation != null) {
                tupleOperation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { argument(it, scope, false) }
                tupleExpression(tupleProof) { e, destination ->
                    // The fixed-arity operation ignores its third accessor for pairs.
                    e.builder.beginTupleArithmetic(tupleOperation, destination[0], destination[1],
                        destination.getOrElse(2) { destination[1] })
                    operands.forEach { it.emit(e) }
                    e.builder.endTupleArithmetic()
                }
            } else if (tupleProof.isSum && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-sum") {
                val tag = SumShape.constructor(tupleProof, constructors[fn[1]], fn[2])
                if (args.size != 1) throw RuntimeFault("Sum constructor must be saturated")
                val selected = tupleProof.alternatives!![tag - 1]
                val lifted = flags.single() as? Boolean ?: throw UnsupportedCore("Unknown sum payload levity")
                val payload = if (selected.isTuple) compile(args.single(), scope, false) else argument(args.single(), scope, lifted)
                SumShape.payload(selected, payload.proof, lifted)
                val shape = TupleShape(tupleProof, language)
                tupleExpression(tupleProof) { e, destination ->
                    val b = e.builder
                    b.beginBlock()
                    shape.leaves.forEachIndexed { index, field ->
                        b.beginStoreLocal(destination[index])
                        if (field.isLong) b.emitLoadConstant(0L) else if (field.isFloat) b.emitLoadConstant(0.0f)
                        else if (field.isDouble) b.emitLoadConstant(0.0) else b.emitLoadNull()
                        b.endStoreLocal()
                    }
                    val mapped = tupleProof.alternativeSlots!![tag - 1].map { destination[it] }
                    if (selected.isTuple) payload.emitTuple(e, mapped)
                    else if (selected.kind == CoreKind.VOID) { b.beginDiscardVoid(); payload.emit(e); b.endDiscardVoid() }
                    else { b.beginStoreLocal(mapped.single()); payload.emit(e); b.endStoreLocal() }
                    b.beginStoreLocal(destination[0]); b.emitLoadConstant(tag.toLong()); b.endStoreLocal()
                    b.endBlock()
                }
            } else if (tupleProof.isTuple && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-tuple") {
                val shape = TupleShape(tupleProof, language)
                if (shape.components.size != args.size || (fn[2] as Number).toInt() != args.size ||
                    (constructors[fn[1]]?.get("arity") as? Number)?.toInt() != args.size) throw RuntimeFault("Tuple constructor arity mismatch")
                val operands = args.mapIndexed { index, arg ->
                    TupleShape.requireCompatible(shape.components[index], CoreRepresentations.expression(arg), component = true)
                    if (shape.components[index].isTuple) compile(arg, scope, false)
                    else argument(arg, scope, flags[index] as? Boolean ?: throw UnsupportedCore("Unknown tuple field levity"))
                }
                tupleExpression(tupleProof) { e, destination ->
                    e.builder.beginBlock()
                    operands.forEachIndexed { index, operand ->
                        val component = shape.components[index]
                        val offset = shape.offsets[index]
                        if (component.isTuple) operand.emitTuple(e, destination.subList(offset, offset + TupleShape.flatten(component).size))
                        else if (component.kind == CoreKind.VOID) {
                            e.builder.beginDiscardVoid(); operand.emit(e); e.builder.endDiscardVoid()
                        }
                        else {
                            e.builder.beginStoreLocal(destination[offset])
                            if (component.kind == CoreKind.ADDRESS) e.builder.beginRequireAddress()
                            operand.emit(e)
                            if (component.kind == CoreKind.ADDRESS) e.builder.endRequireAddress()
                            e.builder.endStoreLocal()
                        }
                    }
                    e.builder.endBlock()
                }
            } else {
            CoreRepresentations.requireNoVector(tupleProof, "call result")
            val strict = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size) strictConstructorFields(fn[1] as String, args.size) else null
            val entryStrict = when (fn[0]) {
                "lam" -> CoreEntries.lambda(fn)
                "var" -> (fn[1] as String).let { id ->
                    scope.joins[id]?.entryStrict ?: if (id in scope.locals) scope.locals.getValue(id).entry else globalEntries[id]
                }
                else -> null
            }?.takeIf { args.size >= it.size }
            val operands = args.mapIndexed { index, arg ->
                val lifted = flags[index] as? Boolean ?: throw UnsupportedCore("Unknown argument levity")
                argument(arg, scope, lifted && !callStrict[index] && strict?.get(index) != true && entryStrict?.getOrNull(index) != true,
                    allowEmpty = fn[0] != "prim" && fn[0] != "con", declaredLifted = lifted)
            }
            when {
                fn[0] == "var" && fn[1] in scope.joins -> joinCall(scope.joins.getValue(fn[1] as String), operands)
                fn[0] == "prim" -> {
                    ScalarPrimitiveSignatures.validate(fn[1] as String, operands.map { it.proof }, tupleProof)
                    val value = primitive(fn[1] as String, operands)
                    if (fn[1] == "raise#" && tupleProof.isAggregate) tupleExpression(tupleProof) { e, _ ->
                        val b = e.builder
                        b.beginBlock()
                        b.beginStoreLocal(b.createLocal("non-returning aggregate", null)); value.emit(e); b.endStoreLocal()
                        b.endBlock()
                    } else value
                }
                strict != null -> construct(dataLayout(fn[1] as String), operands)
                else -> if (tupleProof.isAggregate) tupleApplication(TupleShape(tupleProof, language), compile(fn, scope, false), operands, scope, tail)
                    else application(compile(fn, scope, false), operands, scope, tail)
            }
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            if (group.any { CoreRepresentations.joinArity(it) != null }) {
                joinRegion(group, expr[3] as List<Any?>, recursive, scope, tail)
            } else {
                group.forEach { CoreRepresentations.requireScalar(CoreRepresentations.binder(it), "let binding") }
                val local = scope.child()
                val slots = group.map { bind(local, it["id"] as String, !representation(it),
                    CoreRepresentations.binder(it).copy(evaluated = false), cell = recursive, entry = CoreEntries.binding(it),
                    arityCertificate = CoreApplicationCertificates.binding(it)) }
                val rhs = group.map {
                    val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
                    CoreRepresentations.requireNoSum(CoreRepresentations.expression(rhsExpr), "let binding")
                    if (recursive && !lifted) throw UnsupportedCore("Recursive unlifted binding unsupported")
                    val rhsScope = (if (recursive) local else scope).withSource(sources.binding(it, scope.source))
                    if (recursive && lifted && rhsExpr[0] !in listOf("lam", "lit", "con", "void")) delay(rhsExpr, rhsScope, it["name"].toString())
                    else argument(rhsExpr, rhsScope, lifted, it["name"].toString())
                }
                slots.forEachIndexed { index, slot ->
                    val proof = slot.proof.refine(rhs[index].proof).copy(evaluated = rhs[index].proof.evaluated)
                    // All RHS roots have already captured immutable Local records
                    // with cell=true. Only body/new captures see published values.
                    local.locals[slot.name] = slot.copy(proof = proof, cell = false,
                        primitive = if (proof.present) proof.isLong else slot.primitive)
                }
                val body = compile(expr[3] as List<Any?>, local, tail)
                ProvenExpression(ResultExpression { e, destination ->
                    val b = e.builder
                    b.beginBlock()
                    slots.forEach { e.locals[it.id] = b.createLocal(it.name, if (it.primitive) "primitive" else "object") }
                    if (recursive) {
                        slots.forEach { b.beginStoreLocal(e.locals.getValue(it.id)); b.emitNewCell(); b.endStoreLocal() }
                        slots.forEachIndexed { index, slot ->
                            b.beginInitializeCell(); read(slot, false).emit(e); rhs[index].emit(e); b.endInitializeCell()
                        }
                        // Every RHS has captured the group before publication removes its indirections.
                        slots.forEach { b.beginStoreLocal(e.locals.getValue(it.id)); read(it).emit(e); b.endStoreLocal() }
                    } else slots.forEachIndexed { index, slot ->
                        b.beginStoreLocal(e.locals.getValue(slot.id)); rhs[index].emit(e); b.endStoreLocal()
                    }
                    emitResult(body, e, destination)
                    b.endBlock()
                    slots.forEach { e.locals.remove(it.id) }
                }, body.proof)
            }
        }
        "case" -> {
            CoreVectorMemory.readCase(expr, constructors)?.let { vectorReadCase(it, scope, tail) } ?: run {
            val local = scope.child()
            val scrutineeExpr = expr[1] as List<Any?>
            val scrutinee = force(compile(scrutineeExpr, scope, false))
            val binderProof = scrutinee.proof.refine(CoreRepresentations.caseBinder(expr)).copy(evaluated = true)
            if (binderProof.isSum) sumCase(expr, scrutinee, binderProof, local, tail)
            else if (binderProof.isTuple) tupleCase(expr, scrutinee, binderProof, local, tail) else {
            val binder = bind(local, expr[2] as String, true, binderProof)
            if (scrutineeExpr[0] == "var" && scrutineeExpr[1] != expr[2]) {
                val id = scrutineeExpr[1] as String
                scope.locals[id]?.let { local.locals[id] = it.copy(proof = it.proof.copy(evaluated = true)) }
            }
            data class Alternative(val kind: String, val value: Any?, val fields: List<Local>, val body: Expression)
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let {
                        if (it[0] == "bignat") throw UnsupportedCore("BigNat literal alternatives are invalid GHC Core")
                        if (it[0] in setOf("float", "double")) throw UnsupportedCore("Floating literal alternatives are invalid GHC Core")
                        literal(it[0], it[1])
                    }
                    "data" -> dataLayout(alt[1] as String)
                    "default" -> alt[1]
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                val ids = alt[2] as List<String>; val layout = value as? DataLayout
                if (layout != null && layout.arity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val metadata = CoreRepresentations.alternativeBinders(alt)
                val fields = ids.mapIndexed { index, id -> bind(child, id, layout?.isLong(index) == true,
                    (metadata.getOrNull(index)?.let { CoreRepresentations.binder(it) } ?: CoreRepresentation.UNKNOWN)
                        .let { it.copy(evaluated = layout != null && fieldIsEvaluated(alt[1] as String, index)) }) }
                Alternative(kind, value, fields, compile(alt[3] as List<Any?>, child, tail))
            }
            val explicit = alternatives.filter { it.kind != "default" }
            val fallback = alternatives.lastOrNull { it.kind == "default" }
            val category = caseCategory(binderProof, alternatives.map { when (it.kind) {
                "default" -> 0; "data" -> 1; else -> 2
            } }, alternatives.all { it.kind != "lit" || it.value is Long })
            val resultProof = CoreRepresentations.expression(expr)
            CoreRepresentations.validateAggregateCaseResult(resultProof, alternatives.map { it.body.proof })
            CoreRepresentations.validateFloatingCaseResult(resultProof, alternatives.map { it.body.proof })
            // A missing outer case record must not erase an exact aggregate
            // writer. Infer only when every arm supplies its own checked shape.
            val effectiveResult = if (!resultProof.isAggregate && alternatives.any { it.body.proof.isAggregate }) {
                if (!alternatives.all { it.body.proof.isAggregate })
                    throw UnsupportedCore("Missing exact aggregate case result proof")
                alternatives.first().body.proof.refine(resultProof)
            } else resultProof
            val mergedProof = CoreVectors.caseResult(alternatives.map { it.body.proof })?.refine(effectiveResult)
                ?: effectiveResult.copy(evaluated = alternatives.all { it.body.proof.evaluated })
            LoweredCaseExpression(ProvenExpression(ResultExpression { e, destination ->
                val b = e.builder
                b.beginBlock()
                e.locals[binder.id] = b.createLocal(binder.name, null)
                if (category == CaseCategory.GENERIC) {
                    b.beginStoreLocal(e.locals.getValue(binder.id)); scrutinee.emit(e); b.endStoreLocal()
                } else restoreArgument(e, binder) { scrutinee.emit(e) }
                fun emitAlternative(alt: Alternative) {
                    b.beginBlock()
                    alt.fields.forEachIndexed { index, field ->
                        e.locals[field.id] = b.createLocal(field.name, if (field.primitive) "primitive" else "object")
                        b.beginStoreLocal(e.locals.getValue(field.id))
                        b.beginReadDataField(alt.value as DataLayout, index)
                        read(binder, false).emit(e)
                        b.endReadDataField()
                        b.endStoreLocal()
                    }
                    emitResult(alt.body, e, destination)
                    b.endBlock()
                    alt.fields.forEach { e.locals.remove(it.id) }
                }
                fun emitChoice(index: Int) {
                    if (index == explicit.size) {
                        if (fallback == null) b.emitFailCase() else emitAlternative(fallback)
                        return
                    }
                    val alt = explicit[index]
                    if (destination == null) b.beginConditional() else b.beginIfThenElse()
                    when {
                        category == CaseCategory.DATA -> b.beginMatchDataValue(alt.value as DataLayout)
                        alt.kind == "data" -> b.beginMatchData(alt.value as DataLayout)
                        else -> b.beginMatchLiteral(alt.value!!)
                    }
                    read(binder, false).emit(e)
                    when {
                        category == CaseCategory.DATA -> b.endMatchDataValue()
                        alt.kind == "data" -> b.endMatchData()
                        else -> b.endMatchLiteral()
                    }
                    emitAlternative(alt)
                    emitChoice(index + 1)
                    if (destination == null) b.endConditional() else b.endIfThenElse()
                }
                emitChoice(0)
                b.endBlock()
                e.locals.remove(binder.id)
            }, mergedProof))
            }
            }
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
            if (constructors[id]?.get("kind") == "unboxed-tuple" && arity == 0 && CoreRepresentations.expression(expr).isTuple) {
                val proof = CoreRepresentations.expression(expr)
                if (proof.components?.size != 0) throw RuntimeFault("Empty tuple constructor has nonempty logical components")
                tupleExpression(proof) { e, _ -> e.builder.beginBlock(); e.builder.endBlock() }
            } else {
            val strict = strictConstructorFields(id, arity)
            val layout = dataLayout(id)
            if (arity == 0) construct(layout, emptyList()) else {
                val context = FunctionContext(arity)
                val constructorScope = Scope(context)
                val args = List(arity) { bind(constructorScope, "field$it", layout.isLong(it)) }
                context.arguments = args
                val body = construct(layout, args.mapIndexed { index, arg -> if (strict[index]) force(read(arg)) else read(arg) })
                closure(FunctionSpec(build("constructor $id", context, body), null, emptyList()), arity)
            }
            }
        }
        "prim" -> throw UnsupportedCore("Unsaturated primitive ${expr[1]}")
        else -> throw UnsupportedCore("Unsupported Core node ${expr[0]}")
    }

    private fun tupleApplication(shape: TupleShape, function: Expression, arguments: List<Expression>, scope: Scope, tail: Boolean): Expression {
        val inputLayout = ArgumentLayout.fromProofs(arguments.map { it.proof })
        val context = scope.function
        if (tail) context.mayLoop = true
        return tupleExpression(shape.proof) { e, destination ->
            val b = e.builder
            if (!tail) {
                if (resumable) {
                    checkpointedTupleApplication(e, shape, function, arguments, inputLayout, destination)
                } else if (inputLayout?.requiresTyped == true) {
                    b.beginStoreLocal(b.createLocal("typed tuple call", null))
                    typedArguments(e, function, arguments, inputLayout, false, tupleSlots(shape, destination))
                    b.endStoreLocal()
                } else if (inputLayout == null) {
                    b.beginApplyTuple(tupleSlots(shape, destination), arguments.size, metrics)
                    requireClosure(function).emit(e); arguments.forEach { it.emit(e) }; b.endApplyTuple()
                } else compactArguments(e, function, arguments, inputLayout) { fn, values ->
                    b.beginApplyCompactTuple(tupleSlots(shape, destination), inputLayout, metrics)
                    b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal); b.endApplyCompactTuple()
                }
            } else {
                b.beginBlock()
                val result = b.createLocal("tuple tail result", null)
                b.beginStoreLocal(result)
                if (resumable) {
                    checkpointedTupleApplication(e, shape, function, arguments, inputLayout, destination, true)
                } else if (inputLayout?.requiresTyped == true) {
                    typedArguments(e, function, arguments, inputLayout, true, tupleSlots(shape, destination))
                } else if (inputLayout == null) {
                    b.beginTailApplyTuple(tupleSlots(shape, destination), arguments.size, metrics)
                    requireClosure(function).emit(e); arguments.forEach { it.emit(e) }; b.endTailApplyTuple()
                } else compactArguments(e, function, arguments, inputLayout) { fn, values ->
                    b.beginTailApplyCompactTuple(tupleSlots(shape, destination), inputLayout, metrics)
                    b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal); b.endTailApplyCompactTuple()
                }
                b.endStoreLocal()
                if (resumable) {
                    b.beginIfThen()
                    b.beginIsTailReentry(); b.emitLoadLocal(result); b.emitLoadConstant(true); b.endIsTailReentry()
                    b.beginBlock()
                    b.beginReturn(); b.emitLoadLocal(result); b.endReturn()
                    b.endBlock()
                    b.endIfThen()
                }
                b.beginIfThenElse()
                b.beginIsTailReentry(); b.emitLoadLocal(result); b.emitLoadConstant(false); b.endIsTailReentry()
                b.beginBlock()
                restoreTailArguments(e, context, result)
                b.emitBranch(e.continueLabel!!)
                b.endBlock()
                b.beginBlock(); b.endBlock()
                b.endIfThenElse()
                b.endBlock()
            }
        }
    }

    /** The final exact tuple callee owns the destination; earlier stages return scalar closures. */
    private fun checkpointedTupleCall(e: Emission, slots: BytecodeTupleSlots,
                                      fn: BytecodeLocal, values: List<BytecodeLocal>,
                                      inputLayout: ArgumentLayout?, arity: Int,
                                      callerMask: BytecodeLocal) {
        val b = e.builder
        val suspended = b.createLocal("captured tuple suspension", "object")
        b.beginTryCatch()
        if (inputLayout?.requiresTyped == true) {
            val source = BytecodeInputSource(inputLayout, values.map(LocalAccessor::constantOf).toTypedArray())
            b.beginStoreLocal(b.createLocal("typed tuple completion", "object"))
            b.beginApplyTypedInputTuple(source, slots, false, metrics)
            b.emitLoadLocal(fn)
            b.endApplyTypedInputTuple()
            b.endStoreLocal()
        } else if (inputLayout == null) {
            b.beginApplyTupleCheckpoint(slots, arity, metrics)
            b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
            b.endApplyTupleCheckpoint()
        } else {
            b.beginApplyCompactTupleCheckpoint(slots, inputLayout, metrics)
            b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
            b.endApplyCompactTupleCheckpoint()
        }
        b.beginBlock()
        b.beginStoreLocal(suspended)
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly()
        b.endStoreLocal()
        b.beginResumeTupleApplication(slots)
        b.emitLoadLocal(suspended)
        b.beginReenterCallMask()
        b.beginYield()
        b.beginParkCallMask()
        b.emitLoadLocal(suspended)
        b.emitLoadLocal(checkNotNull(e.checkpointRootEntry))
        b.emitLoadLocal(callerMask)
        b.endParkCallMask()
        b.endYield()
        b.emitLoadLocal(callerMask)
        b.endReenterCallMask()
        b.endResumeTupleApplication()
        b.endBlock()
        b.endTryCatch()
    }

    /** The exact tuple tail writes its destination or forwards a trusted callee Yield. */
    private fun savedTailTuple(e: Emission, slots: BytecodeTupleSlots,
                               fn: BytecodeLocal, values: List<BytecodeLocal>,
                               inputLayout: ArgumentLayout?, arity: Int) {
        val b = e.builder
        if (inputLayout?.requiresTyped == true) {
            val source = BytecodeInputSource(inputLayout, values.map(LocalAccessor::constantOf).toTypedArray())
            b.beginApplyTypedInputTuple(source, slots, true, metrics)
            b.emitLoadLocal(fn)
            b.endApplyTypedInputTuple()
        } else if (inputLayout == null) {
            b.beginTailApplyTuple(slots, arity, metrics)
            b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
            b.endTailApplyTuple()
        } else {
            b.beginTailApplyCompactTuple(slots, inputLayout, metrics)
            b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal)
            b.endTailApplyCompactTuple()
        }
    }

    /** Saved logical and physical arguments survive every saturated prefix call. */
    private fun checkpointedTupleApplication(e: Emission, shape: TupleShape, function: Expression,
                                             arguments: List<Expression>, inputLayout: ArgumentLayout?,
                                             destination: List<BytecodeLocal>, tail: Boolean = false) {
        val b = e.builder
        val slots = tupleSlots(shape, destination, capturesYield = true)
        b.beginBlock()
        val fn = b.createLocal("captured tuple function", "object")
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal()
        val values = if (inputLayout?.requiresTyped == true) {
            List(inputLayout.physicalArity) { b.createLocal("captured typed tuple input $it", null) }.also { fields ->
                arguments.forEachIndexed { index, argument ->
                    val offset = inputLayout.offset(index)
                    if (inputLayout.isTuple(index)) argument.emitTuple(e, fields.subList(offset, inputLayout.offset(index + 1)))
                    else { b.beginStoreLocal(fields[offset]); argument.emit(e); b.endStoreLocal() }
                }
            }
        } else arrayListOf<BytecodeLocal>().also { fields ->
            arguments.forEachIndexed { index, argument ->
                if (inputLayout?.isEmpty(index) == true) argument.emitTuple(e, emptyList())
                else fields += b.createLocal("captured tuple operand $index", null).also { local ->
                    b.beginStoreLocal(local); argument.emit(e); b.endStoreLocal()
                }
            }
        }
        val callerMask = b.createLocal("captured tuple caller mask", "object")
        b.beginStoreLocal(callerMask); b.emitCurrentMask(); b.endStoreLocal()
        val tailResult = if (tail) b.createLocal("captured tuple tail result", "object") else null
        fun finish(suffix: List<BytecodeLocal>, layout: ArgumentLayout?, arity: Int) {
            if (tail) {
                b.beginStoreLocal(checkNotNull(tailResult))
                savedTailTuple(e, slots, fn, suffix, layout, arity)
                b.endStoreLocal()
            } else checkpointedTupleCall(e, slots, fn, suffix, layout, arity, callerMask)
        }
        if (arguments.isEmpty()) finish(values, inputLayout, 0)
        else {
            b.beginIfThenElse()
            b.beginMatchLiteral(1L)
            b.beginLessThan()
            b.beginClosureArity(); b.emitLoadLocal(fn); b.endClosureArity()
            b.emitLoadConstant(arguments.size.toLong())
            b.endLessThan()
            b.endMatchLiteral()
            b.beginBlock()
            val result = b.createLocal("tuple prefix result", "object")
            stagedOverapplication(e, fn, values, inputLayout,
                arguments.map { it.proof.evaluated }.toBooleanArray(), callerMask, result, arguments.size, false) {
                    suffix, layout, arity -> finish(suffix, layout, arity)
                }
            b.endBlock()
            b.beginBlock()
            finish(values, inputLayout, arguments.size)
            b.endBlock()
            b.endIfThenElse()
        }
        if (tail) b.emitLoadLocal(checkNotNull(tailResult))
        b.endBlock()
    }

    private fun vectorPrimitive(name: String, operands: List<Expression>): Expression = when (name) {
        in GeneratedVectors.operations -> generatedVectorPrimitive(name, operands)
        in CoreVectors.operationsWord32 -> vectorWord32Primitive(name, operands)
        in CoreVectors.operationsWord16 -> vectorWord16Primitive(name, operands)
        in CoreVectors.operationsWord8 -> vectorWord8Primitive(name, operands)
        in CoreVectors.operations8 -> vector8Primitive(name, operands)
        in CoreVectors.operations16 -> vector16Primitive(name, operands)
        in CoreVectors.operationsDouble -> vectorDoublePrimitive(name, operands)
        in CoreVectors.operationsFloat -> vectorFloatPrimitive(name, operands)
        in CoreVectors.operations32 -> vector32Primitive(name, operands)
        "unpackInt64X2#" -> tupleExpression(CoreVectors.unpacked) { e, destination ->
            e.builder.beginVectorUnpack(destination[0], destination[1])
            operands[0].emit(e)
            e.builder.endVectorUnpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packInt64X2#" -> {
                    b.beginBlock()
                    val lanes = List(2) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorPack(); lanes.forEach(b::emitLoadLocal); b.endVectorPack()
                    b.endBlock()
                }
                "broadcastInt64X2#" -> { b.beginVectorBroadcast(); operands[0].emit(e); b.endVectorBroadcast() }
                "negateInt64X2#" -> { b.beginVectorNegate(); operands[0].emit(e); b.endVectorNegate() }
                else -> {
                    b.beginVectorBinary(name == "minusInt64X2#")
                    operands.forEach { it.emit(e) }; b.endVectorBinary()
                }
            }
        }, CoreVectors.proof)
    }

    private fun vectorWord8Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackWord8X16#" -> tupleExpression(CoreVectors.unpackedWord8) { e, destination ->
            e.builder.beginVectorWord8Unpack(destination[0], destination[1], destination[2], destination[3],
                destination[4], destination[5], destination[6], destination[7],
                destination[8], destination[9], destination[10], destination[11],
                destination[12], destination[13], destination[14], destination[15])
            operands[0].emit(e)
            e.builder.endVectorWord8Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packWord8X16#" -> {
                    b.beginBlock()
                    val lanes = List(16) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorWord8Pack(); lanes.forEach(b::emitLoadLocal); b.endVectorWord8Pack()
                    b.endBlock()
                }
                "broadcastWord8X16#" -> { b.beginVectorWord8Broadcast(); operands[0].emit(e); b.endVectorWord8Broadcast() }
                else -> {
                    val operation = when (name) { "plusWord8X16#" -> 0; "minusWord8X16#" -> 1; "timesWord8X16#" -> 2; else -> error("Invalid Word8X16 operation") }
                    b.beginVectorWord8Binary(operation)
                    operands.forEach { it.emit(e) }; b.endVectorWord8Binary()
                }
            }
        }, CoreVectors.proofWord8)
    }

    private fun vector8Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackInt8X16#" -> tupleExpression(CoreVectors.unpacked8) { e, destination ->
            e.builder.beginVector8Unpack(destination[0], destination[1], destination[2], destination[3],
                destination[4], destination[5], destination[6], destination[7],
                destination[8], destination[9], destination[10], destination[11],
                destination[12], destination[13], destination[14], destination[15])
            operands[0].emit(e)
            e.builder.endVector8Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packInt8X16#" -> {
                    b.beginBlock()
                    val lanes = List(16) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVector8Pack(); lanes.forEach(b::emitLoadLocal); b.endVector8Pack()
                    b.endBlock()
                }
                "broadcastInt8X16#" -> { b.beginVector8Broadcast(); operands[0].emit(e); b.endVector8Broadcast() }
                "negateInt8X16#" -> { b.beginVector8Negate(); operands[0].emit(e); b.endVector8Negate() }
                else -> {
                    val operation = when (name) { "plusInt8X16#" -> 0; "minusInt8X16#" -> 1; "timesInt8X16#" -> 2; else -> error("Invalid Int8X16 operation") }
                    b.beginVector8Binary(operation)
                    operands.forEach { it.emit(e) }; b.endVector8Binary()
                }
            }
        }, CoreVectors.proof8)
    }

    private fun vectorWord32Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackWord32X4#" -> tupleExpression(CoreVectors.unpackedWord32) { e, destination ->
            e.builder.beginVectorWord32Unpack(destination[0], destination[1], destination[2], destination[3])
            operands[0].emit(e)
            e.builder.endVectorWord32Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packWord32X4#" -> {
                    b.beginBlock()
                    val lanes = List(4) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorWord32Pack(); lanes.forEach(b::emitLoadLocal); b.endVectorWord32Pack()
                    b.endBlock()
                }
                "broadcastWord32X4#" -> { b.beginVectorWord32Broadcast(); operands[0].emit(e); b.endVectorWord32Broadcast() }
                else -> {
                    val operation = when (name) { "plusWord32X4#" -> 0; "minusWord32X4#" -> 1; "timesWord32X4#" -> 2; else -> error("Invalid Word32X4 operation") }
                    b.beginVectorWord32Binary(operation)
                    operands.forEach { it.emit(e) }; b.endVectorWord32Binary()
                }
            }
        }, CoreVectors.proofWord32)
    }

    private fun vectorWord16Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackWord16X8#" -> tupleExpression(CoreVectors.unpackedWord16) { e, destination ->
            e.builder.beginVectorWord16Unpack(destination[0], destination[1], destination[2], destination[3],
                destination[4], destination[5], destination[6], destination[7])
            operands[0].emit(e)
            e.builder.endVectorWord16Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packWord16X8#" -> {
                    b.beginBlock()
                    val lanes = List(8) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorWord16Pack(); lanes.forEach(b::emitLoadLocal); b.endVectorWord16Pack()
                    b.endBlock()
                }
                "broadcastWord16X8#" -> { b.beginVectorWord16Broadcast(); operands[0].emit(e); b.endVectorWord16Broadcast() }
                else -> {
                    val operation = when (name) { "plusWord16X8#" -> 0; "minusWord16X8#" -> 1; "timesWord16X8#" -> 2; else -> error("Invalid Word16X8 operation") }
                    b.beginVectorWord16Binary(operation)
                    operands.forEach { it.emit(e) }; b.endVectorWord16Binary()
                }
            }
        }, CoreVectors.proofWord16)
    }

    private fun vector16Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackInt16X8#" -> tupleExpression(CoreVectors.unpacked16) { e, destination ->
            e.builder.beginVector16Unpack(destination[0], destination[1], destination[2], destination[3],
                destination[4], destination[5], destination[6], destination[7])
            operands[0].emit(e)
            e.builder.endVector16Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packInt16X8#" -> {
                    b.beginBlock()
                    val lanes = List(8) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVector16Pack(); lanes.forEach(b::emitLoadLocal); b.endVector16Pack()
                    b.endBlock()
                }
                "broadcastInt16X8#" -> { b.beginVector16Broadcast(); operands[0].emit(e); b.endVector16Broadcast() }
                "negateInt16X8#" -> { b.beginVector16Negate(); operands[0].emit(e); b.endVector16Negate() }
                else -> {
                    val operation = when (name) { "plusInt16X8#" -> 0; "minusInt16X8#" -> 1; "timesInt16X8#" -> 2; else -> error("Invalid Int16X8 operation") }
                    b.beginVector16Binary(operation)
                    operands.forEach { it.emit(e) }; b.endVector16Binary()
                }
            }
        }, CoreVectors.proof16)
    }

    private fun vector32Primitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackInt32X4#" -> tupleExpression(CoreVectors.unpacked32) { e, destination ->
            e.builder.beginVector32Unpack(destination[0], destination[1], destination[2], destination[3])
            operands[0].emit(e)
            e.builder.endVector32Unpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packInt32X4#" -> {
                    b.beginBlock()
                    val lanes = List(4) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVector32Pack(); lanes.forEach(b::emitLoadLocal); b.endVector32Pack()
                    b.endBlock()
                }
                "broadcastInt32X4#" -> { b.beginVector32Broadcast(); operands[0].emit(e); b.endVector32Broadcast() }
                "negateInt32X4#" -> { b.beginVector32Negate(); operands[0].emit(e); b.endVector32Negate() }
                "timesInt32X4#" -> {
                    b.beginVector32Multiply(); operands.forEach { it.emit(e) }; b.endVector32Multiply()
                }
                else -> {
                    b.beginVector32Binary(name == "minusInt32X4#")
                    operands.forEach { it.emit(e) }; b.endVector32Binary()
                }
            }
        }, CoreVectors.proof32)
    }

    private fun vectorFloatPrimitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackFloatX4#" -> tupleExpression(CoreVectors.unpackedFloat) { e, destination ->
            e.builder.beginVectorFloatUnpack(destination[0], destination[1], destination[2], destination[3])
            operands[0].emit(e)
            e.builder.endVectorFloatUnpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packFloatX4#" -> {
                    b.beginBlock()
                    val lanes = List(4) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorFloatPack(); lanes.forEach(b::emitLoadLocal); b.endVectorFloatPack()
                    b.endBlock()
                }
                "broadcastFloatX4#" -> { b.beginVectorFloatBroadcast(); operands[0].emit(e); b.endVectorFloatBroadcast() }
                else -> {
                    val operation = when (name) { "plusFloatX4#" -> 0; "minusFloatX4#" -> 1; "timesFloatX4#" -> 2; else -> error("Invalid FloatX4 operation") }
                    b.beginVectorFloatBinary(operation)
                    operands.forEach { it.emit(e) }; b.endVectorFloatBinary()
                }
            }
        }, CoreVectors.proofFloat)
    }

    private fun vectorDoublePrimitive(name: String, operands: List<Expression>): Expression = when (name) {
        "unpackDoubleX2#" -> tupleExpression(CoreVectors.unpackedDouble) { e, destination ->
            e.builder.beginVectorDoubleUnpack(destination[0], destination[1])
            operands[0].emit(e)
            e.builder.endVectorDoubleUnpack()
        }
        else -> ProvenExpression(Expression { e ->
            val b = e.builder
            when (name) {
                "packDoubleX2#" -> {
                    b.beginBlock()
                    val lanes = List(2) { b.createLocal() }
                    operands[0].emitTuple(e, lanes)
                    b.beginVectorDoublePack(); lanes.forEach(b::emitLoadLocal); b.endVectorDoublePack()
                    b.endBlock()
                }
                "broadcastDoubleX2#" -> { b.beginVectorDoubleBroadcast(); operands[0].emit(e); b.endVectorDoubleBroadcast() }
                else -> {
                    val operation = when (name) { "plusDoubleX2#" -> 0; "minusDoubleX2#" -> 1; "timesDoubleX2#" -> 2; else -> error("Invalid DoubleX2 operation") }
                    b.beginVectorDoubleBinary(operation)
                    operands.forEach { it.emit(e) }; b.endVectorDoubleBinary()
                }
            }
        }, CoreVectors.proofDouble)
    }

    private fun sumCase(expr: List<Any?>, scrutinee: Expression, proof: CoreRepresentation, scope: Scope, tail: Boolean): Expression {
        val shape = TupleShape(proof, language)
        val fields = shape.leaves.mapIndexed { index, field -> Local(nextLocal++, "sum field $index", field.isLong, field) }
        scope.bindTuple(expr[2] as String, proof, fields)
        data class Arm(val tag: Int?, val body: Expression)
        val seen = mutableSetOf<Int?>()
        val arms = (expr[3] as List<List<Any?>>).map { alt ->
            val child = scope.child()
            val ids = alt[2] as List<String>
            val tag = if (alt[0] == "default") {
                if (ids.isNotEmpty() || CoreRepresentations.alternativeBinders(alt).isNotEmpty())
                    throw RuntimeFault("Invalid sum DEFAULT alternative")
                null
            } else {
                if (alt[0] != "data" || ids.size != 1) throw RuntimeFault("Invalid sum alternative")
                val selected = SumShape.constructor(proof, constructors[alt[1]], ids.size)
                val component = proof.alternatives!![selected - 1]
                val metadata = CoreRepresentations.alternativeBinders(alt)
                if (metadata.size != 1 || metadata[0]["id"] != ids[0]) throw RuntimeFault("Missing sum payload binder proof")
                val actual = CoreRepresentations.binder(metadata.single())
                val lifted = metadata.single()["lifted"] as? Boolean ?: throw RuntimeFault("Unknown sum payload binder levity")
                SumShape.payload(component, actual, lifted)
                val logical = component.refine(actual).copy(evaluated = component.evaluated)
                val projected = proof.alternativeSlots!![selected - 1].map { fields[it] }
                if (component.isTuple) {
                    val leaves = TupleShape.flatten(logical)
                    child.bindTuple(ids[0], logical, projected.mapIndexed { i, field -> field.copy(proof = leaves[i]) })
                } else if (component.kind == CoreKind.VOID) child.bindVoid(ids[0], logical)
                else child.bindLocal(ids[0], projected.single().copy(name = ids[0], proof = logical))
                selected
            }
            if (!seen.add(tag)) throw RuntimeFault("Duplicate sum alternative")
            Arm(tag, compile(alt[3] as List<Any?>, child, tail))
        }
        if (arms.isEmpty()) throw RuntimeFault("Empty sum case")
        val result = arms.first().body.proof.refine(CoreRepresentations.expression(expr))
        arms.forEach { result.refine(it.body.proof) }
        CoreRepresentations.validateFloatingCaseResult(result, arms.map { it.body.proof })
        CoreRepresentations.requireNoVector(result, "sum case result")
        return ProvenExpression(ResultExpression { e, destination ->
            val b = e.builder
            b.beginBlock()
            fields.forEach { e.locals[it.id] = b.createLocal(it.name, if (it.primitive) "primitive" else "object") }
            scrutinee.emitTuple(e, fields.map { e.locals.getValue(it.id) })
            b.beginStoreLocal(e.locals.getValue(fields[0].id)); b.beginCheckSumTag()
            read(fields[0]).emit(e); b.endCheckSumTag(); b.endStoreLocal()
            val explicit = arms.filter { it.tag != null }
            val fallback = arms.singleOrNull { it.tag == null }
            fun choice(index: Int) {
                if (index == explicit.size) {
                    if (fallback == null) b.emitFailCase() else emitResult(fallback.body, e, destination)
                    return
                }
                val arm = explicit[index]
                if (destination == null) b.beginConditional() else b.beginIfThenElse()
                b.beginMatchLiteral(arm.tag!!.toLong()); read(fields[0]).emit(e); b.endMatchLiteral()
                emitResult(arm.body, e, destination); choice(index + 1)
                if (destination == null) b.endConditional() else b.endIfThenElse()
            }
            choice(0)
            b.endBlock()
            fields.forEach { e.locals.remove(it.id) }
        }, result.copy(evaluated = arms.all { it.body.proof.evaluated }))
    }
    private fun vectorByteArray(operation: VectorByteArrayOp, operands: List<Expression>): Expression =
        ProvenExpression(Expression { e ->
            val b = e.builder
            when (operation.family) {
                VectorMemoryFamily.INT32 -> when {
                    operation.isWrite -> b.beginWriteVector32Array(operation.scalarOffset)
                    operation.isRead -> b.beginReadVector32Array(operation.scalarOffset)
                    else -> b.beginIndexVector32Array(operation.scalarOffset)
                }
                VectorMemoryFamily.WORD32 -> when {
                    operation.isWrite -> b.beginWriteVectorWord32Array(operation.scalarOffset)
                    operation.isRead -> b.beginReadVectorWord32Array(operation.scalarOffset)
                    else -> b.beginIndexVectorWord32Array(operation.scalarOffset)
                }
                VectorMemoryFamily.FLOAT32 -> when {
                    operation.isWrite -> b.beginWriteVectorFloatArray(operation.scalarOffset)
                    operation.isRead -> b.beginReadVectorFloatArray(operation.scalarOffset)
                    else -> b.beginIndexVectorFloatArray(operation.scalarOffset)
                }
                VectorMemoryFamily.DOUBLE64 -> when {
                    operation.isWrite -> b.beginWriteVectorDoubleArray(operation.scalarOffset)
                    operation.isRead -> b.beginReadVectorDoubleArray(operation.scalarOffset)
                    else -> b.beginIndexVectorDoubleArray(operation.scalarOffset)
                }
            }
            operands.forEach { it.emit(e) }
            when (operation.family) {
                VectorMemoryFamily.INT32 -> when {
                    operation.isWrite -> b.endWriteVector32Array()
                    operation.isRead -> b.endReadVector32Array()
                    else -> b.endIndexVector32Array()
                }
                VectorMemoryFamily.WORD32 -> when {
                    operation.isWrite -> b.endWriteVectorWord32Array()
                    operation.isRead -> b.endReadVectorWord32Array()
                    else -> b.endIndexVectorWord32Array()
                }
                VectorMemoryFamily.FLOAT32 -> when {
                    operation.isWrite -> b.endWriteVectorFloatArray()
                    operation.isRead -> b.endReadVectorFloatArray()
                    else -> b.endIndexVectorFloatArray()
                }
                VectorMemoryFamily.DOUBLE64 -> when {
                    operation.isWrite -> b.endWriteVectorDoubleArray()
                    operation.isRead -> b.endReadVectorDoubleArray()
                    else -> b.endIndexVectorDoubleArray()
                }
            }
        }, if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof)
    private fun vectorReadCase(read: VectorReadCase, scope: Scope, tail: Boolean): Expression {
        val operands = read.arguments.map { compile(it, scope, false) }
        val value = vectorByteArray(read.operation, operands)
        val local = scope.child()
        local.bindVoid(read.stateBinder, CoreVectorMemory.stateProof)
        val vector = bind(local, read.vectorBinder, false, read.operation.vectorProof)
        val body = compile(read.body, local, tail)
        // No whole-tuple local or result handoff is ever created here.
        return LoweredCaseExpression(ProvenExpression(ResultExpression { e, destination ->
            val b = e.builder
            b.beginBlock()
            e.locals[vector.id] = b.createLocal(vector.name, "object")
            b.beginStoreLocal(e.locals.getValue(vector.id)); value.emit(e); b.endStoreLocal()
            emitResult(body, e, destination)
            b.endBlock()
            e.locals.remove(vector.id)
        }, body.proof))
    }
    private fun tupleCase(expr: List<Any?>, scrutinee: Expression, proof: CoreRepresentation, scope: Scope, tail: Boolean): Expression {
        val shape = TupleShape(proof, language)
        val fields = shape.leaves.mapIndexed { index, field -> Local(nextLocal++, "tuple field $index", field.isLong, field) }
        scope.bindTuple(expr[2] as String, proof, fields)
        val alternatives = expr[3] as List<List<Any?>>
        if (alternatives.size != 1) throw RuntimeFault("Tuple case requires one alternative")
        val alt = alternatives.single()
        val ids = alt[2] as List<String>
        if (alt[0] == "data") {
            if (constructors[alt[1]]?.get("kind") != "unboxed-tuple" || ids.size != shape.components.size ||
                (constructors[alt[1]]?.get("arity") as? Number)?.toInt() != ids.size)
                throw RuntimeFault("Tuple alternative shape mismatch")
            val metadata = CoreRepresentations.alternativeBinders(alt)
            ids.forEachIndexed { index, id ->
                val component = shape.components[index]
                metadata.getOrNull(index)?.let { TupleShape.requireCompatible(component, CoreRepresentations.binder(it), component = true) }
                val offset = shape.offsets[index]
                val width = TupleShape.flatten(component).size
                if (component.isTuple) scope.bindTuple(id, component, fields.subList(offset, offset + width))
                else if (component.kind == CoreKind.VOID) scope.bindVoid(id, component)
                else scope.bindLocal(id, fields[offset].copy(name = id))
            }
        } else if (alt[0] != "default" || ids.isNotEmpty()) throw RuntimeFault("Invalid tuple alternative")
        val body = compile(alt[3] as List<Any?>, scope, tail)
        return ProvenExpression(ResultExpression { e, destination ->
            val b = e.builder
            b.beginBlock()
            fields.forEach { e.locals[it.id] = b.createLocal(it.name, if (it.primitive) "primitive" else "object") }
            scrutinee.emitTuple(e, fields.map { e.locals.getValue(it.id) })
            emitResult(body, e, destination)
            b.endBlock()
            fields.forEach { e.locals.remove(it.id) }
        }, body.proof)
    }

    private fun construct(layout: DataLayout, args: List<Expression>) = evaluated(Expression { e ->
        e.builder.beginConstruct(layout); args.forEach { it.emit(e) }; e.builder.endConstruct()
    })
    private fun floatingPrimitive(name: String, args: List<Expression>): Expression? {
        val operation = when (name) {
            "fmaddFloat#" -> "FloatFMAdd"
            "fmsubFloat#" -> "FloatFMSub"
            "fnmaddFloat#" -> "FloatFNMAdd"
            "fnmsubFloat#" -> "FloatFNMSub"
            "fmaddDouble#" -> "DoubleFMAdd"
            "fmsubDouble#" -> "DoubleFMSub"
            "fnmaddDouble#" -> "DoubleFNMAdd"
            "fnmsubDouble#" -> "DoubleFNMSub"
            "plusFloat#" -> "FloatAdd"
            "minusFloat#" -> "FloatSubtract"
            "timesFloat#" -> "FloatMultiply"
            "divideFloat#" -> "FloatDivide"
            "negateFloat#" -> "FloatNegate"
            "sqrtFloat#" -> "FloatSqrt"
            "fabsFloat#" -> "FloatAbs"
            "expFloat#" -> "FloatExp"
            "expm1Float#" -> "FloatExpm1"
            "logFloat#" -> "FloatLog"
            "log1pFloat#" -> "FloatLog1p"
            "sinFloat#" -> "FloatSin"
            "cosFloat#" -> "FloatCos"
            "powerFloat#" -> "FloatPower"
            "tanFloat#" -> "FloatTan"
            "asinFloat#" -> "FloatAsin"
            "acosFloat#" -> "FloatAcos"
            "atanFloat#" -> "FloatAtan"
            "sinhFloat#" -> "FloatSinh"
            "coshFloat#" -> "FloatCosh"
            "tanhFloat#" -> "FloatTanh"
            "eqFloat#" -> "FloatEqual"
            "neFloat#" -> "FloatNotEqual"
            "ltFloat#" -> "FloatLess"
            "leFloat#" -> "FloatLessEqual"
            "gtFloat#" -> "FloatGreater"
            "geFloat#" -> "FloatGreaterEqual"
            "+##" -> "DoubleAdd"
            "-##" -> "DoubleSubtract"
            "*##" -> "DoubleMultiply"
            "/##" -> "DoubleDivide"
            "negateDouble#" -> "DoubleNegate"
            "sqrtDouble#" -> "DoubleSqrt"
            "fabsDouble#" -> "DoubleAbs"
            "expDouble#" -> "DoubleExp"
            "expm1Double#" -> "DoubleExpm1"
            "logDouble#" -> "DoubleLog"
            "log1pDouble#" -> "DoubleLog1p"
            "sinDouble#" -> "DoubleSin"
            "cosDouble#" -> "DoubleCos"
            "**##" -> "DoublePower"
            "tanDouble#" -> "DoubleTan"
            "asinDouble#" -> "DoubleAsin"
            "acosDouble#" -> "DoubleAcos"
            "atanDouble#" -> "DoubleAtan"
            "sinhDouble#" -> "DoubleSinh"
            "coshDouble#" -> "DoubleCosh"
            "tanhDouble#" -> "DoubleTanh"
            "==##" -> "DoubleEqual"
            "/=##" -> "DoubleNotEqual"
            "<##" -> "DoubleLess"
            "<=##" -> "DoubleLessEqual"
            ">##" -> "DoubleGreater"
            ">=##" -> "DoubleGreaterEqual"
            "castFloatToWord32#" -> "CastFloatToWord32"
            "castWord32ToFloat#" -> "CastWord32ToFloat"
            "castDoubleToWord64#" -> "CastDoubleToWord64"
            "castWord64ToDouble#" -> "CastWord64ToDouble"
            "int2Float#" -> "IntToFloat"
            "int2Double#" -> "IntToDouble"
            "word2Float#" -> "WordToFloat"
            "word2Double#" -> "WordToDouble"
            "float2Int#" -> "FloatToInt"
            "double2Int#" -> "DoubleToInt"
            "float2Double#" -> "FloatToDouble"
            "double2Float#" -> "DoubleToFloat"
            else -> return null
        }
        val unary = operation in setOf("CastFloatToWord32", "CastWord32ToFloat", "CastDoubleToWord64", "CastWord64ToDouble", "FloatNegate", "DoubleNegate", "FloatSqrt", "DoubleSqrt", "IntToFloat", "WordToFloat", "IntToDouble", "WordToDouble", "FloatToInt", "DoubleToInt", "FloatToDouble", "DoubleToFloat", "FloatAbs", "FloatExp", "FloatExpm1", "FloatLog", "FloatLog1p", "FloatSin", "FloatCos", "DoubleAbs", "DoubleExp", "DoubleExpm1", "DoubleLog", "DoubleLog1p", "DoubleSin", "DoubleCos", "FloatTan", "FloatAsin", "FloatAcos", "FloatAtan", "FloatSinh", "FloatCosh", "FloatTanh", "DoubleTan", "DoubleAsin", "DoubleAcos", "DoubleAtan", "DoubleSinh", "DoubleCosh", "DoubleTanh")
        val fused = operation in setOf("FloatFMAdd", "FloatFMSub", "FloatFNMAdd", "FloatFNMSub",
            "DoubleFMAdd", "DoubleFMSub", "DoubleFNMAdd", "DoubleFNMSub")
        if (args.size != if (fused) 3 else if (unary) 1 else 2) throw RuntimeFault("Primitive arity mismatch: $name")
        val kind = when (operation) {
            "FloatFMAdd", "FloatFMSub", "FloatFNMAdd", "FloatFNMSub" -> CoreKind.FLOAT
            "DoubleFMAdd", "DoubleFMSub", "DoubleFNMAdd", "DoubleFNMSub" -> CoreKind.DOUBLE
            "FloatAdd", "FloatSubtract", "FloatMultiply", "FloatDivide", "FloatNegate", "FloatSqrt", "IntToFloat", "WordToFloat", "DoubleToFloat", "CastWord32ToFloat", "FloatAbs", "FloatExp", "FloatExpm1", "FloatLog", "FloatLog1p", "FloatSin", "FloatCos", "FloatPower", "FloatTan", "FloatAsin", "FloatAcos", "FloatAtan", "FloatSinh", "FloatCosh", "FloatTanh" -> CoreKind.FLOAT
            "DoubleAdd", "DoubleSubtract", "DoubleMultiply", "DoubleDivide", "DoubleNegate", "DoubleSqrt", "IntToDouble", "WordToDouble", "FloatToDouble", "CastWord64ToDouble", "DoubleAbs", "DoubleExp", "DoubleExpm1", "DoubleLog", "DoubleLog1p", "DoubleSin", "DoubleCos", "DoublePower", "DoubleTan", "DoubleAsin", "DoubleAcos", "DoubleAtan", "DoubleSinh", "DoubleCosh", "DoubleTanh" -> CoreKind.DOUBLE
            else -> CoreKind.LONG
        }
        return ProvenExpression(Expression { e ->
            val b = e.builder
            when (operation) {
                "FloatFMAdd" -> b.beginFloatFMAdd()
                "FloatFMSub" -> b.beginFloatFMSub()
                "FloatFNMAdd" -> b.beginFloatFNMAdd()
                "FloatFNMSub" -> b.beginFloatFNMSub()
                "DoubleFMAdd" -> b.beginDoubleFMAdd()
                "DoubleFMSub" -> b.beginDoubleFMSub()
                "DoubleFNMAdd" -> b.beginDoubleFNMAdd()
                "DoubleFNMSub" -> b.beginDoubleFNMSub()
                "FloatAdd" -> b.beginFloatAdd()
                "FloatSubtract" -> b.beginFloatSubtract()
                "FloatMultiply" -> b.beginFloatMultiply()
                "FloatDivide" -> b.beginFloatDivide()
                "FloatNegate" -> b.beginFloatNegate()
                "FloatSqrt" -> b.beginFloatSqrt()
                "FloatAbs" -> b.beginFloatAbs()
                "FloatExp" -> b.beginFloatExp()
                "FloatExpm1" -> b.beginFloatExpm1()
                "FloatLog" -> b.beginFloatLog()
                "FloatLog1p" -> b.beginFloatLog1p()
                "FloatSin" -> b.beginFloatSin()
                "FloatCos" -> b.beginFloatCos()
                "FloatPower" -> b.beginFloatPower()
                "DoubleAbs" -> b.beginDoubleAbs()
                "DoubleExp" -> b.beginDoubleExp()
                "DoubleExpm1" -> b.beginDoubleExpm1()
                "DoubleLog" -> b.beginDoubleLog()
                "DoubleLog1p" -> b.beginDoubleLog1p()
                "DoubleSin" -> b.beginDoubleSin()
                "DoubleCos" -> b.beginDoubleCos()
                "DoublePower" -> b.beginDoublePower()
                "FloatTan" -> b.beginFloatTan()
                "FloatAsin" -> b.beginFloatAsin()
                "FloatAcos" -> b.beginFloatAcos()
                "FloatAtan" -> b.beginFloatAtan()
                "FloatSinh" -> b.beginFloatSinh()
                "FloatCosh" -> b.beginFloatCosh()
                "FloatTanh" -> b.beginFloatTanh()
                "DoubleTan" -> b.beginDoubleTan()
                "DoubleAsin" -> b.beginDoubleAsin()
                "DoubleAcos" -> b.beginDoubleAcos()
                "DoubleAtan" -> b.beginDoubleAtan()
                "DoubleSinh" -> b.beginDoubleSinh()
                "DoubleCosh" -> b.beginDoubleCosh()
                "DoubleTanh" -> b.beginDoubleTanh()
                "FloatEqual" -> b.beginFloatEqual()
                "FloatNotEqual" -> b.beginFloatNotEqual()
                "FloatLess" -> b.beginFloatLess()
                "FloatLessEqual" -> b.beginFloatLessEqual()
                "FloatGreater" -> b.beginFloatGreater()
                "FloatGreaterEqual" -> b.beginFloatGreaterEqual()
                "DoubleAdd" -> b.beginDoubleAdd()
                "DoubleSubtract" -> b.beginDoubleSubtract()
                "DoubleMultiply" -> b.beginDoubleMultiply()
                "DoubleDivide" -> b.beginDoubleDivide()
                "DoubleNegate" -> b.beginDoubleNegate()
                "DoubleSqrt" -> b.beginDoubleSqrt()
                "DoubleEqual" -> b.beginDoubleEqual()
                "DoubleNotEqual" -> b.beginDoubleNotEqual()
                "DoubleLess" -> b.beginDoubleLess()
                "DoubleLessEqual" -> b.beginDoubleLessEqual()
                "DoubleGreater" -> b.beginDoubleGreater()
                "DoubleGreaterEqual" -> b.beginDoubleGreaterEqual()
                "CastFloatToWord32" -> b.beginCastFloatToWord32()
                "CastWord32ToFloat" -> b.beginCastWord32ToFloat()
                "CastDoubleToWord64" -> b.beginCastDoubleToWord64()
                "CastWord64ToDouble" -> b.beginCastWord64ToDouble()
                "IntToFloat" -> b.beginIntToFloat()
                "IntToDouble" -> b.beginIntToDouble()
                "WordToFloat" -> b.beginWordToFloat()
                "WordToDouble" -> b.beginWordToDouble()
                "FloatToInt" -> b.beginFloatToInt()
                "DoubleToInt" -> b.beginDoubleToInt()
                "FloatToDouble" -> b.beginFloatToDouble()
                "DoubleToFloat" -> b.beginDoubleToFloat()
            }
            args.forEach { it.emit(e) }
            when (operation) {
                "FloatFMAdd" -> b.endFloatFMAdd()
                "FloatFMSub" -> b.endFloatFMSub()
                "FloatFNMAdd" -> b.endFloatFNMAdd()
                "FloatFNMSub" -> b.endFloatFNMSub()
                "DoubleFMAdd" -> b.endDoubleFMAdd()
                "DoubleFMSub" -> b.endDoubleFMSub()
                "DoubleFNMAdd" -> b.endDoubleFNMAdd()
                "DoubleFNMSub" -> b.endDoubleFNMSub()
                "FloatAdd" -> b.endFloatAdd()
                "FloatSubtract" -> b.endFloatSubtract()
                "FloatMultiply" -> b.endFloatMultiply()
                "FloatDivide" -> b.endFloatDivide()
                "FloatNegate" -> b.endFloatNegate()
                "FloatSqrt" -> b.endFloatSqrt()
                "FloatAbs" -> b.endFloatAbs()
                "FloatExp" -> b.endFloatExp()
                "FloatExpm1" -> b.endFloatExpm1()
                "FloatLog" -> b.endFloatLog()
                "FloatLog1p" -> b.endFloatLog1p()
                "FloatSin" -> b.endFloatSin()
                "FloatCos" -> b.endFloatCos()
                "FloatPower" -> b.endFloatPower()
                "DoubleAbs" -> b.endDoubleAbs()
                "DoubleExp" -> b.endDoubleExp()
                "DoubleExpm1" -> b.endDoubleExpm1()
                "DoubleLog" -> b.endDoubleLog()
                "DoubleLog1p" -> b.endDoubleLog1p()
                "DoubleSin" -> b.endDoubleSin()
                "DoubleCos" -> b.endDoubleCos()
                "DoublePower" -> b.endDoublePower()
                "FloatTan" -> b.endFloatTan()
                "FloatAsin" -> b.endFloatAsin()
                "FloatAcos" -> b.endFloatAcos()
                "FloatAtan" -> b.endFloatAtan()
                "FloatSinh" -> b.endFloatSinh()
                "FloatCosh" -> b.endFloatCosh()
                "FloatTanh" -> b.endFloatTanh()
                "DoubleTan" -> b.endDoubleTan()
                "DoubleAsin" -> b.endDoubleAsin()
                "DoubleAcos" -> b.endDoubleAcos()
                "DoubleAtan" -> b.endDoubleAtan()
                "DoubleSinh" -> b.endDoubleSinh()
                "DoubleCosh" -> b.endDoubleCosh()
                "DoubleTanh" -> b.endDoubleTanh()
                "FloatEqual" -> b.endFloatEqual()
                "FloatNotEqual" -> b.endFloatNotEqual()
                "FloatLess" -> b.endFloatLess()
                "FloatLessEqual" -> b.endFloatLessEqual()
                "FloatGreater" -> b.endFloatGreater()
                "FloatGreaterEqual" -> b.endFloatGreaterEqual()
                "DoubleAdd" -> b.endDoubleAdd()
                "DoubleSubtract" -> b.endDoubleSubtract()
                "DoubleMultiply" -> b.endDoubleMultiply()
                "DoubleDivide" -> b.endDoubleDivide()
                "DoubleNegate" -> b.endDoubleNegate()
                "DoubleSqrt" -> b.endDoubleSqrt()
                "DoubleEqual" -> b.endDoubleEqual()
                "DoubleNotEqual" -> b.endDoubleNotEqual()
                "DoubleLess" -> b.endDoubleLess()
                "DoubleLessEqual" -> b.endDoubleLessEqual()
                "DoubleGreater" -> b.endDoubleGreater()
                "DoubleGreaterEqual" -> b.endDoubleGreaterEqual()
                "CastFloatToWord32" -> b.endCastFloatToWord32()
                "CastWord32ToFloat" -> b.endCastWord32ToFloat()
                "CastDoubleToWord64" -> b.endCastDoubleToWord64()
                "CastWord64ToDouble" -> b.endCastWord64ToDouble()
                "IntToFloat" -> b.endIntToFloat()
                "IntToDouble" -> b.endIntToDouble()
                "WordToFloat" -> b.endWordToFloat()
                "WordToDouble" -> b.endWordToDouble()
                "FloatToInt" -> b.endFloatToInt()
                "DoubleToInt" -> b.endDoubleToInt()
                "FloatToDouble" -> b.endFloatToDouble()
                "DoubleToFloat" -> b.endDoubleToFloat()
            }
        }, CoreRepresentation(kind, evaluated = true))
    }


    private fun primitive(name: String, args: List<Expression>): Expression {
        floatingPrimitive(name, args)?.let { return it }
        val wordMask = narrowWordPrimitiveMask(name)
        val intShift = narrowIntPrimitiveShift(name)
        val bitShift = scalarBitPrimitiveShift(name)
        val operation = when (scalar64PrimitiveOperation(name)) {
            "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> "PopulationCountWidth"
            "clz8#", "clz16#", "clz32#", "clz64#" -> "CountLeadingZerosWidth"
            "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> "CountTrailingZerosWidth"
            "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> "ByteSwapWidth"
            "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> "BitReverseWidth"
            "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#" -> "BitDepositWidth"
            "pext8#", "pext16#", "pext32#", "pext64#", "pext#" -> "BitExtractWidth"

            "negateInt8#", "negateInt16#", "negateInt32#" -> "NegateNarrowInt"
            "plusInt8#", "plusInt16#", "plusInt32#" -> "AddNarrowInt"
            "subInt8#", "subInt16#", "subInt32#" -> "SubtractNarrowInt"
            "timesInt8#", "timesInt16#", "timesInt32#" -> "MultiplyNarrowInt"
            "quotInt8#", "quotInt16#", "quotInt32#" -> "QuotientNarrowInt"
            "remInt8#", "remInt16#", "remInt32#" -> "RemainderNarrowInt"
            "eqInt8#", "eqInt16#", "eqInt32#" -> "EqualNarrowInt"
            "neInt8#", "neInt16#", "neInt32#" -> "NotEqualNarrowInt"
            "ltInt8#", "ltInt16#", "ltInt32#" -> "LessThanNarrowInt"
            "leInt8#", "leInt16#", "leInt32#" -> "LessEqualNarrowInt"
            "gtInt8#", "gtInt16#", "gtInt32#" -> "GreaterThanNarrowInt"
            "geInt8#", "geInt16#", "geInt32#" -> "GreaterEqualNarrowInt"
            "uncheckedShiftLInt8#", "uncheckedShiftLInt16#", "uncheckedShiftLInt32#" -> "ShiftLeftNarrowInt"
            "uncheckedShiftRAInt8#", "uncheckedShiftRAInt16#", "uncheckedShiftRAInt32#" -> "ShiftRightNarrowInt"

            "quotWord#" -> "QuotientUnsigned"
            "remWord#" -> "RemainderUnsigned"
            "gtWord#" -> "GreaterThanUnsigned"
            "geWord#" -> "GreaterEqualUnsigned"
            "quotWord8#", "quotWord16#", "quotWord32#" -> "QuotientNarrowWord"
            "remWord8#", "remWord16#", "remWord32#" -> "RemainderNarrowWord"
            "eqWord8#", "eqWord16#", "eqWord32#" -> "EqualNarrowWord"
            "neWord8#", "neWord16#", "neWord32#" -> "NotEqualNarrowWord"
            "gtWord8#", "gtWord16#", "gtWord32#" -> "GreaterThanNarrowWord"
            "geWord8#", "geWord16#", "geWord32#" -> "GreaterEqualNarrowWord"
            "andWord8#", "andWord16#", "andWord32#" -> "BitAndNarrowWord"
            "orWord8#", "orWord16#", "orWord32#" -> "BitOrNarrowWord"
            "xorWord8#", "xorWord16#", "xorWord32#" -> "BitXorNarrowWord"
            "notWord8#", "notWord16#", "notWord32#" -> "BitNotNarrowWord"
            "uncheckedShiftLWord8#", "uncheckedShiftLWord16#", "uncheckedShiftLWord32#" -> "ShiftLeftNarrowWord"
            "uncheckedShiftRLWord8#", "uncheckedShiftRLWord16#", "uncheckedShiftRLWord32#" -> "ShiftRightNarrowWord"
            "+#", "plusWord#" -> "Add"
            "-#", "minusWord#" -> "Subtract"
            "*#", "timesWord#" -> "Multiply"
            "plusWord8#", "plusWord16#", "plusWord32#" -> "AddNarrowWord"
            "subWord8#", "subWord16#", "subWord32#" -> "SubtractNarrowWord"
            "timesWord8#", "timesWord16#", "timesWord32#" -> "MultiplyNarrowWord"
            "negateInt#" -> "Negate"
            "quotInt#" -> "Quotient"
            "remInt#" -> "Remainder"
            "==#", "eqWord#", "eqChar#" -> "Equal"
            "reallyUnsafePtrEquality#" -> "PointerEqual"
            "/=#", "neWord#", "neChar#" -> "NotEqual"
            "<#", "ltChar#" -> "LessThan"
            "ltWord#" -> "LessThanUnsigned"
            "<=#", "leChar#" -> "LessEqual"
            "leWord#" -> "LessEqualUnsigned"
            "ltWord8#", "ltWord16#", "ltWord32#" -> "LessThanNarrowWord"
            "leWord8#", "leWord16#", "leWord32#" -> "LessEqualNarrowWord"
            ">#", "gtChar#" -> "GreaterThan"
            ">=#", "geChar#" -> "GreaterEqual"
            "and#", "andI#" -> "BitAnd"
            "or#", "orI#" -> "BitOr"
            "xor#", "xorI#" -> "BitXor"
            "not#", "notI#" -> "BitNot"
            "clz#" -> "CountLeadingZeros"
            "ctz#" -> "CountTrailingZeros"
            "popCnt#" -> "PopulationCount"
            "uncheckedIShiftL#", "uncheckedShiftL#" -> "ShiftLeft"
            "uncheckedIShiftRA#" -> "ShiftRight"
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> "ShiftRightUnsigned"
            // Match AST sign-normalized Long carriers in both conversion directions.
            "narrow8Int#", "intToInt8#", "int8ToInt#", "word8ToInt8#" -> "Narrow8"
            "narrow16Int#", "intToInt16#", "int16ToInt#", "word16ToInt16#" -> "Narrow16"
            "narrow32Int#", "intToInt32#", "int32ToInt#", "word32ToInt32#" -> "Narrow32"
            "wordToWord8#", "word8ToWord#", "int8ToWord8#", "wordToWord16#", "word16ToWord#", "int16ToWord16#",
            "wordToWord32#", "word32ToWord#", "int32ToWord32#",
            "narrow8Word#", "narrow16Word#", "narrow32Word#" -> "NarrowWord"
            "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> "Identity"
            "raise#" -> "Raise"
            "plusAddr#" -> "AddressPlus"
            "eqAddr#" -> "AddressEqual"
            "neAddr#" -> "AddressNotEqual"
            "ltAddr#", "leAddr#", "gtAddr#", "geAddr#" -> "AddressOrder"
            "indexCharOffAddr#", "indexWord8OffAddr#", "indexInt8OffAddr#" -> "AddressIndexByte"
            "indexWord16OffAddr#", "indexInt16OffAddr#" -> "AddressIndexManagedScalar"
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        val unary = operation in setOf("PopulationCountWidth", "CountLeadingZerosWidth", "CountTrailingZerosWidth", "ByteSwapWidth", "BitReverseWidth", "NegateNarrowInt", "BitNotNarrowWord", "Negate", "BitNot", "CountLeadingZeros", "CountTrailingZeros", "PopulationCount",
            "Narrow8", "Narrow16", "Narrow32", "NarrowWord", "Identity", "Raise")
        if (args.size != if (unary) 1 else 2) throw RuntimeFault("Primitive arity mismatch: $name")
        if (operation == "Identity") return evaluated(Expression { e -> e.builder.beginToLong(); args[0].emit(e); e.builder.endToLong() })
        return evaluated(Expression { e ->
            val b = e.builder
            when (operation) {
                "NegateNarrowInt" -> b.beginNegateNarrowInt(intShift)
                "AddNarrowInt" -> b.beginAddNarrowInt(intShift)
                "SubtractNarrowInt" -> b.beginSubtractNarrowInt(intShift)
                "MultiplyNarrowInt" -> b.beginMultiplyNarrowInt(intShift)
                "QuotientNarrowInt" -> b.beginQuotientNarrowInt(intShift)
                "RemainderNarrowInt" -> b.beginRemainderNarrowInt(intShift)
                "EqualNarrowInt" -> b.beginEqualNarrowInt(intShift)
                "NotEqualNarrowInt" -> b.beginNotEqualNarrowInt(intShift)
                "LessThanNarrowInt" -> b.beginLessThanNarrowInt(intShift)
                "LessEqualNarrowInt" -> b.beginLessEqualNarrowInt(intShift)
                "GreaterThanNarrowInt" -> b.beginGreaterThanNarrowInt(intShift)
                "GreaterEqualNarrowInt" -> b.beginGreaterEqualNarrowInt(intShift)
                "ShiftLeftNarrowInt" -> b.beginShiftLeftNarrowInt(intShift)
                "ShiftRightNarrowInt" -> b.beginShiftRightNarrowInt(intShift)
                "QuotientUnsigned" -> b.beginQuotientUnsigned()
                "RemainderUnsigned" -> b.beginRemainderUnsigned()
                "GreaterThanUnsigned" -> b.beginGreaterThanUnsigned()
                "GreaterEqualUnsigned" -> b.beginGreaterEqualUnsigned()
                "QuotientNarrowWord" -> b.beginQuotientNarrowWord(wordMask)
                "RemainderNarrowWord" -> b.beginRemainderNarrowWord(wordMask)
                "EqualNarrowWord" -> b.beginEqualNarrowWord(wordMask)
                "NotEqualNarrowWord" -> b.beginNotEqualNarrowWord(wordMask)
                "GreaterThanNarrowWord" -> b.beginGreaterThanNarrowWord(wordMask)
                "GreaterEqualNarrowWord" -> b.beginGreaterEqualNarrowWord(wordMask)
                "BitAndNarrowWord" -> b.beginBitAndNarrowWord(wordMask)
                "BitOrNarrowWord" -> b.beginBitOrNarrowWord(wordMask)
                "BitXorNarrowWord" -> b.beginBitXorNarrowWord(wordMask)
                "BitNotNarrowWord" -> b.beginBitNotNarrowWord(wordMask)
                "ShiftLeftNarrowWord" -> b.beginShiftLeftNarrowWord(wordMask)
                "ShiftRightNarrowWord" -> b.beginShiftRightNarrowWord(wordMask)
                "Add" -> b.beginAdd(); "Subtract" -> b.beginSubtract(); "Multiply" -> b.beginMultiply()
                "AddNarrowWord" -> b.beginAddNarrowWord(wordMask); "SubtractNarrowWord" -> b.beginSubtractNarrowWord(wordMask)
                "MultiplyNarrowWord" -> b.beginMultiplyNarrowWord(wordMask)
                "Negate" -> b.beginNegate(); "Quotient" -> b.beginQuotient(); "Remainder" -> b.beginRemainder()
                "Equal" -> b.beginEqual(); "NotEqual" -> b.beginNotEqual(); "LessThan" -> b.beginLessThan()
                "PointerEqual" -> b.beginPointerEqual()
                "LessThanUnsigned" -> b.beginLessThanUnsigned()
                "LessEqual" -> b.beginLessEqual(); "GreaterThan" -> b.beginGreaterThan(); "GreaterEqual" -> b.beginGreaterEqual()
                "LessEqualUnsigned" -> b.beginLessEqualUnsigned()
                "LessThanNarrowWord" -> b.beginLessThanNarrowWord(wordMask); "LessEqualNarrowWord" -> b.beginLessEqualNarrowWord(wordMask)
                "BitAnd" -> b.beginBitAnd(); "BitOr" -> b.beginBitOr(); "BitXor" -> b.beginBitXor(); "BitNot" -> b.beginBitNot()
                "PopulationCountWidth" -> b.beginPopulationCountWidth(bitShift)
                "CountLeadingZerosWidth" -> b.beginCountLeadingZerosWidth(bitShift)
                "CountTrailingZerosWidth" -> b.beginCountTrailingZerosWidth(bitShift)
                "ByteSwapWidth" -> b.beginByteSwapWidth(bitShift)
                "BitReverseWidth" -> b.beginBitReverseWidth(bitShift)
                "BitDepositWidth" -> b.beginBitDepositWidth(bitShift)
                "BitExtractWidth" -> b.beginBitExtractWidth(bitShift)
                "CountLeadingZeros" -> b.beginCountLeadingZeros()
                "CountTrailingZeros" -> b.beginCountTrailingZeros(); "PopulationCount" -> b.beginPopulationCount()
                "ShiftLeft" -> b.beginShiftLeft(); "ShiftRight" -> b.beginShiftRight(); "ShiftRightUnsigned" -> b.beginShiftRightUnsigned()
                "Narrow8" -> b.beginNarrow8(); "Narrow16" -> b.beginNarrow16(); "Narrow32" -> b.beginNarrow32()
                "NarrowWord" -> b.beginNarrowWord(wordMask)
                "Raise" -> b.beginRaise(); "AddressPlus" -> b.beginAddressPlus()
                "AddressIndexByte" -> b.beginAddressIndexByte(name == "indexInt8OffAddr#")
                "AddressIndexManagedScalar" -> b.beginAddressIndexManagedScalar(
                    if (name == "indexInt16OffAddr#") ManagedAddressRead.INT16 else ManagedAddressRead.WORD16)
                "AddressEqual" -> b.beginAddressEqual(); "AddressNotEqual" -> b.beginAddressNotEqual()
                "AddressOrder" -> b.beginAddressOrder(when (name) {
                    "ltAddr#" -> ManagedAddressOrder.LT
                    "leAddr#" -> ManagedAddressOrder.LE
                    "gtAddr#" -> ManagedAddressOrder.GT
                    else -> ManagedAddressOrder.GE
                })
            }
            args.forEach { it.emit(e) }
            when (operation) {
                "NegateNarrowInt" -> b.endNegateNarrowInt()
                "AddNarrowInt" -> b.endAddNarrowInt()
                "SubtractNarrowInt" -> b.endSubtractNarrowInt()
                "MultiplyNarrowInt" -> b.endMultiplyNarrowInt()
                "QuotientNarrowInt" -> b.endQuotientNarrowInt()
                "RemainderNarrowInt" -> b.endRemainderNarrowInt()
                "EqualNarrowInt" -> b.endEqualNarrowInt()
                "NotEqualNarrowInt" -> b.endNotEqualNarrowInt()
                "LessThanNarrowInt" -> b.endLessThanNarrowInt()
                "LessEqualNarrowInt" -> b.endLessEqualNarrowInt()
                "GreaterThanNarrowInt" -> b.endGreaterThanNarrowInt()
                "GreaterEqualNarrowInt" -> b.endGreaterEqualNarrowInt()
                "ShiftLeftNarrowInt" -> b.endShiftLeftNarrowInt()
                "ShiftRightNarrowInt" -> b.endShiftRightNarrowInt()
                "QuotientUnsigned" -> b.endQuotientUnsigned()
                "RemainderUnsigned" -> b.endRemainderUnsigned()
                "GreaterThanUnsigned" -> b.endGreaterThanUnsigned()
                "GreaterEqualUnsigned" -> b.endGreaterEqualUnsigned()
                "QuotientNarrowWord" -> b.endQuotientNarrowWord()
                "RemainderNarrowWord" -> b.endRemainderNarrowWord()
                "EqualNarrowWord" -> b.endEqualNarrowWord()
                "NotEqualNarrowWord" -> b.endNotEqualNarrowWord()
                "GreaterThanNarrowWord" -> b.endGreaterThanNarrowWord()
                "GreaterEqualNarrowWord" -> b.endGreaterEqualNarrowWord()
                "BitAndNarrowWord" -> b.endBitAndNarrowWord()
                "BitOrNarrowWord" -> b.endBitOrNarrowWord()
                "BitXorNarrowWord" -> b.endBitXorNarrowWord()
                "BitNotNarrowWord" -> b.endBitNotNarrowWord()
                "ShiftLeftNarrowWord" -> b.endShiftLeftNarrowWord()
                "ShiftRightNarrowWord" -> b.endShiftRightNarrowWord()
                "Add" -> b.endAdd(); "Subtract" -> b.endSubtract(); "Multiply" -> b.endMultiply()
                "AddNarrowWord" -> b.endAddNarrowWord(); "SubtractNarrowWord" -> b.endSubtractNarrowWord()
                "MultiplyNarrowWord" -> b.endMultiplyNarrowWord()
                "Negate" -> b.endNegate(); "Quotient" -> b.endQuotient(); "Remainder" -> b.endRemainder()
                "Equal" -> b.endEqual(); "NotEqual" -> b.endNotEqual(); "LessThan" -> b.endLessThan()
                "PointerEqual" -> b.endPointerEqual()
                "LessThanUnsigned" -> b.endLessThanUnsigned()
                "LessEqual" -> b.endLessEqual(); "GreaterThan" -> b.endGreaterThan(); "GreaterEqual" -> b.endGreaterEqual()
                "LessEqualUnsigned" -> b.endLessEqualUnsigned()
                "LessThanNarrowWord" -> b.endLessThanNarrowWord(); "LessEqualNarrowWord" -> b.endLessEqualNarrowWord()
                "BitAnd" -> b.endBitAnd(); "BitOr" -> b.endBitOr(); "BitXor" -> b.endBitXor(); "BitNot" -> b.endBitNot()
                "PopulationCountWidth" -> b.endPopulationCountWidth()
                "CountLeadingZerosWidth" -> b.endCountLeadingZerosWidth()
                "CountTrailingZerosWidth" -> b.endCountTrailingZerosWidth()
                "ByteSwapWidth" -> b.endByteSwapWidth()
                "BitReverseWidth" -> b.endBitReverseWidth()
                "BitDepositWidth" -> b.endBitDepositWidth()
                "BitExtractWidth" -> b.endBitExtractWidth()
                "CountLeadingZeros" -> b.endCountLeadingZeros()
                "CountTrailingZeros" -> b.endCountTrailingZeros(); "PopulationCount" -> b.endPopulationCount()
                "ShiftLeft" -> b.endShiftLeft(); "ShiftRight" -> b.endShiftRight(); "ShiftRightUnsigned" -> b.endShiftRightUnsigned()
                "Narrow8" -> b.endNarrow8(); "Narrow16" -> b.endNarrow16(); "Narrow32" -> b.endNarrow32()
                "NarrowWord" -> b.endNarrowWord()
                "Raise" -> b.endRaise(); "AddressPlus" -> b.endAddressPlus(); "AddressIndexByte" -> b.endAddressIndexByte()
                "AddressIndexManagedScalar" -> b.endAddressIndexManagedScalar()
                "AddressEqual" -> b.endAddressEqual(); "AddressNotEqual" -> b.endAddressNotEqual()
                "AddressOrder" -> b.endAddressOrder()
            }
        })
    }

    // BEGIN GENERATED SIMD FAMILIES
    private fun generatedVectorPrimitive(name: String, operands: List<Expression>): Expression = when (name) {
        "packWord64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(2) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedWord64X2Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedWord64X2Pack()
            b.endBlock()
        }, GeneratedVectors.proofWord64X2)
        "unpackWord64X2#" -> tupleExpression(GeneratedVectors.unpackedWord64X2) { e, destination ->
            e.builder.beginGeneratedWord64X2Unpack(destination[0], destination[1])
            operands[0].emit(e)
            e.builder.endGeneratedWord64X2Unpack()
        }
        "broadcastWord64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord64X2Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedWord64X2Broadcast()
        }, GeneratedVectors.proofWord64X2)
        "plusWord64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord64X2Plus(); operands.forEach { it.emit(e) }; b.endGeneratedWord64X2Plus()
        }, GeneratedVectors.proofWord64X2)
        "minusWord64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord64X2Minus(); operands.forEach { it.emit(e) }; b.endGeneratedWord64X2Minus()
        }, GeneratedVectors.proofWord64X2)
        "timesWord64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord64X2Times(); operands.forEach { it.emit(e) }; b.endGeneratedWord64X2Times()
        }, GeneratedVectors.proofWord64X2)
        "packWord32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(8) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedWord32X8Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedWord32X8Pack()
            b.endBlock()
        }, GeneratedVectors.proofWord32X8)
        "unpackWord32X8#" -> tupleExpression(GeneratedVectors.unpackedWord32X8) { e, destination ->
            e.builder.beginGeneratedWord32X8Unpack(destination[0], destination[1], destination[2], destination[3], destination[4], destination[5], destination[6], destination[7])
            operands[0].emit(e)
            e.builder.endGeneratedWord32X8Unpack()
        }
        "broadcastWord32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord32X8Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedWord32X8Broadcast()
        }, GeneratedVectors.proofWord32X8)
        "plusWord32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord32X8Plus(); operands.forEach { it.emit(e) }; b.endGeneratedWord32X8Plus()
        }, GeneratedVectors.proofWord32X8)
        "minusWord32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord32X8Minus(); operands.forEach { it.emit(e) }; b.endGeneratedWord32X8Minus()
        }, GeneratedVectors.proofWord32X8)
        "timesWord32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedWord32X8Times(); operands.forEach { it.emit(e) }; b.endGeneratedWord32X8Times()
        }, GeneratedVectors.proofWord32X8)
        "packInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(8) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedInt32X8Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedInt32X8Pack()
            b.endBlock()
        }, GeneratedVectors.proofInt32X8)
        "unpackInt32X8#" -> tupleExpression(GeneratedVectors.unpackedInt32X8) { e, destination ->
            e.builder.beginGeneratedInt32X8Unpack(destination[0], destination[1], destination[2], destination[3], destination[4], destination[5], destination[6], destination[7])
            operands[0].emit(e)
            e.builder.endGeneratedInt32X8Unpack()
        }
        "broadcastInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X8Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X8Broadcast()
        }, GeneratedVectors.proofInt32X8)
        "plusInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X8Plus(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X8Plus()
        }, GeneratedVectors.proofInt32X8)
        "minusInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X8Minus(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X8Minus()
        }, GeneratedVectors.proofInt32X8)
        "timesInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X8Times(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X8Times()
        }, GeneratedVectors.proofInt32X8)
        "negateInt32X8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X8Negate(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X8Negate()
        }, GeneratedVectors.proofInt32X8)
        "packInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(16) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedInt32X16Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedInt32X16Pack()
            b.endBlock()
        }, GeneratedVectors.proofInt32X16)
        "unpackInt32X16#" -> tupleExpression(GeneratedVectors.unpackedInt32X16) { e, destination ->
            e.builder.beginGeneratedInt32X16Unpack(destination[0], destination[1], destination[2], destination[3], destination[4], destination[5], destination[6], destination[7], destination[8], destination[9], destination[10], destination[11], destination[12], destination[13], destination[14], destination[15])
            operands[0].emit(e)
            e.builder.endGeneratedInt32X16Unpack()
        }
        "broadcastInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X16Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X16Broadcast()
        }, GeneratedVectors.proofInt32X16)
        "plusInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X16Plus(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X16Plus()
        }, GeneratedVectors.proofInt32X16)
        "minusInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X16Minus(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X16Minus()
        }, GeneratedVectors.proofInt32X16)
        "timesInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X16Times(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X16Times()
        }, GeneratedVectors.proofInt32X16)
        "negateInt32X16#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt32X16Negate(); operands.forEach { it.emit(e) }; b.endGeneratedInt32X16Negate()
        }, GeneratedVectors.proofInt32X16)
        "timesInt64X2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedInt64X2Times(); operands.forEach { it.emit(e) }; b.endGeneratedInt64X2Times()
        }, GeneratedVectors.proofInt64X2)
        "negateFloatX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX4Negate(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX4Negate()
        }, GeneratedVectors.proofFloatX4)
        "divideFloatX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX4Divide(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX4Divide()
        }, GeneratedVectors.proofFloatX4)
        "negateDoubleX2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX2Negate(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX2Negate()
        }, GeneratedVectors.proofDoubleX2)
        "divideDoubleX2#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX2Divide(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX2Divide()
        }, GeneratedVectors.proofDoubleX2)
        "packFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(8) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedFloatX8Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedFloatX8Pack()
            b.endBlock()
        }, GeneratedVectors.proofFloatX8)
        "unpackFloatX8#" -> tupleExpression(GeneratedVectors.unpackedFloatX8) { e, destination ->
            e.builder.beginGeneratedFloatX8Unpack(destination[0], destination[1], destination[2], destination[3], destination[4], destination[5], destination[6], destination[7])
            operands[0].emit(e)
            e.builder.endGeneratedFloatX8Unpack()
        }
        "broadcastFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Broadcast()
        }, GeneratedVectors.proofFloatX8)
        "plusFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Plus(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Plus()
        }, GeneratedVectors.proofFloatX8)
        "minusFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Minus(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Minus()
        }, GeneratedVectors.proofFloatX8)
        "timesFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Times(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Times()
        }, GeneratedVectors.proofFloatX8)
        "negateFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Negate(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Negate()
        }, GeneratedVectors.proofFloatX8)
        "divideFloatX8#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedFloatX8Divide(); operands.forEach { it.emit(e) }; b.endGeneratedFloatX8Divide()
        }, GeneratedVectors.proofFloatX8)
        "packDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginBlock()
            val lanes = List(4) { b.createLocal() }
            operands[0].emitTuple(e, lanes)
            b.beginGeneratedDoubleX4Pack(); lanes.forEach(b::emitLoadLocal); b.endGeneratedDoubleX4Pack()
            b.endBlock()
        }, GeneratedVectors.proofDoubleX4)
        "unpackDoubleX4#" -> tupleExpression(GeneratedVectors.unpackedDoubleX4) { e, destination ->
            e.builder.beginGeneratedDoubleX4Unpack(destination[0], destination[1], destination[2], destination[3])
            operands[0].emit(e)
            e.builder.endGeneratedDoubleX4Unpack()
        }
        "broadcastDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Broadcast(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Broadcast()
        }, GeneratedVectors.proofDoubleX4)
        "plusDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Plus(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Plus()
        }, GeneratedVectors.proofDoubleX4)
        "minusDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Minus(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Minus()
        }, GeneratedVectors.proofDoubleX4)
        "timesDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Times(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Times()
        }, GeneratedVectors.proofDoubleX4)
        "negateDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Negate(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Negate()
        }, GeneratedVectors.proofDoubleX4)
        "divideDoubleX4#" -> ProvenExpression(Expression { e ->
            val b = e.builder
            b.beginGeneratedDoubleX4Divide(); operands.forEach { it.emit(e) }; b.endGeneratedDoubleX4Divide()
        }, GeneratedVectors.proofDoubleX4)
        else -> throw UnsupportedCore("Unsupported generated vector primitive $name")
    }
    // END GENERATED SIMD FAMILIES

    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        val fields = CoreFields(info)
        DataLayout(language, id, info["name"] as String, fields.storage, fields.referenceTypes)
    }
    /** The constructor adapter enforces strict fields before storing them. */
    private fun fieldIsEvaluated(id: String, index: Int): Boolean {
        val constructor = constructors.getValue(id)
        val lifted = (constructor["fieldLifted"] as? List<*>)?.getOrNull(index)
        val strict = (constructor["strictFields"] as? List<*>)?.getOrNull(index)
        return lifted == false || strict == true
    }

    private fun strictConstructorFields(id: String, arity: Int): BooleanArray {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        if ((info["kind"] ?: "boxed") != "boxed") throw UnsupportedCore("Unsupported constructor representation ${info["kind"]}: $id")
        if ((info["arity"] as Number).toInt() != arity) throw RuntimeFault("Constructor arity mismatch: $id")
        val strict = info["strictFields"] as? List<*> ?: throw RuntimeFault("Missing constructor strictness metadata: $id")
        val lifted = info["fieldLifted"] as? List<*> ?: throw RuntimeFault("Missing constructor representation metadata: $id")
        if (strict.size != arity || lifted.size != arity) throw RuntimeFault("Constructor metadata length mismatch: $id")
        return BooleanArray(arity) { i ->
            val strictField = strict[i] as? Boolean ?: throw RuntimeFault("Unknown constructor field strictness: $id")
            strictField && (lifted[i] as? Boolean ?: throw UnsupportedCore("Unknown strict constructor field levity: $id field $i"))
        }
    }
}

/** Immutable source sections can be reused by every replay; builder state cannot. */
private object BytecodeSources {
    fun begin(builder: BytecodeRootGen.Builder, section: SourceSection) {
        builder.beginSource(section.source)
        when {
            !section.isAvailable -> builder.beginSourceSectionUnavailable()
            section.hasCharIndex() -> builder.beginSourceSection(section.charIndex, section.charLength)
            section.hasColumns() -> builder.beginSourceSection(section.startLine, section.startColumn, section.endLine, section.endColumn)
            else -> builder.beginSourceSection(section.startLine)
        }
    }
    fun end(builder: BytecodeRootGen.Builder) {
        builder.endSourceSection()
        builder.endSource()
    }
}
