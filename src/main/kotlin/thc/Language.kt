package thc

import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.nodes.Node
import thc.runtime.TargetCache
import thc.runtime.Metrics
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidArrayIndexException
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnknownIdentifierException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.RootNode
import thc.runtime.Program
import thc.runtime.BytecodeProgram
import thc.runtime.ExecutableProgram
import java.io.File

object CoreModules {
    @Suppress("UNCHECKED_CAST")
    fun merge(modules: List<Map<String, Any?>>): Map<String, Any?> {
        require(modules.isNotEmpty()) { "No Core modules supplied" }
        val bindings = linkedMapOf<String, Map<String, Any?>>()
        val constructors = linkedMapOf<String, Map<String, Any?>>()
        val sourceFiles = linkedMapOf<String, Map<String, Any?>>()
        val sourceSpans = linkedMapOf<String, Map<String, Any?>>()
        for (module in modules) {
            require((module["schema"] as? Number)?.toInt() == 1) { "Unsupported Core schema: ${module["schema"]}" }
            require(module["ghc"] == "9.14.1") { "This adapter requires GHC 9.14.1 exports" }
            for ((key, table) in listOf("sourceFiles" to sourceFiles, "sourceSpans" to sourceSpans)) {
                val records = module[key] ?: continue
                require(records is List<*>) { "Invalid $key table" }
                for (record in records) {
                    require(record is Map<*, *>) { "Invalid $key record" }
                    val id = record["id"] as? String ?: error("Missing $key identity")
                    val source = record as Map<String, Any?>
                    val old = table.putIfAbsent(id, source)
                    require(old == null || old == source) { "Inconsistent $key record: $id" }
                }
            }
            for (b in module["bindings"] as List<Map<String, Any?>>) {
                val id = b["id"] as String
                require(bindings.putIfAbsent(id, b) == null) { "Duplicate binding: $id" }
            }
            for (c in module["constructors"] as List<Map<String, Any?>>) {
                val id = c["id"] as String
                val old = constructors.putIfAbsent(id, c)
                require(old == null || old == c) { "Inconsistent constructor: $id" }
            }
        }
        return mapOf("schema" to 1L, "ghc" to "9.14.1", "module" to "THC.Bundle",
            "bindings" to bindings.values.toList(), "constructors" to constructors.values.toList(),
            "sourceFiles" to sourceFiles.values.toList(), "sourceSpans" to sourceSpans.values.toList())
    }

    @Suppress("UNCHECKED_CAST")
    fun reachable(module: Map<String, Any?>, entry: String): Map<String, Any?> {
        val bindings = module["bindings"] as List<Map<String, Any?>>
        val byId = bindings.associateBy { it["id"] as String }
        val exact = byId[entry]
        val roots = if (exact != null) listOf(exact) else bindings.filter { it["name"] == entry }
        require(roots.size == 1) { "Missing or ambiguous entry: $entry" }
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        fun reference(id: String, bound: Set<String>) {
            if (id !in bound && id in byId && reachable.add(id)) pending.addLast(id)
        }
        fun visit(expr: List<Any?>, bound: Set<String>) {
            when (expr[0]) {
                "var" -> reference(expr[1] as String, bound)
                "lam" -> {
                    val ids = (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }
                    visit(expr[2] as List<Any?>, bound + ids)
                }
                "app" -> {
                    visit(expr[1] as List<Any?>, bound)
                    (expr[2] as List<List<Any?>>).forEach { visit(it, bound) }
                }
                "let" -> {
                    val group = expr[2] as List<Map<String, Any?>>
                    val ids = group.map { it["id"] as String }
                    val rhsScope = if (expr[1] == true) bound + ids else bound
                    group.forEach { visit(it["expr"] as List<Any?>, rhsScope) }
                    visit(expr[3] as List<Any?>, bound + ids)
                }
                "case" -> {
                    visit(expr[1] as List<Any?>, bound)
                    val alternatives = expr[3] as List<List<Any?>>
                    alternatives.forEach { alt ->
                        visit(alt[3] as List<Any?>, bound + (expr[2] as String) + (alt[2] as List<String>))
                    }
                }
            }
        }
        val root = roots.single()["id"] as String
        reachable.add(root)
        pending.addLast(root)
        while (pending.isNotEmpty()) visit(byId.getValue(pending.removeFirst())["expr"] as List<Any?>, emptySet())
        return module + ("bindings" to bindings.filter { it["id"] in reachable })
    }

    @Suppress("UNCHECKED_CAST")
    fun request(paths: List<String>, entry: String, instrument: Boolean = true, diagnosticUnsupported: Boolean = false,
                backend: String = defaultBackend(), sourceNotesEnabled: Boolean = true): String = Json.stringify(mapOf(
        "modules" to paths.map { Json.parse(File(it).readText()) as Map<String, Any?> },
        "entry" to entry, "instrument" to instrument, "diagnosticUnsupported" to diagnosticUnsupported, "backend" to backend, "sourceNotesEnabled" to sourceNotesEnabled))
}

