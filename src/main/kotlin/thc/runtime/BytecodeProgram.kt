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
class BytecodeProgram(private val language: Language, moduleData: Map<String, Any?>) : ExecutableProgram {
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
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    private val roots = arrayListOf<BytecodeRoot>()
    private var nextLocal = 0
    private var localJoinCount = 0

    private data class Local(val id: Int, val name: String, val primitive: Boolean,
                             val proof: CoreRepresentation = CoreRepresentation.UNKNOWN,
                             val cell: Boolean = false, val entry: BooleanArray? = null) {
        // The denoted value can be primitive while a pre-publication capture
        // still holds its recursive cell. Raw captures must retain that cell.
        val directLong: Boolean get() = !cell && proof.isLong && proof.evaluated
        val directFloat: Boolean get() = !cell && proof.isFloat && proof.evaluated
        val directDouble: Boolean get() = !cell && proof.isDouble && proof.evaluated
    }
    private class FunctionContext(val formalArity: Int, val entryStrict: BooleanArray = BooleanArray(formalArity)) {
        var inputLayout: ArgumentLayout? = null
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
                             val locals: List<Local>, val entryStrict: BooleanArray, val result: CoreRepresentation)
    private class JoinEmission(val selector: BytecodeLocal?, val next: BytecodeLabel?, val labels: List<BytecodeLabel>) {
        var emittedIndex = -1
    }
    private class Emission(val builder: BytecodeRootGen.Builder) {
        val locals = mutableMapOf<Int, BytecodeLocal>()
        var continueLabel: BytecodeLabel? = null
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
    private fun tupleSlots(shape: TupleShape, locals: List<BytecodeLocal>) =
        BytecodeTupleSlots(shape, locals.map(LocalAccessor::constantOf).toTypedArray())
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
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(bindings)
            CoreInputCalls.validate(bindings)
        }
        val scope = Scope(FunctionContext(0))
        val initializers = bindings.map { binding ->
            val expr = binding["expr"] as List<Any?>
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
    }

    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    override fun hostEntryTarget(arity: Int): RootCallTarget = hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
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
        "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkEvaluationsByLabel.toMap(),
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
                     entry: BooleanArray? = null): Local =
        Local(nextLocal++, name, !cell && (if (proof.present) proof.isLong else primitive), proof, cell, entry).also { scope.bindLocal(name, it) }
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
        context.captures = captureSources.map { bind(scope, it.name, it.primitive, it.proof, it.cell, it.entry) }
        context.captureLayout = if (captureSources.isEmpty()) null else CaptureLayout(language, captureSources.map { it.primitive }.toBooleanArray(),
            captureSources.map { it.directLong }.toBooleanArray(),
            captureSources.map { if (it.cell) null else it.proof.referenceCarrier() }.toTypedArray(),
            captureSources.map { it.directFloat }.toBooleanArray(),
            captureSources.map { it.directDouble }.toBooleanArray())
        context.arguments = args.mapIndexed { index, arg ->
            val lifted = representation(arg)
            val proof = CoreRepresentations.binder(arg).copy(evaluated = !lifted || context.entryStrict[index])
            if (proof.isEmptyTuple) {
                if (lifted) throw RuntimeFault("Empty tuple formal cannot be lifted")
                scope.bindTuple(arg["id"] as String, proof, emptyList()); null
            } else if (arg["id"] in free) bind(scope, arg["id"] as String, !lifted && arg["coercion"] != true,
                proof) else null
        }
        val compiled = compile(expression, scope, true)
        if (compiled.loweredCase && context.inputLayout == null) context.leadingCaseReturn = LeadingCaseReturn.discover(args, expression,
            resultProof, if (context.captureLayout == null) 1 else 2, free.intersect(argumentIds), context.captureLayout != null,
            ::dataLayout, sources, compiled.source)
        val body = ProvenExpression(compiled, compiled.proof.refine(resultProof).copy(evaluated = compiled.proof.evaluated))
        CoreRepresentations.requireNoVector(body.proof, "function result")
        context.tuple = if (body.proof.isTuple) TupleShape(body.proof, language) else null
        return FunctionSpec(build(label, context, body, forceResult = !body.proof.evaluated), context.captureLayout, captureSources)
    }

    private fun build(label: String, context: FunctionContext, body: Expression, forceResult: Boolean = true): RootCallTarget {
        val source = body.source
        val config = if (sources.enabled && sources.spanCount > 0) BytecodeConfig.WITH_SOURCE else BytecodeConfig.DEFAULT
        val root = BytecodeRootGen.create(language, config) { b ->
            source?.let { BytecodeSources.begin(b, it.section) }
            b.beginRoot()
            val e = Emission(b)
            b.emitEnterRoot(metrics)
            for (local in context.captures + context.arguments.filterNotNull()) {
                e.locals[local.id] = b.createLocal(local.name, if (local.primitive) "primitive" else "object")
            }
            context.captures.forEachIndexed { index, local ->
                b.beginStoreLocal(e.locals.getValue(local.id))
                if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
                b.emitLoadArgument(1)
                if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
                b.endStoreLocal()
            }
            val offset = if (context.captureLayout == null) 1 else 2
            context.arguments.forEachIndexed { index, local -> if (local != null) {
                restoreArgument(e, local) { b.emitLoadArgument(ArgumentLayout.offset(context.inputLayout, index) + offset) }
            } }
            if (context.mayLoop) {
                b.beginWhile()
                b.emitLoadConstant(true)
                b.beginBlock()
                e.continueLabel = b.createLabel()
            }
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
        root.configureEntry(context.entryStrict, context.captureLayout != null)
        root.configureInput(context.inputLayout)
        root.configureLeadingCaseReturn(context.leadingCaseReturn)
        root.configureTupleResult(context.tuple)
        roots += root
        return root.callTarget
    }

    /** Choose checked reference identities while emitting code, never by a guest-time enum switch. */
    private fun restoreArgument(e: Emission, local: Local, value: () -> Unit) {
        val b = e.builder
        val reference = if (local.cell) null else local.proof.referenceCarrier()
        b.beginStoreLocal(e.locals.getValue(local.id))
        when {
            local.directLong -> { b.beginToLong(); value(); b.endToLong() }
            local.directFloat -> { b.beginToFloat(); value(); b.endToFloat() }
            local.directDouble -> { b.beginToDouble(); value(); b.endToDouble() }
            reference == DataValue::class.java -> { b.beginRequireData(); value(); b.endRequireData() }
            reference == Closure::class.java -> { b.beginRequireClosure(); value(); b.endRequireClosure() }
            reference == LiteralAddress::class.java -> { b.beginRequireAddress(); value(); b.endRequireAddress() }
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
    private fun force(value: Expression): Expression {
        if (value.proof.isTuple) return value
        if (value.proof.evaluated) return value
        return evaluated(ResultExpression { e, destination ->
            if (destination != null) value.emitTuple(e, destination) else {
                val b = e.builder
                val localValue = undecorated(value)
                if (localValue is LocalExpression && localValue.resolve) {
                    val local = e.locals.getValue(localValue.local.id)
                    b.beginForceLocal(metrics, local, localValue.local.cell)
                    b.emitLoadLocal(local)
                    b.endForceLocal()
                } else {
                    b.beginForceValue(metrics); value.emit(e); b.endForceValue()
                }
            }
        }).let { sourced(ProvenExpression(it, value.proof.copy(evaluated = true)), value.source) }
    }
    private fun requireClosure(value: Expression) = Expression { e ->
        e.builder.beginRequireClosure(); force(value).emit(e); e.builder.endRequireClosure()
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expression {
        val fn = function(label, emptyList(), expr, scope)
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
        val proof = CoreRepresentations.expression(expr)
        fun check(value: CoreRepresentation) {
            if (allowEmpty) CoreRepresentations.requireInput(value) else CoreRepresentations.requireScalar(value, "argument")
        }
        check(proof)
        val lexical = if (expr[0] == "var") scope.tuples[expr[1]]?.first ?: scope.locals[expr[1]]?.proof else null
        lexical?.let(::check)
        if (proof.isEmptyTuple || lexical?.isEmptyTuple == true) {
            if (declaredLifted) throw RuntimeFault("Empty tuple argument cannot be lifted")
            return compile(expr, scope, false).also {
                if (!it.proof.isEmptyTuple) throw RuntimeFault("Missing exact empty tuple argument proof")
            }
        }
        if (!lifted) return force(compile(expr, scope, false).also {
            CoreRepresentations.requireNoVector(it.proof, "argument")
        })
        if (expr[0] == "app" && ((expr.getOrNull(5) as? Boolean) ?: (expr.getOrNull(4) == true))) return compile(expr, scope, false)
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }
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
        "string-bytes" -> LiteralAddress.fromHex(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun constant(value: Any) = ProvenExpression(Expression { it.builder.emitLoadConstant(value) },
        CoreRepresentation(when (value) {
            is Long -> CoreKind.LONG; is Float -> CoreKind.FLOAT; is Double -> CoreKind.DOUBLE
            is LiteralAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
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

    private fun application(function: Expression, arguments: List<Expression>, scope: Scope, tail: Boolean): Expression {
        val context = scope.function
        val evaluatedArguments = arguments.map { it.proof.evaluated }.toBooleanArray()
        val inputLayout = ArgumentLayout.fromProofs(arguments.map { it.proof })
        val loop = tail && context.inputLayout == null && inputLayout == null && arguments.size <= context.formalArity && context.formalArity > 0
        // Even a root without a direct self-call can receive A -> B -> ... -> A.
        if (tail) context.mayLoop = true
        return evaluated(Expression { e ->
            val b = e.builder
            val reentryResult = if (tail) {
                b.beginBlock()
                b.createLocal("tail result", null).also { b.beginStoreLocal(it) }
            } else null
            if (inputLayout != null) {
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
                        b.beginForceValue(metrics)
                        if (index < prefix) {
                            b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied()
                        } else b.emitLoadLocal(args[index - prefix])
                        b.endForceValue()
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
                b.beginApply(arguments.size, true, metrics, evaluatedArguments)
                b.emitLoadLocal(fn)
                args.forEach { b.emitLoadLocal(it) }
                b.endApply()
                b.endConditional()
                b.endBlock()
            }
            if (reentryResult != null) {
                b.endStoreLocal()
                b.beginConditional()
                b.beginIsTailReentry(); b.emitLoadLocal(reentryResult); b.endIsTailReentry()
                b.beginBlock()
                // The original activation retains its bloom ancestry. Only the new
                // packet's environment and value arguments replace lexical locals.
                context.captures.forEachIndexed { index, local ->
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
                    b.beginTailArgument(1); b.emitLoadLocal(reentryResult); b.endTailArgument()
                    if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
                    b.endStoreLocal()
                }
                val offset = if (context.captureLayout == null) 1 else 2
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    restoreArgument(e, local) {
                        b.beginTailArgument(ArgumentLayout.offset(context.inputLayout, index) + offset); b.emitLoadLocal(reentryResult); b.endTailArgument()
                    }
                } }
                b.emitBranch(e.continueLabel!!)
                b.emitLoadConstant(Unit)
                b.endBlock()
                b.emitLoadLocal(reentryResult)
                b.endConditional()
                b.endBlock()
            }
        })
    }

    /** A join transfer is a parallel move into this activation followed by bytecode control flow. */
    private fun joinCall(target: JoinTarget, arguments: List<Expression>): Expression {
        if (arguments.size != target.parameters.size) throw UnsupportedCore("Local join is not exactly saturated")
        return ProvenExpression(ResultExpression { e, destination ->
            val b = e.builder
            val region = e.joins[target.region] ?: throw RuntimeFault("Local join escapes its owning activation")
            b.beginBlock()
            b.emitJoinTransfer(metrics)
            // Saving all operands first is required for swaps and mutually recursive joins.
            val temporaries = arguments.mapIndexed { index, argument ->
                b.createLocal("join operand $index", null).also { temporary ->
                    b.beginStoreLocal(temporary)
                    argument.emit(e)
                    b.endStoreLocal()
                }
            }
            target.locals.forEachIndexed { index, local ->
                restoreArgument(e, local) { b.emitLoadLocal(temporaries[index]) }
            }
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
        definitions.forEach { definition ->
            definition.parameters.forEach { CoreRepresentations.requireScalar(CoreRepresentations.binder(it), "join argument") }
            val formals = definition.parameters.map { it["id"] as String }.toSet()
            if ((freeVariables(definition.body) - formals - shadowed).any { it in scope.tuples })
                throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple (join capture)")
        }
        val region = JoinRegion()
        val local = scope.child()
        val targets = definitions.mapIndexed { index, definition ->
            val entryStrict = CoreEntries.join(definition)
            val parameters = definition.parameters.mapIndexed { parameterIndex, parameter ->
                // Join formal names have lexical scope only in their own body.
                val proof = CoreRepresentations.binder(parameter).copy(evaluated = !representation(parameter) || entryStrict[parameterIndex])
                Local(nextLocal++, parameter["id"] as String,
                    if (proof.present) proof.isLong else !representation(parameter) && parameter["coercion"] != true, proof)
            }
            JoinTarget(region, index, definition.parameters, parameters, entryStrict, definition.result).also { local.bindJoin(definition.id, it) }
        }
        localJoinCount += targets.size
        val bodies = definitions.mapIndexed { index, definition ->
            val bodyScope = (if (recursive) local else scope).child()
            targets[index].locals.forEach { bodyScope.bindLocal(it.name, it) }
            val body = compile(definition.body, bodyScope.withSource(sources.binding(definition.binding, scope.source)), tail)
            CoreRepresentations.requireNoVector(body.proof, "join result")
            ProvenExpression(body, body.proof.refine(definition.result.copy(evaluated = false)))
        }
        val entry = compile(expression, local, tail)
        CoreRepresentations.requireNoVector(entry.proof, "join result")
        val proof = entry.proof.refine(CoreRepresentations.expression(expression).copy(evaluated = false))
        bodies.forEach { TupleShape.requireCompatible(proof, it.proof) }
        return ProvenExpression(ResultExpression { e, destination ->
            if (proof.isTuple != (destination != null)) throw RuntimeFault("Join result destination disagrees with its representation")
            val b = e.builder
            b.beginBlock()
            val result = if (destination == null) b.createLocal("join result", null) else null
            val selector = if (recursive) b.createLocal("join selector", "primitive") else null
            val exit = b.createLabel()
            targets.flatMap { it.locals }.forEach { e.locals[it.id] = b.createLocal(it.name, if (it.primitive) "primitive" else "object") }
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
                b.endBlock()
                b.endWhile()
            }
            b.emitLabel(exit)
            if (result != null) b.emitLoadLocal(result)
            b.endBlock()
            e.joins.remove(region)
            targets.flatMap { it.locals }.forEach { e.locals.remove(it.id) }
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
            if (expr[1] in listOf("int8", "word8", "int16", "word16", "int32", "word32")) ProvenExpression(it, CoreRepresentations.narrowLiteralProof(expr)) else it
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
            if (fn[0] == "prim" && fn[1] in CoreVectors.operations) {
                val name = fn[1] as String
                CoreVectors.validate(name, args.map(CoreVectors::argumentProof), tupleProof)
                CoreVectors.validateFlags(flags)
                vectorPrimitive(name, args.map { compile(it, scope, false) })
            } else if (fn[0] == "prim" && MutVarOp.named(fn[1] as String) != null) {
                val operation = MutVarOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    if (operation == MutVarOp.NEW) e.builder.beginNewMutVar(destination[0])
                    else e.builder.beginReadMutVar(destination[0])
                    operands.forEach { it.emit(e) }
                    if (operation == MutVarOp.NEW) e.builder.endNewMutVar() else e.builder.endReadMutVar()
                } else ProvenExpression(Expression { e ->
                    e.builder.beginWriteMutVar(); operands.forEach { it.emit(e) }; e.builder.endWriteMutVar()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ArrayOp.named(fn[1] as String) != null) {
                val operation = ArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.mapIndexed { index, value -> argument(value, scope, flags[index] as Boolean) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        ArrayOp.NEW -> e.builder.beginNewArray(destination[0])
                        ArrayOp.READ -> e.builder.beginReadArray(destination[0])
                        ArrayOp.FREEZE -> e.builder.beginFreezeArray(destination[0])
                        ArrayOp.INDEX -> e.builder.beginIndexArray(destination[0])
                        else -> error("Not a tuple array operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ArrayOp.NEW -> e.builder.endNewArray()
                        ArrayOp.READ -> e.builder.endReadArray()
                        ArrayOp.FREEZE -> e.builder.endFreezeArray()
                        ArrayOp.INDEX -> e.builder.endIndexArray()
                        else -> error("Not a tuple array operation")
                    }
                } else ProvenExpression(Expression { e ->
                    e.builder.beginWriteArray(); operands.forEach { it.emit(e) }; e.builder.endWriteArray()
                }, tupleProof.copy(evaluated = true))
            } else if (fn[0] == "prim" && ByteArrayOp.named(fn[1] as String) != null) {
                val operation = ByteArrayOp.named(fn[1] as String)!!
                operation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { compile(it, scope, false) }
                if (operation.tuple) tupleExpression(tupleProof) { e, destination ->
                    when (operation) {
                        ByteArrayOp.NEW -> e.builder.beginNewByteArray(destination[0])
                        ByteArrayOp.FREEZE -> e.builder.beginFreezeByteArray(destination[0])
                        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD -> e.builder.beginReadIntArray(destination[0])
                        ByteArrayOp.READ_DOUBLE -> e.builder.beginReadDoubleArray(destination[0])
                        ByteArrayOp.READ_FLOAT -> e.builder.beginReadFloatArray(destination[0])
                        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16 ->
                            e.builder.beginReadInt16Array(operation == ByteArrayOp.READ_WORD16, destination[0])
                        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32 ->
                            e.builder.beginReadInt32Array(operation == ByteArrayOp.READ_WORD32, destination[0])
                        else -> error("Scalar ByteArray operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ByteArrayOp.NEW -> e.builder.endNewByteArray()
                        ByteArrayOp.FREEZE -> e.builder.endFreezeByteArray()
                        ByteArrayOp.READ_INT, ByteArrayOp.READ_WORD -> e.builder.endReadIntArray()
                        ByteArrayOp.READ_DOUBLE -> e.builder.endReadDoubleArray()
                        ByteArrayOp.READ_FLOAT -> e.builder.endReadFloatArray()
                        ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16 -> e.builder.endReadInt16Array()
                        ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32 -> e.builder.endReadInt32Array()
                        else -> error("Scalar ByteArray operation")
                    }
                } else ProvenExpression(Expression { e ->
                    when (operation) {
                        ByteArrayOp.COPY -> e.builder.beginCopyByteArray()
                        ByteArrayOp.WRITE -> e.builder.beginWriteByteArray()
                        ByteArrayOp.SIZE -> e.builder.beginSizeByteArray()
                        ByteArrayOp.INDEX -> e.builder.beginIndexByteArray()
                        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD -> e.builder.beginWriteIntArray()
                        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD -> e.builder.beginIndexIntArray()
                        ByteArrayOp.WRITE_DOUBLE -> e.builder.beginWriteDoubleArray()
                        ByteArrayOp.INDEX_DOUBLE -> e.builder.beginIndexDoubleArray()
                        ByteArrayOp.WRITE_FLOAT -> e.builder.beginWriteFloatArray()
                        ByteArrayOp.INDEX_FLOAT -> e.builder.beginIndexFloatArray()
                        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16 -> e.builder.beginWriteInt16Array()
                        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32 -> e.builder.beginWriteInt32Array()
                        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16 ->
                            e.builder.beginIndexInt16Array(operation == ByteArrayOp.INDEX_WORD16)
                        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32 ->
                            e.builder.beginIndexInt32Array(operation == ByteArrayOp.INDEX_WORD32)
                        else -> error("Tuple ByteArray operation")
                    }
                    operands.forEach { it.emit(e) }
                    when (operation) {
                        ByteArrayOp.COPY -> e.builder.endCopyByteArray()
                        ByteArrayOp.WRITE -> e.builder.endWriteByteArray()
                        ByteArrayOp.SIZE -> e.builder.endSizeByteArray()
                        ByteArrayOp.INDEX -> e.builder.endIndexByteArray()
                        ByteArrayOp.WRITE_INT, ByteArrayOp.WRITE_WORD -> e.builder.endWriteIntArray()
                        ByteArrayOp.INDEX_INT, ByteArrayOp.INDEX_WORD -> e.builder.endIndexIntArray()
                        ByteArrayOp.WRITE_DOUBLE -> e.builder.endWriteDoubleArray()
                        ByteArrayOp.INDEX_DOUBLE -> e.builder.endIndexDoubleArray()
                        ByteArrayOp.WRITE_FLOAT -> e.builder.endWriteFloatArray()
                        ByteArrayOp.INDEX_FLOAT -> e.builder.endIndexFloatArray()
                        ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16 -> e.builder.endWriteInt16Array()
                        ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32 -> e.builder.endWriteInt32Array()
                        ByteArrayOp.INDEX_INT16, ByteArrayOp.INDEX_WORD16 -> e.builder.endIndexInt16Array()
                        ByteArrayOp.INDEX_INT32, ByteArrayOp.INDEX_WORD32 -> e.builder.endIndexInt32Array()
                        else -> error("Tuple ByteArray operation")
                    }
                }, tupleProof.copy(evaluated = true))
            } else if (tupleOperation != null) {
                tupleOperation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                val operands = args.map { argument(it, scope, false) }
                tupleExpression(tupleProof) { e, destination ->
                    e.builder.beginTupleArithmetic(tupleOperation, destination[0], destination[1])
                    operands.forEach { it.emit(e) }
                    e.builder.endTupleArithmetic()
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
                            e.builder.beginStoreLocal(destination[offset]); operand.emit(e); e.builder.endStoreLocal()
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
                    primitive(fn[1] as String, operands)
                }
                strict != null -> construct(dataLayout(fn[1] as String), operands)
                else -> if (tupleProof.isTuple) tupleApplication(TupleShape(tupleProof, language), compile(fn, scope, false), operands, scope, tail)
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
                    CoreRepresentations.binder(it).copy(evaluated = false), cell = recursive, entry = CoreEntries.binding(it)) }
                val rhs = group.map {
                    val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
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
            val local = scope.child()
            val scrutineeExpr = expr[1] as List<Any?>
            val scrutinee = force(compile(scrutineeExpr, scope, false))
            val binderProof = scrutinee.proof.refine(CoreRepresentations.caseBinder(expr)).copy(evaluated = true)
            if (binderProof.isTuple) tupleCase(expr, scrutinee, binderProof, local, tail) else {
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
            CoreRepresentations.validateFloatingCaseResult(resultProof, alternatives.map { it.body.proof })
            val mergedProof = CoreVectors.caseResult(alternatives.map { it.body.proof })?.refine(resultProof)
                ?: resultProof.copy(evaluated = alternatives.all { it.body.proof.evaluated })
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
                if (inputLayout == null) {
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
                if (inputLayout == null) {
                    b.beginTailApplyTuple(tupleSlots(shape, destination), arguments.size, metrics)
                    requireClosure(function).emit(e); arguments.forEach { it.emit(e) }; b.endTailApplyTuple()
                } else compactArguments(e, function, arguments, inputLayout) { fn, values ->
                    b.beginTailApplyCompactTuple(tupleSlots(shape, destination), inputLayout, metrics)
                    b.emitLoadLocal(fn); values.forEach(b::emitLoadLocal); b.endTailApplyCompactTuple()
                }
                b.endStoreLocal()
                b.beginIfThenElse()
                b.beginIsTailReentry(); b.emitLoadLocal(result); b.endIsTailReentry()
                b.beginBlock()
                context.captures.forEachIndexed { index, local ->
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    if (local.directLong) b.beginCaptureReadLong(context.captureLayout!!, index) else b.beginCaptureRead(context.captureLayout!!, index)
                    b.beginTailArgument(1); b.emitLoadLocal(result); b.endTailArgument()
                    if (local.directLong) b.endCaptureReadLong() else b.endCaptureRead()
                    b.endStoreLocal()
                }
                val offset = if (context.captureLayout == null) 1 else 2
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    restoreArgument(e, local) { b.beginTailArgument(offset + ArgumentLayout.offset(context.inputLayout, index)); b.emitLoadLocal(result); b.endTailArgument() }
                } }
                b.emitBranch(e.continueLabel!!)
                b.endBlock()
                b.beginBlock(); b.endBlock()
                b.endIfThenElse()
                b.endBlock()
            }
        }
    }

    private fun vectorPrimitive(name: String, operands: List<Expression>): Expression = when (name) {
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
            "plusFloat#" -> "FloatAdd"
            "minusFloat#" -> "FloatSubtract"
            "timesFloat#" -> "FloatMultiply"
            "divideFloat#" -> "FloatDivide"
            "negateFloat#" -> "FloatNegate"
            "sqrtFloat#" -> "FloatSqrt"
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
            "==##" -> "DoubleEqual"
            "/=##" -> "DoubleNotEqual"
            "<##" -> "DoubleLess"
            "<=##" -> "DoubleLessEqual"
            ">##" -> "DoubleGreater"
            ">=##" -> "DoubleGreaterEqual"
            "int2Float#" -> "IntToFloat"
            "int2Double#" -> "IntToDouble"
            "float2Int#" -> "FloatToInt"
            "double2Int#" -> "DoubleToInt"
            "float2Double#" -> "FloatToDouble"
            "double2Float#" -> "DoubleToFloat"
            else -> return null
        }
        val unary = operation in setOf("FloatNegate", "DoubleNegate", "FloatSqrt", "DoubleSqrt", "IntToFloat", "IntToDouble", "FloatToInt", "DoubleToInt", "FloatToDouble", "DoubleToFloat")
        if (args.size != if (unary) 1 else 2) throw RuntimeFault("Primitive arity mismatch: $name")
        val kind = when (operation) {
            "FloatAdd", "FloatSubtract", "FloatMultiply", "FloatDivide", "FloatNegate", "FloatSqrt", "IntToFloat", "DoubleToFloat" -> CoreKind.FLOAT
            "DoubleAdd", "DoubleSubtract", "DoubleMultiply", "DoubleDivide", "DoubleNegate", "DoubleSqrt", "IntToDouble", "FloatToDouble" -> CoreKind.DOUBLE
            else -> CoreKind.LONG
        }
        return ProvenExpression(Expression { e ->
            val b = e.builder
            when (operation) {
                "FloatAdd" -> b.beginFloatAdd()
                "FloatSubtract" -> b.beginFloatSubtract()
                "FloatMultiply" -> b.beginFloatMultiply()
                "FloatDivide" -> b.beginFloatDivide()
                "FloatNegate" -> b.beginFloatNegate()
                "FloatSqrt" -> b.beginFloatSqrt()
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
                "IntToFloat" -> b.beginIntToFloat()
                "IntToDouble" -> b.beginIntToDouble()
                "FloatToInt" -> b.beginFloatToInt()
                "DoubleToInt" -> b.beginDoubleToInt()
                "FloatToDouble" -> b.beginFloatToDouble()
                "DoubleToFloat" -> b.beginDoubleToFloat()
            }
            args.forEach { it.emit(e) }
            when (operation) {
                "FloatAdd" -> b.endFloatAdd()
                "FloatSubtract" -> b.endFloatSubtract()
                "FloatMultiply" -> b.endFloatMultiply()
                "FloatDivide" -> b.endFloatDivide()
                "FloatNegate" -> b.endFloatNegate()
                "FloatSqrt" -> b.endFloatSqrt()
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
                "IntToFloat" -> b.endIntToFloat()
                "IntToDouble" -> b.endIntToDouble()
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
            "narrow8Int#", "intToInt8#", "int8ToInt#" -> "Narrow8"
            "narrow16Int#", "intToInt16#", "int16ToInt#" -> "Narrow16"
            "narrow32Int#", "intToInt32#", "int32ToInt#" -> "Narrow32"
            "wordToWord8#", "word8ToWord#", "wordToWord16#", "word16ToWord#", "wordToWord32#", "word32ToWord#" -> "NarrowWord"
            "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> "Identity"
            "raise#" -> "Raise"
            "plusAddr#" -> "AddressPlus"
            "indexCharOffAddr#" -> "AddressIndexChar"
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
                "CountLeadingZeros" -> b.beginCountLeadingZeros()
                "CountTrailingZeros" -> b.beginCountTrailingZeros(); "PopulationCount" -> b.beginPopulationCount()
                "ShiftLeft" -> b.beginShiftLeft(); "ShiftRight" -> b.beginShiftRight(); "ShiftRightUnsigned" -> b.beginShiftRightUnsigned()
                "Narrow8" -> b.beginNarrow8(); "Narrow16" -> b.beginNarrow16(); "Narrow32" -> b.beginNarrow32()
                "NarrowWord" -> b.beginNarrowWord(wordMask)
                "Raise" -> b.beginRaise(); "AddressPlus" -> b.beginAddressPlus(); "AddressIndexChar" -> b.beginAddressIndexChar()
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
                "CountLeadingZeros" -> b.endCountLeadingZeros()
                "CountTrailingZeros" -> b.endCountTrailingZeros(); "PopulationCount" -> b.endPopulationCount()
                "ShiftLeft" -> b.endShiftLeft(); "ShiftRight" -> b.endShiftRight(); "ShiftRightUnsigned" -> b.endShiftRightUnsigned()
                "Narrow8" -> b.endNarrow8(); "Narrow16" -> b.endNarrow16(); "Narrow32" -> b.endNarrow32()
                "NarrowWord" -> b.endNarrowWord()
                "Raise" -> b.endRaise(); "AddressPlus" -> b.endAddressPlus(); "AddressIndexChar" -> b.endAddressIndexChar()
            }
        })
    }

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
