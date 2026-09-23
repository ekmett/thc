@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.BytecodeLabel
import com.oracle.truffle.api.bytecode.BytecodeLocal
import thc.Language

/**
 * Core lowers to real Bytecode DSL control flow and primitive operations. The parser is
 * replayable: targets, layouts and literal constants are prepared once; bytecode locals
 * and labels are created afresh on every replay. Runtime values and application use the
 * same selective captures, lazy update protocol and PAP convention as the AST backend.
 */
class BytecodeProgram(private val language: Language, moduleData: Map<String, Any?>) : ExecutableProgram {
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
    private val roots = arrayListOf<BytecodeRoot>()
    private var nextLocal = 0

    private data class Local(val id: Int, val name: String, val primitive: Boolean)
    private class FunctionContext(val formalArity: Int) {
        var arguments: List<Local?> = emptyList()
        var captures: List<Local> = emptyList()
        var captureLayout: CaptureLayout? = null
        var mayLoop = false
    }
    private class Scope(val function: FunctionContext, val locals: MutableMap<String, Local> = linkedMapOf()) {
        fun child() = Scope(function, LinkedHashMap(locals))
    }
    private class Emission(val builder: BytecodeRootGen.Builder) {
        val locals = mutableMapOf<Int, BytecodeLocal>()
        var continueLabel: BytecodeLabel? = null
    }
    private fun interface Expression { fun emit(emission: Emission) }
    private class LocalExpression(val local: Local, val resolve: Boolean) : Expression {
        override fun emit(emission: Emission) {
            val b = emission.builder
            if (resolve) b.beginReadCellIfNeeded()
            b.emitLoadLocal(emission.locals.getValue(local.id))
            if (resolve) b.endReadCellIfNeeded()
        }
    }
    private data class FunctionSpec(val target: RootCallTarget, val captureLayout: CaptureLayout?, val captures: List<Local>)

    init {
        val scope = Scope(FunctionContext(0))
        val initializers = bindings.map { binding ->
            val expr = binding["expr"] as List<Any?>
            if (representation(binding) && expr[0] !in listOf("lam", "lit", "con", "void")) delay(expr, scope, binding["name"] as String)
            else argument(expr, scope, representation(binding), binding["name"] as String)
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
        "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkEvaluationsByLabel.toMap(),
        "compiledEntries" to metrics.compiledEntries, "thunkEvaluations" to metrics.thunkEvaluations,
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

    private fun bind(scope: Scope, name: String, primitive: Boolean): Local = Local(nextLocal++, name, primitive).also { scope.locals[name] = it }
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
        val context = FunctionContext(args.size)
        val scope = Scope(context)
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        val captureSources = (free - argumentIds).filter { it in outer.locals }.map { outer.locals.getValue(it) }
        context.captures = captureSources.map { bind(scope, it.name, it.primitive) }
        context.captureLayout = if (captureSources.isEmpty()) null else CaptureLayout(language, captureSources.map { it.primitive }.toBooleanArray())
        context.arguments = args.map { arg ->
            val lifted = representation(arg)
            if (arg["id"] in free) bind(scope, arg["id"] as String, !lifted && arg["coercion"] != true) else null
        }
        val body = compile(expression, scope, true)
        return FunctionSpec(build(label, context, body), context.captureLayout, captureSources)
    }

    private fun build(label: String, context: FunctionContext, body: Expression, forceResult: Boolean = true): RootCallTarget {
        val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            val e = Emission(b)
            b.emitEnterRoot(metrics)
            for (local in context.captures + context.arguments.filterNotNull()) {
                e.locals[local.id] = b.createLocal(local.name, if (local.primitive) "primitive" else "object")
            }
            context.captures.forEachIndexed { index, local ->
                b.beginStoreLocal(e.locals.getValue(local.id))
                b.beginCaptureRead(context.captureLayout!!, index)
                b.emitLoadArgument(1)
                b.endCaptureRead()
                b.endStoreLocal()
            }
            val offset = if (context.captureLayout == null) 1 else 2
            context.arguments.forEachIndexed { index, local -> if (local != null) {
                b.beginStoreLocal(e.locals.getValue(local.id))
                b.emitLoadArgument(index + offset)
                b.endStoreLocal()
            } }
            if (context.mayLoop) {
                b.beginWhile()
                b.emitLoadConstant(true)
                b.beginBlock()
                e.continueLabel = b.createLabel()
            }
            b.beginReturn()
            if (forceResult) force(body).emit(e) else body.emit(e)
            b.endReturn()
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
        }.getNode(0)
        root.setLabel(label)
        roots += root
        return root.callTarget
    }

