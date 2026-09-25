// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import thc.runtime.TargetCache
import thc.runtime.Metrics
import thc.runtime.Calls
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
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
import thc.runtime.CoreRepresentations
import thc.runtime.CoreRepresentation
import thc.runtime.IoMainRoot
import thc.runtime.TargetLayout
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

object CoreModules {
    @Suppress("UNCHECKED_CAST")
    fun merge(modules: List<Map<String, Any?>>): Map<String, Any?> {
        require(modules.isNotEmpty()) { "No Core modules supplied" }
        val bindings = linkedMapOf<String, Map<String, Any?>>()
        val constructors = linkedMapOf<String, Map<String, Any?>>()
        val sourceFiles = linkedMapOf<String, Map<String, Any?>>()
        val sourceSpans = linkedMapOf<String, Map<String, Any?>>()
        val bindingOrigins = linkedMapOf<String, Map<String, String>>()
        val foreignLinks = linkedMapOf<Pair<String, String>, ForeignBitcode>()
        val moduleKeys = hashSetOf<Pair<String, String>>()
        for (module in modules) {
            CoreForeignArtifacts.requireExecutable(module)
            CoreForeignArtifacts.linked(module)?.let { link ->
                require(foreignLinks.putIfAbsent(link.unit to link.module, link) == null) {
                    "Duplicate linked foreign module: ${link.unit}:${link.module}"
                }
            }
            require(module["ghc"] == "9.14.1") { "This adapter requires GHC 9.14.1 exports" }
            val unit = module["unit"] as? String
            val name = module["module"] as? String
            val interfaceFragment = unit == "dependency-closure" && name == "THC.InterfaceClosure" &&
                module["boundary"] == "actual-interface-unfoldings"
            if (unit != null && name != null && !interfaceFragment) {
                require(moduleKeys.add(unit to name)) { "Duplicate GHC module: $unit:$name" }
            }
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
                // The merged bundle has no single unit/module. Preserve the exact
                // exporting module for globally named bindings; synthetic entries
                // and interface fragments must not acquire a guessed owner.
                if (unit != null && name != null && id.startsWith("$unit:$name.") && id.length > "$unit:$name.".length)
                    bindingOrigins[id] = mapOf("unit" to unit, "module" to name)
            }
            for (c in module["constructors"] as List<Map<String, Any?>>) {
                val id = c["id"] as String
                val old = constructors.putIfAbsent(id, c)
                // The pretty-printed type can differ by quantified variable
                // names; all layout and future metadata fields must still agree.
                require(old == null || old.filterKeys { it != "type" } == c.filterKeys { it != "type" }) {
                    "Inconsistent constructor: $id"
                }
            }
        }
        return mapOf("schema" to 1L, "ghc" to "9.14.1", "module" to "THC.Bundle",
            "bindings" to bindings.values.toList(), "constructors" to constructors.values.toList(),
            "bindingOrigins" to bindingOrigins,
            "foreignLinks" to foreignLinks.values.toList(),
            "sourceFiles" to sourceFiles.values.toList(), "sourceSpans" to sourceSpans.values.toList())
    }

    @Suppress("UNCHECKED_CAST")
    fun reachable(module: Map<String, Any?>, entry: String, strictLink: Boolean = false): Map<String, Any?> {
        CoreForeignArtifacts.requireExecutableInput(module)
        val bindings = module["bindings"] as List<Map<String, Any?>>
        val byId = bindings.associateBy { it["id"] as String }
        val constructorIds = (module["constructors"] as? List<Map<String, Any?>>)
            ?.mapTo(hashSetOf()) { it["id"] as String } ?: emptySet()
        val exact = byId[entry]
        val roots = if (exact != null) listOf(exact) else bindings.filter { it["name"] == entry }
        require(roots.size == 1) { "Missing or ambiguous entry: $entry" }
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        val missing = linkedMapOf<String, MutableSet<String>>()
        val missingConstructors = linkedMapOf<String, MutableSet<String>>()
        var owner = ""
        fun constructor(id: String) {
            if (strictLink && id !in constructorIds)
                missingConstructors.getOrPut(id) { linkedSetOf() }.add(owner)
        }
        fun reference(id: String, bound: Set<String>) {
            if (id in bound) return
            if (id in byId) {
                if (reachable.add(id)) pending.addLast(id)
            } else if (strictLink) missing.getOrPut(id) { linkedSetOf() }.add(owner)
        }
        fun visit(expr: List<Any?>, bound: Set<String>) {
            when (expr[0]) {
                "var" -> reference(expr[1] as String, bound)
                "lam" -> {
                    val ids = (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }
                    visit(expr[2] as List<Any?>, bound + ids)
                }
                "app" -> {
                    val function = expr[1] as List<Any?>
                    // FCallIds name foreign declarations, not Haskell globals.
                    // Lowering validates the complete ABI and rejects unsupported
                    // targets. Defined heads and all operands still participate
                    // in linking; metadata cannot hide their dependencies.
                    val foreignHead = CoreRepresentations.metadata(expr)?.get("foreignCall") is Map<*, *> &&
                        function.firstOrNull() == "var" && function.getOrNull(1) is String &&
                        function[1] !in bound && function[1] !in byId
                    if (!foreignHead) visit(function, bound)
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
                        if (alt[0] == "data") constructor(alt[1] as String)
                        visit(alt[3] as List<Any?>, bound + (expr[2] as String) + (alt[2] as List<String>))
                    }
                }
                "con" -> constructor(expr[1] as String)
            }
        }
        val root = roots.single()["id"] as String
        reachable.add(root)
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            owner = pending.removeFirst()
            visit(byId.getValue(owner)["expr"] as List<Any?>, emptySet())
        }
        require(missing.isEmpty()) {
            "Unlinked Core globals: " + missing.entries.joinToString { (id, uses) -> "$id referenced by ${uses.joinToString()}" }
        }
        require(missingConstructors.isEmpty()) {
            "Unlinked Core constructors: " + missingConstructors.entries.joinToString { (id, uses) -> "$id referenced by ${uses.joinToString()}" }
        }
        return module + ("bindings" to bindings.filter { it["id"] in reachable })
    }

    fun request(paths: List<String>, entry: String, instrument: Boolean = true, diagnosticUnsupported: Boolean = false,
                backend: String = defaultBackend(), sourceNotesEnabled: Boolean = true, ioMain: Boolean = false): String {
        val manifest = paths.singleOrNull()?.takeIf { it.startsWith("@") }?.drop(1)
        val settings = linkedMapOf<String, Any>(
            "entry" to entry, "instrument" to instrument,
            "diagnosticUnsupported" to diagnosticUnsupported, "backend" to backend,
            "sourceNotesEnabled" to sourceNotesEnabled)
        if (manifest != null) settings["strictLink"] = true
        if (ioMain) settings["ioMain"] = true
        val options = StringBuilder().also { Json.appendObjectDocument(it,
            Json.stringify(settings)) }
        return buildString {
            append(options, 0, options.length - 1)
            append(",\"modules\":[")
            val layout = if (manifest != null) CorePackageManifest.appendModules(this, manifest) else {
                paths.forEachIndexed { index, path ->
                    if (index != 0) append(',')
                    // Validate each complete document before embedding it. Language.parse
                    // materializes the modules once; all bindings and metadata travel intact.
                    Json.appendObjectDocument(this, File(path).readText())
                }
                null
            }
            append(']')
            if (layout != null) {
                append(",\"targetLayout\":")
                append(Json.stringify(layout.document()))
            }
            append('}')
        }
    }
}

