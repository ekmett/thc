// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.util.Collections
import java.util.concurrent.CancellationException

/** Runtime field projection over an immutable JSON source, not an inspection conversion.
 *
 * The caller retains the source while any projected value is in use. Only demanded
 * scalars are decoded, into this adapter's string pool; the index does not retain a
 * second decoded copy. Executable state remains in each Program. This does not link
 * modules or replace foreign admission, dependency discovery, or the full audit.
 */
internal class CoreJsonBindings(private val sourceNotesEnabled: Boolean) {
    data class Statistics(val bindingHeaders: Long, val bodyMaterializations: Long,
        val expressionViews: Long, val summaryExpressionsVisited: Long, val canonicalStrings: Int,
        val canonicalLists: Int)
    private var bindingHeaders = 0L
    private var bodyMaterializations = 0L
    private var expressionViews = 0L
    private var summaryExpressionsVisited = 0L
    private val strings = HashMap<String, String>()
    private val lists = HashMap<List<Any?>, List<Any?>>()
    @Synchronized fun statistics() = Statistics(bindingHeaders, bodyMaterializations,
        expressionViews, summaryExpressionsVisited, strings.size, lists.size)

    /** One linear traversal of the immediate binding directory, not repeated indexed access. */
    fun bindings(span: CoreJsonIndex.Span): List<Map<String, Any?>> =
        Collections.unmodifiableList(span.elements().map { binding(it) })

    fun binding(span: CoreJsonIndex.Span): Map<String, Any?> = synchronized(this) {
        require(span.kind == CoreJsonIndex.Kind.OBJECT) { "Core binding must be an object" }
        bindingHeaders++
        Record(span, Role.BINDING)
    }

    private enum class Role { BINDING, LOCAL_BINDING, FORMAL, METADATA, FULL }
    private val bindingFields = setOf("id", "name", "type", "lifted", "coercion", "arity", "expr",
        "rep", "entryStrict", "joinValueArity", "joinResultRep", "source", "sourceNotes")
    private val formalFields = setOf("id", "name", "type", "lifted", "coercion", "rep", "source", "sourceNotes")
    private val metadataFields = setOf("rep", "resultRep", "entryStrict", "callDemand", "foreignCall",
        "exceptionPayload", "enumFamily", "dataToTagFamily", "binder", "binders", "source", "sourceNotes")
    private fun fields(role: Role): Set<String>? = when (role) {
        Role.BINDING, Role.LOCAL_BINDING -> bindingFields
        Role.FORMAL -> formalFields
        Role.METADATA -> metadataFields
        Role.FULL -> null
    }
    private fun admitted(role: Role, key: String): Boolean =
        (fields(role)?.contains(key) != false) &&
            (role == Role.FULL || sourceNotesEnabled || key !in setOf("source", "sourceNotes"))

