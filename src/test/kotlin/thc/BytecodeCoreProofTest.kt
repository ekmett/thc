package thc

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private typealias ProofExpr = List<Any?>

/** Core proofs and lexical joins must remain sound at the public bytecode boundary. */
class BytecodeCoreProofTest {
    private val longRep = rep("long", listOf("IntRep"), true)
    private val dataRep = rep("data", listOf("BoxedRep (Just Lifted)"), true)
    private val closureRep = rep("closure", listOf("BoxedRep (Just Lifted)"), true)
    private val addressRep = rep("address", listOf("AddrRep"), true)
    private fun rep(kind: String, registers: List<String>, evaluated: Boolean) =
        mapOf("kind" to kind, "primReps" to registers, "evaluated" to evaluated)
    private fun meta(proof: Map<String, Any?>) = mapOf("rep" to proof)
    private fun variable(id: String, proof: Map<String, Any?> = longRep): ProofExpr = listOf("var", id, meta(proof))
    private fun integer(n: Long): ProofExpr = listOf("lit", "int", n.toString(), meta(longRep))
    private fun binder(id: String, proof: Map<String, Any?> = longRep, lifted: Boolean = false): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "type" to "Synthetic", "lifted" to lifted, "coercion" to false, "rep" to proof)
    private fun lambda(parameters: List<Map<String, Any?>>, body: ProofExpr,
                       result: Map<String, Any?> = longRep): ProofExpr =
        listOf("lam", parameters, body, mapOf("rep" to closureRep, "resultRep" to result))
    private fun apply(function: ProofExpr, args: List<ProofExpr>, flags: List<Boolean> = List(args.size) { false },
                      result: Map<String, Any?> = longRep): ProofExpr =
        listOf("app", function, args, flags, false, false, meta(result))
    private fun primitive(name: String, vararg args: ProofExpr): ProofExpr = apply(listOf("prim", name), args.toList())
    private fun binding(id: String, rhs: ProofExpr, lifted: Boolean = true,
                        proof: Map<String, Any?> = closureRep): Map<String, Any?> =
        binder(id, proof, lifted) + mapOf("arity" to (if (rhs[0] == "lam") (rhs[1] as List<*>).size else 0), "expr" to rhs)
    private fun join(id: String, parameters: List<Map<String, Any?>>, body: ProofExpr,
                     valueArity: Int = parameters.size, result: Map<String, Any?> = longRep): Map<String, Any?> =
        binding(id, lambda(parameters, body)) + mapOf("joinValueArity" to valueArity, "joinResultRep" to result)
    private fun local(group: List<Map<String, Any?>>, body: ProofExpr, recursive: Boolean = false,
                      result: Map<String, Any?> = longRep): ProofExpr = listOf("let", recursive, group, body, meta(result))
    private fun caseOf(value: ProofExpr, id: String, alternatives: List<ProofExpr>,
                       inputRep: Map<String, Any?> = longRep, result: Map<String, Any?> = longRep): ProofExpr =
        listOf("case", value, id, alternatives, mapOf("rep" to result, "binder" to binder(id, inputRep, inputRep["kind"] == "data")))
    private fun chooseZero(value: ProofExpr, yes: ProofExpr, no: ProofExpr): ProofExpr = caseOf(value, "choice", listOf(
        listOf("lit", listOf("int", "0"), emptyList<String>(), yes),
        listOf("default", null, emptyList<String>(), no)))
    private val box = mapOf("id" to "Box", "name" to "Box", "arity" to 1, "tag" to 1, "kind" to "boxed",
        "strictFields" to listOf(false), "fieldLifted" to listOf(false), "fieldReps" to listOf(listOf("IntRep")))
    private fun construct(n: ProofExpr): ProofExpr = apply(listOf("con", "Box", 1, meta(closureRep)), listOf(n), result = dataRep)
    private fun unpack(value: ProofExpr, body: ProofExpr): ProofExpr = caseOf(value, "boxValue", listOf(
        listOf("data", "Box", listOf("field"), body, mapOf("binders" to listOf(binder("field"))))), inputRep = dataRep)
    private fun request(body: ProofExpr, extras: List<Map<String, Any?>> = emptyList(), diagnostic: Boolean = false,
                        constructors: List<Map<String, Any?>> = listOf(box)): String = Json.stringify(
        mapOf("entry" to "entry", "backend" to "bytecode", "instrument" to true, "diagnosticUnsupported" to diagnostic,
            "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.BytecodeProofs",
                "constructors" to constructors, "bindings" to extras + binding("entry", lambda(listOf(binder("input")), body))))))
    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(fn: Value) = Json.parse(fn.getMember("diagnostics").asString()) as Map<String, Any?>
    private fun count(fn: Value, key: String) = (diagnostics(fn)[key] as Number).toLong()
    private fun compile(fn: Value, input: Long, expected: Long) {
        repeat(30) { assertEquals(expected, fn.execute(input).asLong()) }
        assertTrue(fn.invokeMember("compile").asBoolean())
        assertEquals(expected, fn.execute(input).asLong())
        assertTrue(count(fn, "compiledEntries") > 0)
    }

    @Test fun primitiveIdentityCompilesAfterOneOrdinaryInvocation() {
        executionContext().use { context ->
            val fn = context.eval("thc", request(variable("input")))
            assertEquals(3_000_000_000L, fn.execute(3_000_000_000L).asLong())
            assertFalse(fn.getMember("bytecode").asString().contains("c.Force"))
            assertTrue(fn.invokeMember("compile").asBoolean())
            assertEquals(Long.MIN_VALUE, fn.execute(Long.MIN_VALUE).asLong())
            assertTrue(count(fn, "compiledEntries") > 0)
        }
    }

    @Test fun diagnosticGlobalCannotRetainTheUnsupportedConstructorWhnfProof() {
        val unsupported = binding("unsupported", listOf("con", "UnsupportedTuple", 0, meta(dataRep)), proof = dataRep)
        val body = chooseZero(variable("input"), integer(42), unpack(variable("unsupported", dataRep), variable("field")))
        executionContext().use { context ->
            val fn = context.eval("thc", request(body, listOf(unsupported), diagnostic = true,
                constructors = listOf(box, mapOf("id" to "UnsupportedTuple", "kind" to "unboxed-tuple"))))
            compile(fn, 0, 42)
            val error = assertThrows(PolyglotException::class.java) { fn.execute(1L) }
            assertTrue(error.message.orEmpty().contains("Diagnostic unsupported path reached"), error.message)
            assertEquals(1L, count(fn, "unsupportedTraps"))
        }
    }

    @Test fun provenLongCaseAndLetOmitAdaptiveForcingInstructions() {
        val rhs = chooseZero(variable("input"), integer(Long.MIN_VALUE), primitive("*#", variable("input"), integer(3)))
        val body = local(listOf(binding("saved", rhs, false, longRep)), primitive("+#", variable("saved"), integer(17)))
        executionContext().use { context ->
            val fn = context.eval("thc", request(body))
            compile(fn, 0, Long.MIN_VALUE + 17)
            assertEquals(38L, fn.execute(7L).asLong())
            val dump = fn.getMember("bytecode").asString()
            assertFalse(dump.contains("c.ForceValue"), dump)
            assertFalse(dump.contains("c.ForceLocal"), dump)
            assertFalse(dump.contains("c.ReadCellIfNeeded"), dump)
        }
    }

    @Test fun capturedAddressesRemainObjectsAndCapturedLongsUsePrimitiveReads() {
        val address: ProofExpr = listOf("lit", "string-bytes", "61626300", meta(addressRep))
        val captured = lambda(listOf(binder("offset")), primitive("+#",
            primitive("indexCharOffAddr#", variable("address", addressRep), variable("offset")), variable("saved")))
        val body = local(listOf(binding("address", address, false, addressRep)),
            local(listOf(binding("saved", integer(17), false, longRep)), apply(captured, listOf(variable("input")))))
        executionContext().use { context ->
            val fn = context.eval("thc", request(body))
            compile(fn, 0, 114)
            assertEquals(116L, fn.execute(2L).asLong())
            assertTrue(fn.getMember("bytecode").asString().contains("c.CaptureReadLong"))
        }
    }

    @Test fun nestedNonrecursiveJoinsBranchToAncestorsWithoutLoopScaffolding() {
        val outer = join("finish", listOf(binder("answer")), primitive("+#", variable("answer"), integer(17)))
        var inner = apply(variable("finish", closureRep), listOf(variable("input")))
        repeat(25) { index ->
            val name = "step$index"
            inner = local(listOf(join(name, listOf(binder("unused$index")), inner)),
                apply(variable(name, closureRep), listOf(variable("input"))))
        }
        val body = local(listOf(outer), inner)
        executionContext().use { context ->
            val fn = context.eval("thc", request(body))
            compile(fn, 3_000_000_000L, 3_000_000_017L)
            for (input in listOf(Long.MIN_VALUE, -4097L, 0L, Long.MAX_VALUE)) {
                val before = count(fn, "compiledEntries")
                assertEquals(input + 17, fn.execute(input).asLong())
                assertTrue(count(fn, "compiledEntries") > before)
            }
            val dump = fn.getMember("bytecode").asString()
            assertFalse(dump.contains("join selector"), dump)
            assertFalse(dump.contains("c.Apply"), dump)
            assertEquals(26L, count(fn, "localJoinCount"))
            assertEquals(2L, count(fn, "bytecodeRootCount"))
            assertEquals(0L, count(fn, "trampolineIterations"))
        }
    }

    @Test fun recursiveJoinUsesParallelMovesAndPreservesNonTailContinuation() {
        val body = chooseZero(variable("n"), primitive("+#", primitive("*#", variable("x"), integer(100)), variable("y")),
            apply(variable("loop", closureRep), listOf(primitive("-#", variable("n"), integer(1)), variable("y"), variable("x"))))
        val region = local(listOf(join("loop", listOf(binder("n"), binder("x"), binder("y")), body)),
            apply(variable("loop", closureRep), listOf(variable("input"), integer(1), integer(2))), true)
        executionContext().use { context ->
            val fn = context.eval("thc", request(primitive("+#", integer(17), region)))
            compile(fn, 0, 119)
            assertEquals(218L, fn.execute(100_001L).asLong())
            assertEquals(119L, fn.execute(100_000L).asLong())
            assertEquals(2L, count(fn, "bytecodeRootCount"), "Join must not allocate a guest call target")
            assertEquals(1L, count(fn, "localJoinCount"))
            assertTrue(count(fn, "localJoinTransfers") >= 200_003L)
            assertEquals(0L, count(fn, "tailBounces"))
            assertEquals(0L, count(fn, "papAllocations"))
            assertFalse(fn.getMember("bytecode").asString().contains("c.Apply"))
        }
    }

    @Test fun mutuallyRecursiveJoinsStayInsideTheirOwningRoot() {
        fun worker(other: String): ProofExpr = chooseZero(variable("n"), variable("acc"), apply(variable(other, closureRep),
            listOf(primitive("-#", variable("n"), integer(1)), primitive("+#", variable("acc"), integer(1)))))
        val region = local(listOf(join("left", listOf(binder("n"), binder("acc")), worker("right")),
            join("right", listOf(binder("n"), binder("acc")), worker("left"))),
            apply(variable("left", closureRep), listOf(variable("input"), integer(0))), true)
        executionContext().use { context ->
            val fn = context.eval("thc", request(primitive("+#", region, integer(17))))
            compile(fn, 0, 17)
            assertEquals(100_018L, fn.execute(100_001L).asLong())
            assertEquals(2L, count(fn, "bytecodeRootCount"))
            assertEquals(2L, count(fn, "localJoinCount"))
            assertEquals(0L, count(fn, "trampolineIterations"))
            assertFalse(fn.getMember("bytecode").asString().contains("c.Apply"))
        }
    }

    @Test fun adjustedJoinArityCanReturnTheRestOfAFlattenedLambda() {
        for (arity in listOf(0, 1)) {
            val parameters = if (arity == 0) listOf(binder("extra")) else listOf(binder("first"), binder("extra"))
            val sum = primitive("+#", variable(if (arity == 0) "input" else "first"), variable("extra"))
            val declaration = join("answer", parameters, sum, arity, closureRep)
            val jump = if (arity == 0) variable("answer", closureRep) else apply(variable("answer", closureRep), listOf(variable("input")), result = closureRep)
            val region = local(listOf(declaration), jump, result = closureRep)
            executionContext().use { context ->
                val fn = context.eval("thc", request(apply(region, listOf(integer(17)))))
                compile(fn, 1, 18)
                assertEquals(42L, fn.execute(25L).asLong())
                assertEquals(1L, count(fn, "localJoinCount"))
                assertEquals(3L, count(fn, "bytecodeRootCount"), "Only the returned function needs a new root")
                assertEquals(0L, count(fn, "papAllocations"))
            }
        }
    }

    @Test fun evaluatedCoreVarsStillForceOurCafAndRecursiveAliasThunks() {
        val caf = binding("caf", construct(integer(9)), proof = dataRep)
        val fromCaf = unpack(variable("caf", dataRep), primitive("+#", variable("field"), variable("input")))
        val group = listOf(binding("cell", construct(integer(9)), proof = dataRep),
            binding("alias", variable("cell", dataRep), proof = dataRep))
        val fromAlias = local(group, unpack(variable("alias", dataRep), primitive("+#", variable("field"), variable("input"))), true)
        for ((body, globals) in listOf(fromCaf to listOf(caf), fromAlias to emptyList())) {
            executionContext().use { context ->
                val fn = context.eval("thc", request(body, globals))
                compile(fn, 1, 10)
                assertEquals(16L, fn.execute(7L).asLong())
                assertTrue(count(fn, "thunkEvaluations") > 0)
                assertEquals(0L, count(fn, "blackholes"))
            }
        }
    }

    @Test fun malformedJoinsAreRejectedEvenInDiagnosticMode() {
        val declaration = join("j", listOf(binder("n")), variable("n"))
        val bad = listOf(variable("j", closureRep),
            primitive("+#", apply(variable("j", closureRep), listOf(variable("input"))), integer(1)),
            apply(variable("j", closureRep), listOf(variable("input"), integer(2))),
            lambda(listOf(binder("later")), apply(variable("j", closureRep), listOf(variable("later")))))
        for (body in bad) executionContext().use { context ->
            val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request(local(listOf(declaration), body), diagnostic = true)) }
            assertTrue(error.message.orEmpty().contains("join", ignoreCase = true), error.message)
        }
    }

    @Test fun diagnosticSubstitutionCannotAcquireItsOriginalLongWhnfProof() {
        val unsupported = primitive("notARealPrim#", integer(1))
        val body = primitive("+#", chooseZero(variable("input"), integer(41), unsupported), integer(1))
        executionContext().use { context ->
            val fn = context.eval("thc", request(body, diagnostic = true))
            compile(fn, 0, 42)
            val error = assertThrows(PolyglotException::class.java) { fn.execute(1L) }
            assertTrue(error.message.orEmpty().contains("Diagnostic unsupported path reached"), error.message)
            assertEquals(1L, count(fn, "unsupportedTraps"))
        }
    }
}
