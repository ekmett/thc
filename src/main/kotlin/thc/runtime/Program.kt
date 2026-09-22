@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. Haskell thunks
 * supply the additional lazy update/blackhole protocol. One guest thread;
 * arbitrary non-tail recursion still uses the host stack. */
open class RuntimeFault(message: String) : RuntimeException(message)
/** A known implementation gap, distinct from malformed Core or runtime errors. */
internal class UnsupportedCore(message: String) : RuntimeFault(message)
internal fun fault(message: String): Nothing {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault(message)
}
/** Cadenza's recursive indirection: captured by identity, initialized once. */
internal class RecCell {
    var initialized = false
    var value: Any? = null
}
/** Program linkage is fixed before guest execution; CAF contents remain lazy. */
private class GlobalBinding(val name: String) {
    @CompilationFinal private var initialized = false
    @CompilationFinal private var value: Any? = null
    fun initialize(value: Any?) { check(!initialized); this.value = value; initialized = true }
    fun read(): Any? {
        if (!initialized) fault("Uninitialized global binding")
        return value
    }
}
internal class Thunk(val target: RootCallTarget, var environment: CapturedFrame?) {
    var state = 0
    var value: Any? = null
}
internal class Metrics(val enabled: Boolean) {
    val thunkEvaluationsByLabel = linkedMapOf<String, Long>()
    @CompilerDirectives.TruffleBoundary fun recordThunk(label: String) {
        thunkEvaluationsByLabel[label] = (thunkEvaluationsByLabel[label] ?: 0L) + 1L
    }
    var compiledEntries = 0L
    var thunkEvaluations = 0L
    var thunkHits = 0L
    var blackholes = 0L
    var directCacheMisses = 0L
    var indirectCalls = 0L
    var tailBounces = 0L
    var papAllocations = 0L
    var unsupportedTraps = 0L
}
internal abstract class Expr : Node() {
    abstract fun execute(frame: VirtualFrame): Any?
    open fun executeLong(frame: VirtualFrame): Long = execute(frame) as? Long ?: fault("Expected primitive Long")
}
private class Literal(private val value: Any?) : Expr() {
    override fun execute(frame: VirtualFrame) = value
    override fun executeLong(frame: VirtualFrame) = value as? Long ?: fault("Expected primitive literal")
}
private class LocalRead(private val slot: Int) : Expr() {
    override fun execute(frame: VirtualFrame): Any? {
        val value = FrameAccess.read(frame, slot)
        if (value !is RecCell) return value ?: fault("Uninitialized local binding")
        if (!value.initialized) fault("Recursive binding read before initialization")
        return value.value
    }
}
private class GlobalRead(private val binding: GlobalBinding) : Expr() {
    override fun execute(frame: VirtualFrame) = binding.read()
}
private class MakeClosure(private val target: RootCallTarget, private val arity: Int,
                          private val captureLayout: CaptureLayout?,
                          @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    // Cadenza's closed-lambda optimization: immutable code needs no allocation.
    private val constantClosure = if (captureLayout == null) Closure(environment = null, arity = arity, target = target) else null
    override fun execute(frame: VirtualFrame): Any = constantClosure ?: Closure(
        environment = captureLayout!!.capture(frame, captures), arity = arity, target = target)
}
private class Delay(private val target: RootCallTarget, private val captureLayout: CaptureLayout?,
                    @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    override fun execute(frame: VirtualFrame): Any = Thunk(target, captureLayout?.capture(frame, captures))
}
internal class Force(private val metrics: Metrics) : Node() {
    @Child private var calls = ThunkTargetCache(metrics)
    @Child private var trampoline = TailCallLoop(metrics)
    private val tailCallProfile = BranchProfile.create()
    @CompilationFinal private var seenThunk = false
    fun execute(frame: VirtualFrame, original: Any?): Any? {
        if (!seenThunk) {
            if (original !is Thunk) return original
            CompilerDirectives.transferToInterpreterAndInvalidate()
            seenThunk = true
        }
        var value = original
        while (value is Thunk) {
            when (value.state) {
                2 -> { if (metrics.enabled) metrics.thunkHits++; value = value.value }
                3 -> {
                    // Failed thunks rethrow on the cold interpreter path. A Kotlin
                    // non-null cast here otherwise pulls NPE stack-trace machinery
                    // into every compiled forcing site, including successful ones.
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    throw (value.value as? Throwable ?: fault("Invalid failed thunk"))
                }
                1 -> { if (metrics.enabled) metrics.blackholes++; fault("Blackhole: cyclic thunk entered while evaluating") }
                else -> {
                    val thunk = value
                    thunk.state = 1
                    if (metrics.enabled) { metrics.thunkEvaluations++; metrics.recordThunk(thunk.target.rootNode.name) }
                    try {
                        val environment = thunk.environment
                        val result = try { calls.call(thunk.target, environment) }
                        catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
                        if (result is Thunk) fault("Thunk target violated WHNF convention")
                        thunk.value = result
                        thunk.environment = null
                        thunk.state = 2
                        value = result
                    } catch (e: GuestException) {
                        thunk.value = e; thunk.environment = null; thunk.state = 3; throw e
                    } catch (e: RuntimeFault) {
                        thunk.value = e; thunk.environment = null; thunk.state = 3; throw e
                    } catch (e: Throwable) {
                        thunk.value = null; thunk.state = 0; throw e
                    }
                }
            }
        }
        return value
    }
}
private class Evaluate(@field:Child private var value: Expr, metrics: Metrics) : Expr() {
    @Child private var force = Force(metrics)
    override fun execute(frame: VirtualFrame) = force.execute(frame, value.execute(frame))
}
private class Application(@field:Child private var function: Expr,
                          @field:Children private var arguments: Array<Expr>, tail: Boolean, metrics: Metrics) : Expr() {
    @Child private var force = Force(metrics)
    @Child private var dispatch = Dispatch.create(arguments.size, tail, metrics)
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val fn = force.execute(frame, function.execute(frame)) as? Closure ?: fault("Application of a non-function")
        val values = arrayOfNulls<Any>(arguments.size)
        for (i in arguments.indices) values[i] = arguments[i].execute(frame)
        return dispatch.execute(frame, fn, values)
    }
}
private class Let(@field:CompilationFinal(dimensions = 1) private val slots: IntArray,
                  @field:Children private var rhs: Array<Expr>, @field:Child private var body: Expr,
                  private val recursive: Boolean) : Expr() {
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        if (recursive) {
            // A closure captures these cells, never a mutable activation frame.
            for (slot in slots) FrameAccess.write(frame, slot, RecCell())
            for (i in slots.indices) {
                val cell = FrameAccess.read(frame, slots[i]) as? RecCell ?: fault("Invalid recursive cell")
                cell.value = rhs[i].execute(frame)
                cell.initialized = true
            }
            // Existing recursive captures retain the cells; the let body and
            // closures built after publication read the values directly.
            // Publish only after every RHS has captured the whole group.
            for (slot in slots) {
                val cell = FrameAccess.read(frame, slot) as? RecCell ?: fault("Invalid recursive cell")
                FrameAccess.write(frame, slot, cell.value)
            }
        } else for (i in slots.indices) FrameAccess.write(frame, slots[i], rhs[i].execute(frame))
        return body.execute(frame)
    }
}
private const val DEFAULT_ALTERNATIVE = 0
private const val DATA_ALTERNATIVE = 1
private const val LITERAL_ALTERNATIVE = 2