@TruffleLanguage.Registration(id = "thc", name = "Turbo Haskell Compiler", version = "0.1-experiment",
    characterMimeTypes = ["application/x-thc-core"], defaultMimeType = "application/x-thc-core",
    dependentLanguages = ["llvm"], contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
class Language : TruffleLanguage<Language.State>() {
    // Layout interning belongs to a context even when the language instance is shared.
    internal val handoffLayouts: thc.runtime.HandoffLayouts get() = currentState(null).handoffLayouts
    internal val handoffState = locals.createContextThreadLocal { _, _ -> thc.runtime.HandoffState() }
    class State(val env: Env, language: Language) {
        internal val handoffLayouts = thc.runtime.HandoffLayouts(language)
        internal val javaScriptImports = thc.runtime.JavaScriptImports()
        internal val maskingState = ThreadLocal.withInitial { thc.runtime.MaskingState.UNMASKED }
        internal val threads = thc.runtime.GuestThreads(env, maskingState)
        internal val files = thc.runtime.ManagedFiles(env, threads)
        internal val rtsFileLocks = thc.runtime.RtsFileLocks()
        // Installed only by the explicit fixed-filesystem NativeIO factory.
        // Ordinary/custom Context builders retain the embedding file service;
        // the CLI and explicit NativeIO factory install the fixed native provider.
        internal var nativeFiles: thc.runtime.NativeFileProvider? = null
        internal val stdio = thc.runtime.ManagedStdio(files)
        internal val iconv = thc.runtime.ManagedIconv({ cbits() }, stdio, threads)
        internal val strerror = thc.runtime.ManagedStrerror({ cbits() }, threads)
        internal val stackSnapshots = thc.runtime.ManagedStackRegistry()
        internal val capturedAsyncRequests = thc.runtime.CapturedAsyncRequests()
        internal val stablePointers = thc.runtime.StablePointers()
        internal val nativeAddresses = thc.runtime.NativeAddresses(env)
        // A future SHARED policy may keep the lockless thunk path while this is valid.
        // The transition is one-way and belongs to this context, not to Language.
        internal val singleThreadedAssumption = Truffle.getRuntime().createAssumption("THC single-threaded context")
        private var firstThread: Thread? = null
        @Synchronized internal fun noteThread(thread: Thread) {
            if (firstThread == null) firstThread = thread
            else if (firstThread !== thread) markMultithreaded()
        }
        internal fun markMultithreaded() {
            singleThreadedAssumption.invalidate("A second guest thread entered the context")
        }
        private val nativeCbits = AtomicReference<FutureTask<thc.runtime.SulongCbits>?>()
        private val nativeLimbs = AtomicReference<FutureTask<thc.runtime.LimbProvider>?>()
        @CompilerDirectives.TruffleBoundary
        internal fun limbs(): thc.runtime.LimbProvider {
            if (!env.isNativeAccessAllowed)
                throw thc.runtime.RuntimeFault("Native GMP arithmetic requires native access")
            var task = nativeLimbs.get()
            if (task == null) {
                val candidate = FutureTask<thc.runtime.LimbProvider> { thc.runtime.SulongLimbProvider(env) }
                if (nativeLimbs.compareAndSet(null, candidate)) {
                    task = candidate
                    candidate.run() // Parse LLVM without holding a monitor across guest code.
                } else task = nativeLimbs.get()
            }
            return try {
                val selected = task!!
                if (selected.isDone) selected.get()
                else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<FutureTask<thc.runtime.LimbProvider>, thc.runtime.LimbProvider> {
                        waiting -> waiting.get()
                    }, selected)
            } catch (failure: ExecutionException) {
                nativeLimbs.compareAndSet(task, null)
                throw (failure.cause ?: failure)
            }
        }
        @CompilerDirectives.TruffleBoundary
        internal fun cbits(): thc.runtime.SulongCbits {
            if (!env.isNativeAccessAllowed)
                throw thc.runtime.RuntimeFault("C bitcode requires native access for the Sulong runtime")
            var task = nativeCbits.get()
            if (task == null) {
                val candidate = FutureTask { thc.runtime.SulongCbits(env) }
                if (nativeCbits.compareAndSet(null, candidate)) {
                    task = candidate
                    candidate.run() // Parsing LLVM can execute guest code; never hold a cache lock here.
                } else task = nativeCbits.get()
            }
            return try {
                val selected = task!!
                if (selected.isDone) selected.get()
                else TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    TruffleSafepoint.InterruptibleFunction<FutureTask<thc.runtime.SulongCbits>, thc.runtime.SulongCbits> {
                        waiting -> waiting.get()
                    }, selected)
            } catch (failure: ExecutionException) {
                nativeCbits.compareAndSet(task, null)
                throw (failure.cause ?: failure)
            }
        }
    }
    override fun createContext(env: Env): State = State(env, this)
    override fun isThreadAccessAllowed(thread: Thread, singleThreaded: Boolean): Boolean = true
    override fun finalizeContext(context: State) { context.iconv.dispose() }
    override fun disposeContext(context: State) {
        try {
            try { context.threads.close() } finally {
                try { context.capturedAsyncRequests.close() } finally {
                    try { context.files.dispose() } finally {
                        try { context.stdio.dispose() } finally {
                            try { context.rtsFileLocks.dispose() } finally { context.stackSnapshots.dispose() }
                        }
                    }
                }
            }
        } finally { try { context.stablePointers.close() } finally { context.nativeAddresses.close() } }
    }
    override fun initializeThread(context: State, thread: Thread) = context.noteThread(thread)
    override fun initializeMultiThreading(context: State) = context.markMultithreaded()
    companion object {
        private val contexts = ContextReference.create(Language::class.java)
        @JvmStatic fun currentState(node: Node? = null): State = contexts.get(node)
    }
    @Suppress("UNCHECKED_CAST")
    override fun parse(request: ParsingRequest): CallTarget {
        val input = Json.parse(request.source.characters.toString()) as Map<String, Any?>
        val modules = input["modules"] as? List<Map<String, Any?>> ?: error("Expected modules array")
        val entry = input["entry"] as? String ?: error("Expected entry name")
        require(input["ioMain"] != true || input["diagnosticUnsupported"] != true) {
            "IO main requires strict unsupported-Core rejection"
        }
        val layout = input["targetLayout"]?.let(TargetLayout::fromDocument)
        val linked = CoreModules.reachable(CoreModules.merge(modules), entry, input["strictLink"] == true) + mapOf("instrument" to (input["instrument"] != false),
            "diagnosticUnsupported" to (input["diagnosticUnsupported"] == true),
            "sourceNotesEnabled" to (input["sourceNotesEnabled"] != false)) +
            (if (layout == null) emptyMap() else mapOf("targetLayout" to layout))
        (linked["foreignLinks"] as List<ForeignBitcode>).forEach { currentState(null).cbits().link(it) }
        val bindings = linked["bindings"] as List<Map<String, Any?>>
        val selected = bindings.singleOrNull { it["id"] == entry } ?: bindings.single { it["name"] == entry }
        val selectedExpression = selected["expr"] as List<Any?>
        val ioResult = if (input["ioMain"] == true) CoreRepresentations.ioUnitMainResult(selected, bindings) else null
        val hostResultFault = if (ioResult != null) null else try {
            thc.runtime.CoreRepresentations.knownFunctionSignature(selectedExpression, bindings)?.let { (inputs, result) ->
                inputs.forEach { thc.runtime.CoreRepresentations.requireScalar(it, "host argument") }
                thc.runtime.CoreRepresentations.requireScalar(result, "host result")
            }
            if (selectedExpression.firstOrNull() == "lam") {
                for (parameter in selectedExpression[1] as List<Map<String, Any?>>)
                    thc.runtime.CoreRepresentations.requireScalar(thc.runtime.CoreRepresentations.binder(parameter), "host argument")
                thc.runtime.CoreRepresentations.requireScalar(
                    thc.runtime.CoreRepresentations.lambdaResult(selectedExpression), "host result")
            }
            null
        } catch (gap: thc.runtime.UnsupportedCore) {
            if (input["diagnosticUnsupported"] != true) throw gap
            gap.message
        }
        val program = when (val backend = input["backend"] ?: defaultBackend()) {
            "ast" -> Program(this, linked)
            "bytecode" -> BytecodeProgram(this, linked, true)
            else -> throw IllegalArgumentException("Unknown THC backend: $backend")
        }
        val value = EntryValue(program, entry, (selected["arity"] as Number).toInt(), hostResultFault, ioResult, this)
        return object : RootNode(this) {
            override fun execute(frame: VirtualFrame): Any = value
            override fun getName(): String = "THC load $entry"
        }.callTarget
    }
}