@TruffleLanguage.Registration(id = "thc", name = "Turbo Haskell Compiler", version = "0.1-experiment",
    characterMimeTypes = ["application/x-thc-core"], defaultMimeType = "application/x-thc-core",
    contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
class Language : TruffleLanguage<Language.State>() {
    internal val handoffLayouts = thc.runtime.HandoffLayouts(this)
    internal val handoffState = locals.createContextThreadLocal { _, _ -> thc.runtime.HandoffState() }
    class State
    override fun createContext(env: Env): State = State()
    @Suppress("UNCHECKED_CAST")
    override fun parse(request: ParsingRequest): CallTarget {
        val input = Json.parse(request.source.characters.toString()) as Map<String, Any?>
        val modules = input["modules"] as? List<Map<String, Any?>> ?: error("Expected modules array")
        val entry = input["entry"] as? String ?: error("Expected entry name")
        val linked = CoreModules.reachable(CoreModules.merge(modules), entry) + mapOf("instrument" to (input["instrument"] != false),
            "diagnosticUnsupported" to (input["diagnosticUnsupported"] == true),
            "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false))
        val bindings = linked["bindings"] as List<Map<String, Any?>>
        val selected = bindings.singleOrNull { it["id"] == entry } ?: bindings.single { it["name"] == entry }
        val selectedExpression = selected["expr"] as List<Any?>
        val hostResultFault = try {
            if (selectedExpression.firstOrNull() == "lam") thc.runtime.CoreRepresentations.requireScalar(
                thc.runtime.CoreRepresentations.lambdaResult(selectedExpression), "host result")
            null
        } catch (gap: thc.runtime.UnsupportedCore) {
            if (input["diagnosticUnsupported"] != true) throw gap
            gap.message
        }
        val program = when (val backend = input["backend"] ?: defaultBackend()) {
            "ast" -> Program(this, linked)
            "bytecode" -> BytecodeProgram(this, linked)
            else -> throw IllegalArgumentException("Unknown THC backend: $backend")
        }
        val value = EntryValue(program, entry, (selected["arity"] as Number).toInt(), hostResultFault)
        return object : RootNode(this) {
            override fun execute(frame: VirtualFrame): Any = value
            override fun getName(): String = "THC load $entry"
        }.callTarget
    }
}

@ExportLibrary(InteropLibrary::class)
class EntryValue(private val program: ExecutableProgram, private val entry: String, private val argumentCount: Int,
                 private val hostResultFault: String? = null) : TruffleObject {
    private val guestTarget = program.hostEntryTarget(argumentCount)
    private val guestEntry = program.entryValue(entry)
    @ExportMessage fun isExecutable() = true
    @ExportMessage fun execute(arguments: Array<Any?>,
                               @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any? {
        if (hostResultFault != null) throw thc.runtime.RuntimeFault("Diagnostic unsupported path reached: $hostResultFault")
        if (arguments.size != argumentCount) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            throw IllegalArgumentException("Host kernel $entry expects $argumentCount arguments")
        }
        val normalized = Array<Any?>(arguments.size) { index ->
            when (val value = arguments[index]) {
                is Long -> value
                is Int -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                else -> {
                    CompilerDirectives.transferToInterpreterAndInvalidate()
                    error("The prototype host ABI accepts signed 64-bit integer arguments only")
                }
            }
        }
        return dispatch.execute(guestTarget, arrayOf(guestEntry, normalized))
    }
    @ExportMessage fun hasMembers() = true
    @ExportMessage fun getMembers(includeInternal: Boolean): Any = MemberNames(
        if (program is BytecodeProgram) arrayOf("diagnostics", "compile", "bytecode") else arrayOf("diagnostics", "compile"))
    @ExportMessage fun isMemberReadable(member: String) = member == "diagnostics" || (member == "bytecode" && program is BytecodeProgram)
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun readMember(member: String): Any {
        return when {
            member == "diagnostics" -> Json.stringify(program.diagnostics())
            member == "bytecode" && program is BytecodeProgram -> program.bytecodeDump()
            else -> throw UnknownIdentifierException.create(member)
        }
    }
    @ExportMessage fun isMemberInvocable(member: String) = member == "compile"
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun invokeMember(member: String, arguments: Array<Any?>): Any {
        if (member != "compile") throw UnknownIdentifierException.create(member)
        require(arguments.isEmpty()) { "compile takes no arguments" }
        val target = program.entryTarget(entry)
        val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        require(cls.isInstance(target)) { "Graal optimizing Truffle runtime required" }
        cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        check(cls.getMethod("isValidLastTier").invoke(target) == true) { "Guest code was not installed" }
        return true
    }
}

/** Bounded host entry cache, matching the guest call-target cache policy. */
class HostDispatch : Node() {
    @Child private var calls = TargetCache(Metrics(false))
    fun execute(target: RootCallTarget, arguments: Array<Any?>): Any? = calls.call(target, arguments)
    companion object { @JvmStatic fun create() = HostDispatch() }
}

@ExportLibrary(InteropLibrary::class)
class MemberNames(private val names: Array<String>) : TruffleObject {
    @ExportMessage fun hasArrayElements() = true
    @ExportMessage fun getArraySize(): Long = names.size.toLong()
    @ExportMessage fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < names.size
    @ExportMessage fun readArrayElement(index: Long): Any {
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index)
        return names[index.toInt()]
    }
}