    /** Cache ordinary data errors exactly; cancellation/interruption are not malformed Core. */
    private fun <K, V> memo(cache: MutableMap<K, Result<V>>, key: K, action: () -> V): V {
        cache[key]?.let { return it.getOrThrow() }
        val result = try { Result.success(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (failure: Exception) { Result.failure(failure) }
        cache[key] = result
        return result.getOrThrow()
    }
    private fun scalar(span: CoreJsonIndex.Span): Any? {
        val value = span.decodeUncached()
        return if (value is String) strings.getOrPut(value) { value } else value
    }
    private fun value(span: CoreJsonIndex.Span): Any? = when (span.kind) {
        CoreJsonIndex.Kind.OBJECT -> Record(span, Role.FULL)
        CoreJsonIndex.Kind.ARRAY -> Sequence(span) { _, child -> value(child) }
        else -> scalar(span)
    }
    private fun record(span: CoreJsonIndex.Span, role: Role): Any? =
        if (span.kind == CoreJsonIndex.Kind.OBJECT) Record(span, role) else value(span)

    /** Canonicalize only consumed, immutable scalar layout/strictness vectors.
     * Representation occurrence maps (notably evaluated/source evidence) stay distinct.
     * Nested records are not hashed or traversed merely to intern their parents.
     */
    private fun scalarList(span: CoreJsonIndex.Span): Any? {
        if (span.kind != CoreJsonIndex.Kind.ARRAY) return value(span)
        val result = ArrayList<Any?>()
        var scalarsOnly = true
        for (child in span.elements()) {
            val item = value(child)
            if (item is Map<*, *> || item is List<*>) scalarsOnly = false
            result += item
        }
        val immutable = Collections.unmodifiableList(result)
        return if (scalarsOnly) lists.getOrPut(immutable) { immutable } else immutable
    }

    private inner class Record(private val span: CoreJsonIndex.Span, private val role: Role) : AbstractMap<String, Any?>() {
        private val locations = HashMap<String, Result<CoreJsonIndex.Span?>>()
        private val decodedFields = HashMap<String, Result<Any?>>()
        private var allKeys: Set<String>? = null
        private fun location(key: String): CoreJsonIndex.Span? {
            if (!admitted(role, key)) return null
            return memo(locations, key) { span.member(key) }
        }
        override fun containsKey(key: String): Boolean = synchronized(this@CoreJsonBindings) { location(key) != null }
        override fun get(key: String): Any? = synchronized(this@CoreJsonBindings) {
            memo(decodedFields, key) {
                val child = location(key) ?: return@memo null
                when {
                    key == "expr" && role == Role.BINDING -> body(child)
                    key == "expr" && role == Role.LOCAL_BINDING -> expression(child)
                    role == Role.METADATA && key == "binder" -> record(child, Role.FORMAL)
                    role == Role.METADATA && key == "binders" -> sequence(child) { _, item -> record(item, Role.FORMAL) }
                    key in setOf("entryStrict", "primReps", "strictArgs") -> scalarList(child)
                    else -> value(child)
                }
            }
        }
        override val keys: Set<String> get() = synchronized(this@CoreJsonBindings) {
            allKeys ?: Collections.unmodifiableSet(LinkedHashSet<String>().also { result ->
                val selected = fields(role)
                if (selected == null) {
                    // Exact-key ABI records keep ALL fields, including unknown/forged ones.
                    for ((key, child) in span.members()) {
                        val canonical = strings.getOrPut(key) { key }
                        locations[canonical] = Result.success(child)
                        result += canonical
                    }
                } else for (key in selected) if (location(key) != null) result += key
            }).also { allKeys = it }
        }
        override val entries: Set<Map.Entry<String, Any?>> get() = object : AbstractSet<Map.Entry<String, Any?>>() {
            override val size get() = keys.size
            override fun iterator(): Iterator<Map.Entry<String, Any?>> = keys.iterator().let { cursor ->
                object : Iterator<Map.Entry<String, Any?>> {
                    override fun hasNext() = cursor.hasNext()
                    override fun next(): Map.Entry<String, Any?> = object : Map.Entry<String, Any?> {
                        override val key = cursor.next()
                        override val value get() = get(key)
                        override fun equals(other: Any?) = other is Map.Entry<*, *> && key == other.key && value == other.value
                        override fun hashCode() = key.hashCode() xor (value?.hashCode() ?: 0)
                    }
                }
            }
        }
    }

    /** Only immediate spans are retained, and only once this list itself is requested. */
    private inner class Sequence(span: CoreJsonIndex.Span, private val project: (Int, CoreJsonIndex.Span) -> Any?) : AbstractList<Any?>() {
        private val children = span.elements().toList()
        private val values = HashMap<Int, Result<Any?>>()
        override val size get() = children.size
        override fun get(index: Int): Any? = synchronized(this@CoreJsonBindings) {
            memo(values, index) { project(index, children[index]) }
        }
    }
    private fun sequence(span: CoreJsonIndex.Span, project: (Int, CoreJsonIndex.Span) -> Any?): Any? =
        if (span.kind == CoreJsonIndex.Kind.ARRAY) Sequence(span, project) else value(span)

    private fun metadataIndex(tag: String): Int = when (tag) {
        "var", "prim" -> 2
        "lit", "lam", "con" -> 3
        "app" -> 6
        "let", "case" -> 4
        "void" -> 1
        else -> -1
    }
    private fun child(tag: String, index: Int, span: CoreJsonIndex.Span): Any? = when {
        index == metadataIndex(tag) -> record(span, Role.METADATA)
        tag == "lam" && index == 1 -> sequence(span) { _, item -> record(item, Role.FORMAL) }
        tag == "lam" && index == 2 || tag == "app" && index == 1 ||
            tag == "let" && index == 3 || tag == "case" && index == 1 -> expression(span)
        tag == "app" && index == 2 -> sequence(span) { _, item -> expression(item) }
        tag == "let" && index == 2 -> sequence(span) { _, item -> record(item, Role.LOCAL_BINDING) }
        tag == "case" && index == 3 -> sequence(span) { _, alternative ->
            sequence(alternative) { position, item -> when (position) {
                3 -> expression(item)
                4 -> record(item, Role.METADATA)
                else -> value(item)
            } }
        }
        else -> value(span)
    }
    private fun expression(span: CoreJsonIndex.Span): Any? {
        if (span.kind != CoreJsonIndex.Kind.ARRAY) return value(span)
        val parts = span.elements().toList()
        val head = parts.firstOrNull()?.let(::value)
        val tag = head as? String
        expressionViews++
        return expression(parts, tag, if (parts.isEmpty()) emptyMap() else mapOf(0 to head))
    }
    private fun expression(parts: List<CoreJsonIndex.Span>, tag: String?, prepared: Map<Int, Any?>): List<Any?> =
        object : AbstractList<Any?>() {
            private val values = prepared.mapValuesTo(HashMap<Int, Result<Any?>>()) { Result.success(it.value) }
            override val size get() = parts.size
            override fun get(index: Int): Any? = synchronized(this@CoreJsonBindings) {
                memo(values, index) { if (tag == null) value(parts[index]) else child(tag, index, parts[index]) }
            }
        }

    private fun body(span: CoreJsonIndex.Span): Any? {
        if (span.kind != CoreJsonIndex.Kind.ARRAY) return value(span)
        val parts = span.elements().toList()
        val tag = parts.firstOrNull()?.takeIf { it.kind == CoreJsonIndex.Kind.STRING }?.let(::scalar) as? String
            ?: return expression(span)
        val header = linkedMapOf<Int, Any?>(0 to tag)
        val shallow = if (tag == "lam") listOf(1, metadataIndex(tag)) else listOf(metadataIndex(tag))
        for (index in shallow) if (index in parts.indices) header[index] = child(tag, index, parts[index])
        val containsControl = containsDelimitedControl(span)
        return CoreBindingBody(CoreBindingBody.Header(parts.size, header, containsControl)) {
            synchronized(this) {
                // Check the owner even if all shallow header fields were already projected.
                span.kind
                bodyMaterializations++
                expressionViews++
                expression(parts, tag, header)
            }
        }
    }

    /** Current root policy needs this summary before ANY root is published. Walk
     * only expression positions, not metadata or pretty Core, without decoding
     * body values. This remains real eager structural work, counted separately.
     * Malformed/unknown expressions still fail on demanded runtime admission.
     */
    private fun containsDelimitedControl(root: CoreJsonIndex.Span): Boolean {
        val pending = ArrayDeque<CoreJsonIndex.Span>()
        pending.add(root)
        fun expressions(span: CoreJsonIndex.Span?) {
            if (span?.kind == CoreJsonIndex.Kind.ARRAY) for (item in span.elements()) pending.add(item)
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current.kind != CoreJsonIndex.Kind.ARRAY) continue
            summaryExpressionsVisited++
            val parts = current.elements().toList()
            val tag = parts.firstOrNull()?.takeIf { it.kind == CoreJsonIndex.Kind.STRING } ?: continue
            when {
                tag.stringEquals("prim") -> if (parts.getOrNull(1)?.let {
                    it.kind == CoreJsonIndex.Kind.STRING && (it.stringEquals("prompt#") || it.stringEquals("control0#"))
                } == true) return true
                tag.stringEquals("lam") -> parts.getOrNull(2)?.let(pending::add)
                tag.stringEquals("app") -> { parts.getOrNull(1)?.let(pending::add); expressions(parts.getOrNull(2)) }
                tag.stringEquals("let") -> {
                    parts.getOrNull(3)?.let(pending::add)
                    parts.getOrNull(2)?.takeIf { it.kind == CoreJsonIndex.Kind.ARRAY }?.elements()?.forEach {
                        if (it.kind == CoreJsonIndex.Kind.OBJECT) it.member("expr")?.let(pending::add)
                    }
                }
                tag.stringEquals("case") -> {
                    parts.getOrNull(1)?.let(pending::add)
                    parts.getOrNull(3)?.takeIf { it.kind == CoreJsonIndex.Kind.ARRAY }?.elements()?.forEach {
                        if (it.kind == CoreJsonIndex.Kind.ARRAY) it.elements().asSequence().drop(3).firstOrNull()?.let(pending::add)
                    }
                }
            }
        }
        return false
    }
}
