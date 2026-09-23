@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.Json

/** Pinned GHC signatures are checked while lowering, never during guest execution. */
internal object ScalarPrimitiveSignatures {
    private data class Signature(val arguments: List<String>, val result: String)
    private val signatures: Map<String, Signature> = run {
        val stream = ScalarPrimitiveSignatures::class.java.getResourceAsStream("/thc/scalar-primop-signatures.json")
            ?: error("Missing scalar primitive signature contract")
        val table = stream.bufferedReader().use { Json.parse(it.readText()) } as Map<String, Any?>
        check(table["ghc"] == "9.14.1" && table["schema"] == 1L && table["targetWordSize"] == 64L)
        (table["primitives"] as Map<String, Map<String, Any?>>).mapValues { (_, signature) ->
            Signature(signature["arguments"] as List<String>, signature["result"] as String)
        }
    }

    fun validate(name: String, arguments: List<CoreRepresentation>, result: CoreRepresentation) {
        val signature = signatures[name] ?: return
        if (arguments.size != signature.arguments.size) throw RuntimeFault("Primitive arity mismatch: $name")
        arguments.forEachIndexed { index, proof -> requireProof(name, "argument $index", signature.arguments[index], proof) }
        requireProof(name, "result", signature.result, result)
    }

    private fun requireProof(name: String, position: String, expected: String, proof: CoreRepresentation) {
        // Missing/unknown legacy metadata is not a contradictory exact type proof.
        // Casts through genuine scalar newtypes preserve the same primitive rep.
        if (!proof.present || proof.primReps == null || proof.kind == CoreKind.UNKNOWN && !proof.isAggregate && !proof.isVector) return
        if (proof.isAggregate || proof.isVector || proof.primReps != listOf(expected))
            throw RuntimeFault("Primitive representation mismatch: $name $position expects $expected, found ${proof.primReps}")
    }
}
