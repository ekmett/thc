// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Context-owned cold cells. Merely referencing a global reserves a cell; it
 * does not look up a symbol row, decode its header, or lower its body. */
internal class CoreDemandBindings(private val owns: (String) -> Boolean,
    private val readBinding: (String) -> Map<String, Any?>,
    private val readConstructor: (String) -> Map<String, Any?>?,
    private val prepare: (String, Map<String, Any?>) -> ExecutableProgram,
    instrument: Boolean) {
    private val lock = Any()
    val metrics = Metrics(instrument)
    val layouts = mutableMapOf<String, DataLayout>()
    private val definitions = HashMap<String, Map<String, Any?>>()
    private val cells = HashMap<String, GlobalBinding>()
    private val programs = LinkedHashMap<String, ExecutableProgram>()
    private val uses = HashMap<String, MutableList<CoreRepresentation>>()
    private val calls = HashMap<String, MutableList<List<CoreRepresentation>>>()

    fun contains(id: String) = owns(id)
    fun definition(id: String): Map<String, Any?> = synchronized(lock) {
        definitions.getOrPut(id) { readBinding(id).also { require(it["id"] == id) { "Core binding identity mismatch: $id" } } }
    }
    fun cell(id: String): GlobalBinding? = synchronized(lock) {
        if (!owns(id)) return@synchronized null
        cells.getOrPut(id) {
            GlobalBinding(id).also { cell ->
                cell.defer(lock) {
                    val binding = definition(id)
                    validateUses(id, binding)
                    val program = prepare(id, binding)
                    val value = program.entryValue(id)
                    programs[id] = program
                    value
                }
            }
        }
    }
    fun globals(local: Map<String, GlobalBinding>): Map<String, GlobalBinding> = object : AbstractMap<String, GlobalBinding>() {
        override val entries get() = local.entries
        override fun containsKey(key: String) = local.containsKey(key) || this@CoreDemandBindings.contains(key)
        override fun get(key: String) = local[key] ?: cell(key)
    }
    fun constructors(local: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> = object : AbstractMap<String, Map<String, Any?>>() {
        override val entries get() = local.entries
        override fun containsKey(key: String) = get(key) != null
        override fun get(key: String) = local[key] ?: synchronized(lock) { readConstructor(key) }
    }
    /** Occurrence layout selects code generation, but never asserts that an
     * unopened definition is evaluated. Definition-site vector/aggregate checks
     * run before the cell publishes a value to that generated read. */
    fun occurrence(id: String, proof: CoreRepresentation): CoreRepresentation? = synchronized(lock) {
        if (!owns(id)) return@synchronized null
        uses.getOrPut(id) { ArrayList() }.add(proof)
        definitions[id]?.let { validateOccurrence(CoreRepresentations.binder(it), proof) }
        proof.copy(evaluated = false)
    }
    fun call(id: String, arguments: List<CoreRepresentation>) = synchronized(lock) {
        calls.getOrPut(id) { ArrayList() }.add(arguments)
        if (programs.containsKey(id)) validateCall(id, arguments)
    }
    private fun validateOccurrence(expected: CoreRepresentation, actual: CoreRepresentation) {
        CoreVectors.requireVariableProof(expected, actual)
        if (expected.isTypedTransport || actual.isTypedTransport) {
            if (!expected.isTypedTransport || !actual.isTypedTransport || !TupleShape.compatible(expected, actual))
                throw UnsupportedCore("Conflicting demanded global representation proof")
        }
    }
    private fun signature(id: String): List<CoreRepresentation>? {
        val seen = HashSet<String>()
        fun resolve(expr: List<Any?>): List<CoreRepresentation>? = when (expr.firstOrNull()) {
            "lam" -> (expr[1] as List<Map<String, Any?>>).map(CoreRepresentations::binder)
            "var" -> (expr[1] as String).let { next ->
                if (!owns(next) || !seen.add(next)) null else resolve(definition(next)["expr"] as List<Any?>)
            }
            "app" -> resolve(expr[1] as List<Any?>)?.let { inputs ->
                val supplied = (expr[2] as List<*>).size
                if (supplied < inputs.size) inputs.drop(supplied) else null
            }
            else -> null
        }
        seen.add(id)
        return resolve(definition(id)["expr"] as List<Any?>)
    }
    private fun validateCall(id: String, arguments: List<CoreRepresentation>) {
        signature(id)?.let { expected -> CoreInputCalls.requireArguments(expected, arguments) }
    }
    private fun validateUses(id: String, binding: Map<String, Any?>) {
        val proof = CoreRepresentations.binder(binding)
        uses[id]?.forEach { validateOccurrence(proof, it) }
        calls[id]?.forEach { validateCall(id, it) }
    }
    fun program(id: String): ExecutableProgram = synchronized(lock) {
        val selected = cell(id) ?: throw UnsupportedCore("Unresolved external binding $id")
        selected.read()
        programs.getValue(id)
    }
    fun preparedPrograms(): List<ExecutableProgram> = synchronized(lock) { programs.values.toList() }
}
