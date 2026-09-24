// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Constructor worker metadata describes the representation after its CBV obligations. */
internal class CoreFields(info: Map<String, Any?>) {
    val storage: Array<String>
    val referenceTypes: Array<Class<*>?>

    init {
        val id = info["id"]
        if ((info["kind"] ?: "boxed") != "boxed")
            throw UnsupportedCore("Unsupported constructor representation ${info["kind"]}: $id")
        val arityValue = info["arity"] as? Number ?: throw RuntimeFault("Missing constructor arity: $id")
        val arity = arityValue.toInt()
        if (arity < 0 || arityValue.toDouble() != arity.toDouble()) throw RuntimeFault("Invalid constructor arity: $id")
        val reps = info["fieldReps"] as? List<*> ?: throw RuntimeFault("Missing constructor primitive representations: $id")
        if (reps.size != arity) throw RuntimeFault("Constructor representation count mismatch: $id")
        storage = reps.map { field ->
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
        // AddrRep has one managed carrier even in older exports lacking fieldTypes.
        // It must never become a generic reference property or a native pointer.
        referenceTypes = Array(arity) { if (storage[it] == "AddrRep") ManagedAddress::class.java else null }
        for (index in storage.indices) if (storage[index] == "AddrRep") {
            if (info.containsKey("fieldLifted")) {
                val lifted = info["fieldLifted"] as? List<*>
                    ?: throw RuntimeFault("Invalid address constructor levity: $id field $index")
                if (lifted.size != arity || lifted[index] != false)
                    throw RuntimeFault("Address constructor field must be unlifted: $id field $index")
            }
        }
        if (info.containsKey("fieldTypes")) {
            val types = info["fieldTypes"] as? List<*> ?: throw RuntimeFault("Invalid constructor field types: $id")
            val strict = info["strictFields"] as? List<*> ?: throw RuntimeFault("Missing constructor strictness metadata: $id")
            val lifted = info["fieldLifted"] as? List<*> ?: throw RuntimeFault("Missing constructor representation metadata: $id")
            if (types.size != arity || strict.size != arity || lifted.size != arity)
                throw RuntimeFault("Constructor field type count mismatch: $id")
            for (index in types.indices) {
                val proof = CoreRepresentations.parse(types[index])
                CoreRepresentations.requireScalar(proof, "constructor field")
                if (!proof.present || proof.primReps != reps[index])
                    throw RuntimeFault("Constructor field type disagrees with its primitive representation: $id field $index")
                if (storage[index] == "AddrRep" && proof.kind != CoreKind.ADDRESS)
                    throw RuntimeFault("Address constructor field lacks its exact managed carrier: $id field $index")
                val strictField = strict[index] as? Boolean ?: throw RuntimeFault("Unknown constructor field strictness: $id")
                val expectedLifted = storage[index] == "LiftedRep"
                if (lifted[index] != expectedLifted)
                    throw RuntimeFault("Constructor field levity disagrees with its primitive representation: $id field $index")
                if (proof.evaluated != (strictField || lifted[index] == false))
                    throw RuntimeFault("Constructor field evaluatedness lacks a worker obligation: $id field $index")
                referenceTypes[index] = proof.referenceCarrier()
            }
        }
    }
}