@ExportLibrary(InteropLibrary::class)
internal class EntryValue(private val program: ExecutableProgram, private val entry: String, private val argumentCount: Int,
                 private val hostResultFault: String? = null, ioResult: CoreRepresentation? = null,
                 language: Language? = null) : TruffleObject {
    private val guestTarget = program.hostEntryTarget(argumentCount)
    private val guestEntry = program.entryValue(entry)
    private val ioTarget = ioResult?.let { IoMainRoot(language ?: error("Missing IO language"), it).callTarget }
    @ExportMessage fun isExecutable() = ioTarget == null
    @ExportMessage fun execute(arguments: Array<Any?>,
                               @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any? {
        if (ioTarget != null) throw thc.runtime.RuntimeFault("IO main must be invoked through runIO")
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
        val threads = Language.currentState(dispatch).threads
        threads.enterCurrent()
        var outcome = thc.runtime.GuestThreadStatus.FINISHED
        try {
            try {
                return thc.runtime.AsyncContinuations.publicResult(
                    dispatch.executePublic(guestTarget, arrayOf(guestEntry, normalized)), dispatch)
            } catch (suspended: thc.runtime.ThunkSuspended) {
                thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
            } catch (suspended: thc.runtime.CallSegmentSuspended) {
                thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
            }
        } catch (failure: Throwable) {
            outcome = thc.runtime.GuestThreadStatus.uncaught(failure)
            throw failure
        } finally { threads.leaveCurrent(outcome) }
    }
    @ExportMessage fun hasMembers() = true
    @ExportMessage fun getMembers(includeInternal: Boolean): Any = MemberNames(
        if (ioTarget != null) {
            if (program is BytecodeProgram) arrayOf("diagnostics", "runIO", "bytecode") else arrayOf("diagnostics", "runIO")
        } else if (program is BytecodeProgram) arrayOf("diagnostics", "compile", "bytecode") else arrayOf("diagnostics", "compile"))
    @ExportMessage fun isMemberReadable(member: String) = member == "diagnostics" || (member == "bytecode" && program is BytecodeProgram)
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun readMember(member: String): Any {
        return when {
            member == "diagnostics" -> Json.stringify(program.diagnostics())
            member == "bytecode" && program is BytecodeProgram -> program.bytecodeDump()
            else -> throw UnknownIdentifierException.create(member)
        }
    }
    @ExportMessage fun isMemberInvocable(member: String) = if (ioTarget != null) member == "runIO" else member == "compile"
    @ExportMessage @CompilerDirectives.TruffleBoundary
    fun invokeMember(member: String, arguments: Array<Any?>,
                     @Cached(value = "create()", uncached = "create()", neverDefault = true) dispatch: HostDispatch): Any {
        if (member == "runIO" && ioTarget != null) {
            require(arguments.isEmpty()) { "runIO takes no arguments" }
            val threads = Language.currentState(dispatch).threads
            threads.enterCurrent()
            var outcome = thc.runtime.GuestThreadStatus.FINISHED
            try {
                try { dispatch.execute(ioTarget, arrayOf(guestEntry)) }
                catch (suspended: thc.runtime.ThunkSuspended) {
                    thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
                } catch (suspended: thc.runtime.CallSegmentSuspended) {
                    thc.runtime.AsyncContinuations.publicSuspension(suspended, dispatch)
                }
            } catch (failure: Throwable) {
                outcome = thc.runtime.GuestThreadStatus.uncaught(failure)
                throw failure
            } finally { threads.leaveCurrent(outcome) }
            return true
        }
        if (member != "compile") throw UnknownIdentifierException.create(member)
        if (ioTarget != null) throw UnknownIdentifierException.create(member)
        require(arguments.isEmpty()) { "compile takes no arguments" }
        val original = program.entryTarget(entry)
        // The host root is not cloned, but its guest direct call may be split.
        // Compile the targets this stable dispatch tree actually invokes, not
        // only the original target retained by the Haskell closure identity.
        val targets = NodeUtil.findAllNodeInstances(guestTarget.rootNode, DirectCallNode::class.java)
            .filter { it.callTarget === original }.map { it.currentCallTarget }.distinct()
            .ifEmpty { listOf(original) }
        val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        // The executable value enters through this stable bridge. Install it
        // as well as its active guest callees so an explicit host compilation
        // request covers the actual public call path.
        for (target in (targets + guestTarget).distinct()) {
            require(cls.isInstance(target)) { "Graal optimizing Truffle runtime required" }
            cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            check(cls.getMethod("isValidLastTier").invoke(target) == true) { "Guest code was not installed" }
        }
        // HotSpot can retire the shared call-boundary stub while these guest
        // targets remain valid. The pinned runtime hook restores that entry
        // prerequisite without executing guest code or settling a public call.
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, guestTarget)
        return true
    }
}

/** Direct IO dispatch and a bounded polyglot-to-guest entry boundary. */
class HostDispatch : Node() {
    @Child private var calls = TargetCache(Metrics(false))
    // The polyglot Value.execute root is shared across unrelated guest entries.
    // Keep its compiled graph bounded while leaving direct guest-to-guest calls
    // and explicit compilation of the stable guest entry target unchanged.
    @Child private var publicCall = IndirectCallNode.create()
    fun execute(target: RootCallTarget, arguments: Array<Any?>): Any? = calls.call(target, arguments)
    fun executePublic(target: RootCallTarget, arguments: Array<Any?>): Any? = Calls.indirect(publicCall, target, arguments)
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