private class Alternative(val kind: Int, val value: Any?,
                          @field:CompilationFinal(dimensions = 1) val fields: IntArray,
                          @field:Child var body: Expr) : Node()
private class Case(@field:Child private var scrutinee: Expr, private val binderSlot: Int,
                   @field:Children private var alternatives: Array<Alternative>, metrics: Metrics) : Expr() {
    @Child private var force = Force(metrics)
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val value = force.execute(frame, scrutinee.execute(frame))
        FrameAccess.write(frame, binderSlot, value)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            val matches = when (alt.kind) {
                DATA_ALTERNATIVE -> value is DataValue && value.layout === alt.value
                LITERAL_ALTERNATIVE -> value == alt.value
                else -> false
            }
            if (matches) return runAlternative(frame, value, alt)
        }
        return runAlternative(frame, value, fallback ?: fault("Non-exhaustive Core case"))
    }
    @ExplodeLoop private fun runAlternative(frame: VirtualFrame, value: Any?, alt: Alternative): Any? {
        if (alt.kind == DATA_ALTERNATIVE) {
            val data = value as? DataValue ?: fault("Invalid constructor case")
            val layout = alt.value as? DataLayout ?: fault("Invalid constructor alternative")
            for (i in alt.fields.indices) layout.restore(data, i, frame, alt.fields[i])
        }
        return alt.body.execute(frame)
    }
}
private class Construct(private val layout: DataLayout,
                        @field:Children private var fields: Array<Expr>) : Expr() {
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any {
        val values = arrayOfNulls<Any>(fields.size)
        for (i in fields.indices) values[i] = fields[i].execute(frame)
        return layout.create(values)
    }
}
private class Primitive(private val name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init {
        val arity = when (name) {
            "negateInt#", "not#", "notI#", "int2Word#", "word2Int#", "ord#", "chr#",
            "narrow8Int#", "narrow16Int#", "narrow32Int#" -> 1
            "+#", "plusWord#", "-#", "minusWord#", "*#", "timesWord#", "quotInt#", "remInt#",
            "==#", "eqWord#", "eqChar#", "/=#", "neWord#", "neChar#", "<#", "ltChar#", "<=#", "leChar#",
            ">#", "gtChar#", ">=#", "geChar#", "and#", "andI#", "or#", "orI#", "xor#", "xorI#",
            "uncheckedIShiftL#", "uncheckedShiftL#", "uncheckedIShiftRA#", "uncheckedIShiftRL#", "uncheckedShiftRL#" -> 2
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        if (arguments.size != arity) throw RuntimeFault("Primitive arity mismatch: $name")
    }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val x = arguments[0].executeLong(frame)
        val y = if (arguments.size == 2) arguments[1].executeLong(frame) else 0L
        fun b(value: Boolean) = if (value) 1L else 0L
        return when (name) {
            "+#", "plusWord#" -> x + y
            "-#", "minusWord#" -> x - y
            "*#", "timesWord#" -> x * y
            "negateInt#" -> -x
            "quotInt#" -> x / y
            "remInt#" -> x % y
            "==#", "eqWord#", "eqChar#" -> b(x == y)
            "/=#", "neWord#", "neChar#" -> b(x != y)
            "<#", "ltChar#" -> b(x < y)
            "<=#", "leChar#" -> b(x <= y)
            ">#", "gtChar#" -> b(x > y)
            ">=#", "geChar#" -> b(x >= y)
            "and#", "andI#" -> x and y
            "or#", "orI#" -> x or y
            "xor#", "xorI#" -> x xor y
            "not#", "notI#" -> x.inv()
            "uncheckedIShiftL#", "uncheckedShiftL#" -> x shl y.toInt()
            "uncheckedIShiftRA#" -> x shr y.toInt()
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> x ushr y.toInt()
            "narrow8Int#" -> x.toByte().toLong()
            "narrow16Int#" -> x.toShort().toLong()
            "narrow32Int#" -> x.toInt().toLong()
            "int2Word#", "word2Int#", "ord#", "chr#" -> x
            else -> fault("Unsupported primitive")
        }
    }
}
private class FunctionBody(@field:Child private var expression: Expr, metrics: Metrics) : Node() {
    @Child private var force = Force(metrics)
    fun execute(frame: VirtualFrame): Any? = force.execute(frame, expression.execute(frame))
}
private class SelfRepeater(@field:Child private var body: FunctionBody) : Node(), RepeatingNode {
    fun once(frame: VirtualFrame): Any? = body.execute(frame)
    override fun executeRepeating(frame: VirtualFrame): Boolean = error("value loop")
    override fun executeRepeatingWithValue(frame: VirtualFrame): Any? = try { once(frame) }
    catch (tail: TailCall) {
        val root = rootNode as FunctionRoot
        if (!root.isSelf(tail.target)) throw tail
        root.buildFrame(tail.args, frame)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
}
internal class FunctionRoot(language: TruffleLanguage<*>?, descriptor: FrameDescriptor, private val label: String,
                            private val captureLayout: CaptureLayout?,
                            @field:CompilationFinal(dimensions = 1) private val environmentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentIndices: IntArray,
                            body: Expr, private val metrics: Metrics) : RootNode(language, descriptor) {
    private val bodyIdentity = Any()
    @field:CompilationFinal private var hasSelfTail = false
    private val tailCallProfile = BranchProfile.create()
    val mask = System.identityHashCode(bodyIdentity).let { h ->
        (1L shl (h and 63)) or (1L shl ((h ushr 6) and 63)) or (1L shl ((h ushr 12) and 63)) or
            (1L shl ((h ushr 18) and 63)) or (1L shl ((h ushr 24) and 63))
    }
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(SelfRepeater(FunctionBody(body, metrics)))
    fun isSelf(target: RootCallTarget) = (target.rootNode as? FunctionRoot)?.bodyIdentity === bodyIdentity
    @ExplodeLoop fun buildFrame(arguments: Array<Any?>, frame: VirtualFrame) {
        val offset = if (captureLayout == null) 1 else 2
        for (i in argumentSlots.indices) FrameAccess.write(frame, argumentSlots[i], arguments[argumentIndices[i] + offset])
        if (captureLayout != null) {
            val environment = arguments[1] as? CapturedFrame ?: fault("Invalid captured frame")
            for (i in environmentSlots.indices) captureLayout.restore(environment, i, frame, environmentSlots[i])
        }
    }
    override fun execute(frame: VirtualFrame): Any? {
        if (metrics.enabled && CompilerDirectives.inCompiledCode()) metrics.compiledEntries++
        frame.setLong(FrameLayout.BLOOM_FILTER, (frame.arguments[0] as? Long ?: fault("Invalid bloom argument")) or mask)
        buildFrame(frame.arguments, frame)
        // Non-looping roots retain entry argument facts. Once self recursion
        // is observed, PE selects only the loop body instead of duplicating it.
        if (hasSelfTail) return loop.execute(frame)
        return try { (loop.repeatingNode as SelfRepeater).once(frame) }
        catch (tail: TailCall) {
            tailCallProfile.enter()
            if (!isSelf(tail.target)) throw tail
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            // Keep this frame's bloom ancestry and restore the new captures.
            buildFrame(tail.args, frame)
            loop.execute(frame)
        }
    }
    override fun getName() = label
    override fun toString() = label
    override fun isCloningAllowed() = true
}
private class EntryRoot(language: TruffleLanguage<*>?, private val arity: Int, metrics: Metrics) : RootNode(language, FrameLayout().build()) {
    @Child private var dispatch = Dispatch.create(arity, false, metrics)
    @Child private var force = Force(metrics)
    override fun execute(frame: VirtualFrame): Any? {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val value = force.execute(frame, frame.arguments[0])
        if (arity == 0) return value
        val fn = value as? Closure ?: fault("Application of a non-function")
        val args = frame.arguments[1] as? Array<Any?> ?: fault("Invalid host arguments")
        return force.execute(frame, dispatch.execute(frame, fn, args))
    }
    override fun getName() = "THC host entry/$arity"
}
private data class Local(val slot: Int, val primitive: Boolean)
private class Scope(val layout: FrameLayout, val locals: MutableMap<String, Local> = linkedMapOf()) {
    fun child() = Scope(layout.scope(), LinkedHashMap(locals))
    fun bind(id: String, primitive: Boolean): Local = Local(layout.bind(id), primitive).also { locals[id] = it }
}
private data class FunctionSpec(val target: RootCallTarget, val captureLayout: CaptureLayout?, val captures: IntArray)

/** Exported GHC Core lowers lexical bindings to indexed frame slots, as Cadenza does. */
class Program(private val language: TruffleLanguage<*>?, moduleData: Map<String, Any?>) {
    private val metrics = Metrics(moduleData["instrument"] != false)
    private val diagnosticUnsupported = moduleData["diagnosticUnsupported"] == true
    private val deferredUnsupported = linkedSetOf<String>()
    private val bindings = moduleData["bindings"] as? List<Map<String, Any?>> ?: throw RuntimeFault("Missing bindings")
    private val constructors = (moduleData["constructors"] as? List<Map<String, Any?>> ?: emptyList()).associateBy { it["id"] as String }
    private val dataLayouts = mutableMapOf<String, DataLayout>()
    private val globals = bindings.associate { it["id"] as String to GlobalBinding(it["name"] as String) }
    private val indices = bindings.withIndex().associate { it.value["id"] as String to it.index }
    private val names = bindings.withIndex().groupBy({ it.value["name"] as String }, { it.index })
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    init {
        val scope = Scope(FrameLayout())
        val initializers = bindings.map { binding ->
            val expr = binding["expr"] as List<Any?>
            if (representation(binding) && expr[0] !in listOf("lam", "lit", "con", "void")) delay(expr, scope, binding["name"] as String)
            else argument(expr, scope, representation(binding), binding["name"] as String)
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scope.layout.build())
        bindings.forEachIndexed { index, binding -> globals.getValue(binding["id"] as String).initialize(initializers[index].execute(frame)) }
    }
    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    fun hostEntryTarget(arity: Int = 0): RootCallTarget = hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
    fun entryValue(name: String): Any? = globals.getValue(bindings[bindingIndex(name)]["id"] as String).read()
    fun entryTarget(name: String): RootCallTarget {
        var value = entryValue(name)
        while (value is Thunk && value.state == 2) value = value.value
        return when (value) { is Closure -> value.target; is Thunk -> value.target; else -> hostEntryTarget(0) }
    }
    fun diagnostics(): Map<String, Any> = linkedMapOf(
        "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkEvaluationsByLabel.toMap(),
        "compiledEntries" to metrics.compiledEntries, "thunkEvaluations" to metrics.thunkEvaluations,
        "thunkHits" to metrics.thunkHits, "blackholes" to metrics.blackholes, "directCacheMisses" to metrics.directCacheMisses,
        "indirectCalls" to metrics.indirectCalls, "tailBounces" to metrics.tailBounces, "papAllocations" to metrics.papAllocations,
        "unsupportedPolicy" to (if (diagnosticUnsupported) "diagnostic-traps" else "reject-at-load"),
        "deferredUnsupported" to deferredUnsupported.toList(), "unsupportedTraps" to metrics.unsupportedTraps,
        "frames" to "indexed primitive slots; selective StaticShape captures",
        "stackPolicy" to "tail-safe; non-tail calls and nested thunk forcing use host stack", "threadPolicy" to "single guest thread")
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
    private fun function(label: String, args: List<Map<String, Any?>>, expression: List<Any?>, outer: Scope): FunctionSpec {
        val scope = Scope(FrameLayout())
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        val captured = (free - argumentIds).filter { it in outer.locals }
        val captureSources = captured.map { outer.locals.getValue(it).slot }.toIntArray()
        val captureKinds = captured.map { outer.locals.getValue(it).primitive }.toBooleanArray()
        val environmentSlots = captured.map { scope.bind(it, outer.locals.getValue(it).primitive).slot }.toIntArray()
        val argumentSlots = arrayListOf<Int>(); val argumentIndices = arrayListOf<Int>()
        for ((index, arg) in args.withIndex()) {
            val lifted = representation(arg)
            if (arg["id"] in free) { argumentIndices += index; argumentSlots += scope.bind(arg["id"] as String, !lifted && arg["coercion"] != true).slot }
        }
        val body = compile(expression, scope, true)
        val captures = if (captured.isEmpty()) null else CaptureLayout(requireNotNull(language), captureKinds)
        val target = FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics).callTarget
        return FunctionSpec(target, captures, captureSources)
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expr {
        val fn = function(label, emptyList(), expr, scope)
        return Delay(fn.target, fn.captureLayout, fn.captures)
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk"): Expr {
        if (!lifted) return Evaluate(compile(expr, scope, false), metrics)
        // GHC's context-aware exprOkForSpecEval certificate also covers total
        // primitive operands in constructors, without strictifying recursive
        // dictionary knots. Allocate these values directly instead of creating
        // an update thunk and captures. A false certificate overrides HNF.
        // Older exports fall back to exprIsHNF; missing proofs stay lazy.
        if (expr[0] == "app" && ((expr.getOrNull(5) as? Boolean) ?: (expr.getOrNull(4) == true)))
            return compile(expr, scope, false)
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }
    }
    private fun literal(kind: String, value: String): Any = when (kind) {
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
        "string-bytes" -> LiteralAddress.fromHex(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = try {
        compileSupported(expr, scope, tail)
    } catch (gap: UnsupportedCore) {
        if (!diagnosticUnsupported) throw gap
        val message = gap.message ?: "Unsupported Core"
        deferredUnsupported += message
        // An unavailable value stays lazy until demanded. This applies uniformly
        // to unknown globals/operations, without special-casing library names or
        // removing branches. The default mode still rejects the same Core.
        val target = FunctionRoot(language, FrameLayout().build(), "unsupported: $message", null,
            intArrayOf(), intArrayOf(), intArrayOf(), UnsupportedExpression(message, metrics), metrics).callTarget
        Delay(target, null, intArrayOf())
    }
    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            scope.locals[id]?.let { LocalRead(it.slot) } ?: globals[id]?.let { GlobalRead(it) }
                ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> Literal(literal(expr[1] as String, expr[2] as String))
        "void" -> Literal(Unit)
        "lam" -> {
            val args = expr[1] as List<Map<String, Any?>>
            val fn = function("lambda ${args.joinToString { it["name"].toString() }}", args, expr[2] as List<Any?>, scope)
            MakeClosure(fn.target, args.size, fn.captureLayout, fn.captures)
        }
        "app" -> {
            val fn = expr[1] as List<Any?>; val args = expr[2] as List<List<Any?>>
            val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Application lacks representation flags")
            if (flags.size != args.size) throw RuntimeFault("Application representation flag count mismatch")
            val constructorStrictFields = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size)
                strictConstructorFields(fn[1] as String, args.size) else null
            val nodes = args.mapIndexed { i, arg ->
                val lifted = flags[i] as? Boolean ?: throw UnsupportedCore("Unknown argument levity")
                // A saturated constructor's strict operand is already a CBV
                // context. Compile it directly, without an allocate/force thunk.
                // Partial constructors deliberately take the ordinary lazy path.
                argument(arg, scope, lifted && constructorStrictFields?.get(i) != true)
            }.toTypedArray()
            when {
                fn[0] == "prim" -> primitive(fn[1] as String, nodes)
                constructorStrictFields != null -> Construct(dataLayout(fn[1] as String), nodes)
                else -> Application(compile(fn, scope, false), nodes, tail, metrics)
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            val local = scope.child()
            val slots = group.map { local.bind(it["id"] as String, !representation(it)).slot }.toIntArray()
            val rhs = group.map {
                val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
                if (recursive && !lifted) throw UnsupportedCore("Recursive unlifted binding unsupported")
                if (recursive && lifted && rhsExpr[0] !in listOf("lam", "lit", "con", "void")) delay(rhsExpr, local, it["name"].toString())
                else argument(rhsExpr, if (recursive) local else scope, lifted, it["name"].toString())
            }.toTypedArray()
            Let(slots, rhs, compile(expr[3] as List<Any?>, local, tail), recursive)
        }
        "case" -> {
            val local = scope.child(); val binder = local.bind(expr[2] as String, true).slot
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let { literal(it[0], it[1]) }
                    "data" -> dataLayout(alt[1] as String)
                    else -> alt[1]
                }
                val ids = alt[2] as List<String>
                val layout = value as? DataLayout
                if (layout != null && layout.arity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val slots = ids.mapIndexed { index, id -> child.bind(id, layout?.isLong(index) == true).slot }.toIntArray()
                val tag = when (kind) {
                    "default" -> DEFAULT_ALTERNATIVE
                    "data" -> DATA_ALTERNATIVE
                    "lit" -> LITERAL_ALTERNATIVE
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                Alternative(tag, value, slots, compile(alt[3] as List<Any?>, child, tail))
            }.toTypedArray()
            Case(compile(expr[1] as List<Any?>, scope, false), binder, alternatives, metrics)
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
            if (arity == 0) construct(id, emptyArray()) else {
                val layout = FrameLayout(); val slots = IntArray(arity) { layout.bind("field$it") }
                val body = construct(id, Array(arity) { LocalRead(slots[it]) })
                val target = FunctionRoot(language, layout.build(), "constructor $id", null, intArrayOf(), slots, IntArray(arity) { it }, body, metrics).callTarget
                MakeClosure(target, arity, null, intArrayOf())
            }
        }
        "prim" -> throw UnsupportedCore("Unsaturated primitive ${expr[1]}")
        else -> throw UnsupportedCore("Unsupported Core node ${expr[0]}")
    }
    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        if ((info["kind"] ?: "boxed") != "boxed") throw UnsupportedCore("Unsupported constructor representation ${info["kind"]}: $id")
        val reps = info["fieldReps"] as? List<*> ?: throw RuntimeFault("Missing constructor primitive representations: $id")
        if (reps.size != (info["arity"] as Number).toInt()) throw RuntimeFault("Constructor representation count mismatch: $id")
        val fields = reps.map { field ->
            val registers = field as? List<*> ?: throw UnsupportedCore("Unresolved constructor field representation: $id")
            when (registers.size) {
                0 -> "VoidRep"
                1 -> when (val rep = registers[0] as? String ?: throw RuntimeFault("Invalid constructor field representation: $id")) {
                    "BoxedRep (Just Lifted)" -> "LiftedRep"
                    "BoxedRep (Just Unlifted)" -> "UnliftedRep"
                    "BoxedRep Nothing" -> throw UnsupportedCore("Unresolved constructor field levity: $id")
                    else -> rep
                }
                else -> throw UnsupportedCore("Multi-register constructor field unsupported: $id")
            }
        }.toTypedArray()
        DataLayout(language ?: throw RuntimeFault("Constructor layout requires a guest language"), id, info["name"] as String, fields)
    }
    private fun primitive(name: String, args: Array<Expr>): Expr = when (name) {
        "raise#" -> {
            if (args.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
            RaiseException(args[0])
        }
        "plusAddr#", "indexCharOffAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "plusAddr#") PlusLiteralAddress(args[0], args[1]) else IndexLiteralChar(args[0], args[1])
        }
        else -> Primitive(name, args)
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
            strictField && (lifted[i] as? Boolean
                ?: throw UnsupportedCore("Unknown strict constructor field levity: $id field $i"))
        }
    }
    private fun construct(id: String, args: Array<Expr>): Expr {
        val strict = strictConstructorFields(id, args.size)
        val fields = Array(args.size) { i ->
            // Constructor workers carry CBV obligations independently of argument
            // levity (CorePrep, Note [Pin evaluatedness on floats]). This body runs
            // only at saturation, including entry through a constructor closure/PAP.
            if (strict[i]) Evaluate(args[i], metrics) else args[i]
        }
        return Construct(dataLayout(id), fields)
    }
}

/** Explicit development mode only; execution never fabricates a guest result. */
private class UnsupportedExpression(private val message: String, private val metrics: Metrics) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        metrics.unsupportedTraps++
        throw RuntimeFault("Diagnostic unsupported path reached: $message")
    }
}