    private fun read(local: Local, resolve: Boolean = true): Expression = LocalExpression(local, resolve)
    private fun force(value: Expression) = Expression { e ->
        val b = e.builder
        if (value is LocalExpression && value.resolve) {
            val local = e.locals.getValue(value.local.id)
            b.beginForceLocal(metrics, local)
            b.emitLoadLocal(local)
            b.endForceLocal()
        } else {
            b.beginForceValue(metrics); value.emit(e); b.endForceValue()
        }
    }
    private fun requireClosure(value: Expression) = Expression { e ->
        e.builder.beginRequireClosure(); force(value).emit(e); e.builder.endRequireClosure()
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expression {
        val fn = function(label, emptyList(), expr, scope)
        val template = BytecodeRoot.ClosureTemplate(fn.target, 0, fn.captureLayout)
        return Expression { e ->
            e.builder.beginMakeThunk(template)
            fn.captures.forEach { read(it, false).emit(e) }
            e.builder.endMakeThunk()
        }
    }
    private fun closure(fn: FunctionSpec, arity: Int): Expression {
        val template = BytecodeRoot.ClosureTemplate(fn.target, arity, fn.captureLayout)
        return Expression { e ->
            e.builder.beginMakeClosure(template)
            fn.captures.forEach { read(it, false).emit(e) }
            e.builder.endMakeClosure()
        }
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk"): Expression {
        if (!lifted) return force(compile(expr, scope, false))
        if (expr[0] == "app" && ((expr.getOrNull(5) as? Boolean) ?: (expr.getOrNull(4) == true))) return compile(expr, scope, false)
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }
    }
    private fun literal(kind: String, value: String): Any = when (kind) {
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
        "string-bytes" -> LiteralAddress.fromHex(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun constant(value: Any) = Expression { it.builder.emitLoadConstant(value) }
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expression = try {
        compileSupported(expr, scope, tail)
    } catch (gap: UnsupportedCore) {
        if (!diagnosticUnsupported) throw gap
        val message = gap.message ?: "Unsupported Core"
        deferredUnsupported += message
        val target = build("unsupported: $message", FunctionContext(0), Expression { it.builder.emitUnsupported(message, metrics) })
        val template = BytecodeRoot.ClosureTemplate(target, 0, null)
        Expression { e -> e.builder.beginMakeThunk(template); e.builder.endMakeThunk() }
    }

    private fun application(function: Expression, arguments: List<Expression>, scope: Scope, tail: Boolean): Expression {
        val context = scope.function
        val loop = tail && arguments.size <= context.formalArity && context.formalArity > 0
        // Even a root without a direct self-call can receive A -> B -> ... -> A.
        if (tail) context.mayLoop = true
        return Expression { e ->
            val b = e.builder
            val reentryResult = if (tail) {
                b.beginBlock()
                b.createLocal("tail result", null).also { b.beginStoreLocal(it) }
            } else null
            if (!loop) {
                b.beginApply(arguments.size, tail, metrics)
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
                context.captures.forEachIndexed { index, local ->
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    b.beginCaptureRead(context.captureLayout!!, index)
                    b.beginClosureEnvironment(); b.emitLoadLocal(fn); b.endClosureEnvironment()
                    b.endCaptureRead()
                    b.endStoreLocal()
                }
                val prefix = context.formalArity - arguments.size
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    if (index < prefix) {
                        b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied()
                    } else b.emitLoadLocal(args[index - prefix])
                    b.endStoreLocal()
                } }
                b.emitBranch(e.continueLabel!!)
                // Unreachable value satisfies the expression shape of Conditional's then branch.
                b.emitLoadConstant(Unit)
                b.endBlock()
                b.beginApply(arguments.size, true, metrics)
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
                    b.beginCaptureRead(context.captureLayout!!, index)
                    b.beginTailArgument(1); b.emitLoadLocal(reentryResult); b.endTailArgument()
                    b.endCaptureRead()
                    b.endStoreLocal()
                }
                val offset = if (context.captureLayout == null) 1 else 2
                context.arguments.forEachIndexed { index, local -> if (local != null) {
                    b.beginStoreLocal(e.locals.getValue(local.id))
                    b.beginTailArgument(index + offset); b.emitLoadLocal(reentryResult); b.endTailArgument()
                    b.endStoreLocal()
                } }
                b.emitBranch(e.continueLabel!!)
                b.emitLoadConstant(Unit)
                b.endBlock()
                b.emitLoadLocal(reentryResult)
                b.endConditional()
                b.endBlock()
            }
        }
    }

    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expression = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            scope.locals[id]?.let { read(it) } ?: globals[id]?.let { binding -> Expression { it.builder.emitReadGlobal(binding) } }
                ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> constant(literal(expr[1] as String, expr[2] as String))
        "void" -> constant(Unit)
        "lam" -> {
            val args = expr[1] as List<Map<String, Any?>>
            closure(function("lambda ${args.joinToString { it["name"].toString() }}", args, expr[2] as List<Any?>, scope), args.size)
        }
        "app" -> {
            val fn = expr[1] as List<Any?>; val args = expr[2] as List<List<Any?>>
            val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Application lacks representation flags")
            if (flags.size != args.size) throw RuntimeFault("Application representation flag count mismatch")
            val strict = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size) strictConstructorFields(fn[1] as String, args.size) else null
            val operands = args.mapIndexed { index, arg ->
                val lifted = flags[index] as? Boolean ?: throw UnsupportedCore("Unknown argument levity")
                argument(arg, scope, lifted && strict?.get(index) != true)
            }
            when {
                fn[0] == "prim" -> primitive(fn[1] as String, operands)
                strict != null -> construct(dataLayout(fn[1] as String), operands)
                else -> application(compile(fn, scope, false), operands, scope, tail)
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            val local = scope.child()
            val slots = group.map { bind(local, it["id"] as String, !representation(it)) }
            val rhs = group.map {
                val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
                if (recursive && !lifted) throw UnsupportedCore("Recursive unlifted binding unsupported")
                if (recursive && lifted && rhsExpr[0] !in listOf("lam", "lit", "con", "void")) delay(rhsExpr, local, it["name"].toString())
                else argument(rhsExpr, if (recursive) local else scope, lifted, it["name"].toString())
            }
            val body = compile(expr[3] as List<Any?>, local, tail)
            Expression { e ->
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
                body.emit(e)
                b.endBlock()
                slots.forEach { e.locals.remove(it.id) }
            }
        }
        "case" -> {
            val local = scope.child()
            val binder = bind(local, expr[2] as String, true)
            val scrutinee = force(compile(expr[1] as List<Any?>, scope, false))
            data class Alternative(val kind: String, val value: Any?, val fields: List<Local>, val body: Expression)
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let { literal(it[0], it[1]) }
                    "data" -> dataLayout(alt[1] as String)
                    "default" -> alt[1]
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                val ids = alt[2] as List<String>; val layout = value as? DataLayout
                if (layout != null && layout.arity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val fields = ids.mapIndexed { index, id -> bind(child, id, layout?.isLong(index) == true) }
                Alternative(kind, value, fields, compile(alt[3] as List<Any?>, child, tail))
            }
            val explicit = alternatives.filter { it.kind != "default" }
            val fallback = alternatives.lastOrNull { it.kind == "default" }
            Expression { e ->
                val b = e.builder
                b.beginBlock()
                e.locals[binder.id] = b.createLocal(binder.name, null)
                b.beginStoreLocal(e.locals.getValue(binder.id)); scrutinee.emit(e); b.endStoreLocal()
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
                    alt.body.emit(e)
                    b.endBlock()
                    alt.fields.forEach { e.locals.remove(it.id) }
                }
                fun emitChoice(index: Int) {
                    if (index == explicit.size) {
                        if (fallback == null) b.emitFailCase() else emitAlternative(fallback)
                        return
                    }
                    val alt = explicit[index]
                    b.beginConditional()
                    if (alt.kind == "data") b.beginMatchData(alt.value as DataLayout) else b.beginMatchLiteral(alt.value!!)
                    read(binder, false).emit(e)
                    if (alt.kind == "data") b.endMatchData() else b.endMatchLiteral()
                    emitAlternative(alt)
                    emitChoice(index + 1)
                    b.endConditional()
                }
                emitChoice(0)
                b.endBlock()
                e.locals.remove(binder.id)
            }
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
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
        "prim" -> throw UnsupportedCore("Unsaturated primitive ${expr[1]}")
        else -> throw UnsupportedCore("Unsupported Core node ${expr[0]}")
    }

    private fun construct(layout: DataLayout, args: List<Expression>) = Expression { e ->
        e.builder.beginConstruct(layout); args.forEach { it.emit(e) }; e.builder.endConstruct()
    }
    private fun primitive(name: String, args: List<Expression>): Expression {
        val operation = when (name) {
            "+#", "plusWord#" -> "Add"
            "-#", "minusWord#" -> "Subtract"
            "*#", "timesWord#" -> "Multiply"
            "negateInt#" -> "Negate"
            "quotInt#" -> "Quotient"
            "remInt#" -> "Remainder"
            "==#", "eqWord#", "eqChar#" -> "Equal"
            "/=#", "neWord#", "neChar#" -> "NotEqual"
            "<#", "ltChar#" -> "LessThan"
            "<=#", "leChar#" -> "LessEqual"
            ">#", "gtChar#" -> "GreaterThan"
            ">=#", "geChar#" -> "GreaterEqual"
            "and#", "andI#" -> "BitAnd"
            "or#", "orI#" -> "BitOr"
            "xor#", "xorI#" -> "BitXor"
            "not#", "notI#" -> "BitNot"
            "uncheckedIShiftL#", "uncheckedShiftL#" -> "ShiftLeft"
            "uncheckedIShiftRA#" -> "ShiftRight"
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> "ShiftRightUnsigned"
            "narrow8Int#" -> "Narrow8"
            "narrow16Int#" -> "Narrow16"
            "narrow32Int#" -> "Narrow32"
            "int2Word#", "word2Int#", "ord#", "chr#" -> "Identity"
            "raise#" -> "Raise"
            "plusAddr#" -> "AddressPlus"
            "indexCharOffAddr#" -> "AddressIndexChar"
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        val unary = operation in setOf("Negate", "BitNot", "Narrow8", "Narrow16", "Narrow32", "Identity", "Raise")
        if (args.size != if (unary) 1 else 2) throw RuntimeFault("Primitive arity mismatch: $name")
        if (operation == "Identity") return Expression { e -> e.builder.beginToLong(); args[0].emit(e); e.builder.endToLong() }
        return Expression { e ->
            val b = e.builder
            when (operation) {
                "Add" -> b.beginAdd(); "Subtract" -> b.beginSubtract(); "Multiply" -> b.beginMultiply()
                "Negate" -> b.beginNegate(); "Quotient" -> b.beginQuotient(); "Remainder" -> b.beginRemainder()
                "Equal" -> b.beginEqual(); "NotEqual" -> b.beginNotEqual(); "LessThan" -> b.beginLessThan()
                "LessEqual" -> b.beginLessEqual(); "GreaterThan" -> b.beginGreaterThan(); "GreaterEqual" -> b.beginGreaterEqual()
                "BitAnd" -> b.beginBitAnd(); "BitOr" -> b.beginBitOr(); "BitXor" -> b.beginBitXor(); "BitNot" -> b.beginBitNot()
                "ShiftLeft" -> b.beginShiftLeft(); "ShiftRight" -> b.beginShiftRight(); "ShiftRightUnsigned" -> b.beginShiftRightUnsigned()
                "Narrow8" -> b.beginNarrow8(); "Narrow16" -> b.beginNarrow16(); "Narrow32" -> b.beginNarrow32()
                "Raise" -> b.beginRaise(); "AddressPlus" -> b.beginAddressPlus(); "AddressIndexChar" -> b.beginAddressIndexChar()
            }
            args.forEach { it.emit(e) }
            when (operation) {
                "Add" -> b.endAdd(); "Subtract" -> b.endSubtract(); "Multiply" -> b.endMultiply()
                "Negate" -> b.endNegate(); "Quotient" -> b.endQuotient(); "Remainder" -> b.endRemainder()
                "Equal" -> b.endEqual(); "NotEqual" -> b.endNotEqual(); "LessThan" -> b.endLessThan()
                "LessEqual" -> b.endLessEqual(); "GreaterThan" -> b.endGreaterThan(); "GreaterEqual" -> b.endGreaterEqual()
                "BitAnd" -> b.endBitAnd(); "BitOr" -> b.endBitOr(); "BitXor" -> b.endBitXor(); "BitNot" -> b.endBitNot()
                "ShiftLeft" -> b.endShiftLeft(); "ShiftRight" -> b.endShiftRight(); "ShiftRightUnsigned" -> b.endShiftRightUnsigned()
                "Narrow8" -> b.endNarrow8(); "Narrow16" -> b.endNarrow16(); "Narrow32" -> b.endNarrow32()
                "Raise" -> b.endRaise(); "AddressPlus" -> b.endAddressPlus(); "AddressIndexChar" -> b.endAddressIndexChar()
            }
        }
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
        DataLayout(language, id, info["name"] as String, fields)
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
