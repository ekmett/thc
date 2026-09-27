// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

class BytecodeStaticEntryTest {
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun compile(target: RootCallTarget) {
        val type = target.javaClass
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode",
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun allThreeStatelessOperationsMatchBoundaryArithmeticOnTheirFirstCompiledCall() = withLanguage { language ->
        for (operation in 0..2) {
            val metrics = Metrics(true)
            val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                b.beginRoot(); b.emitEnterRoot(metrics); b.beginReturn()
                b.beginStaticLongArithmetic(operation)
                b.emitLoadArgument(0); b.emitLoadArgument(1)
                b.endStaticLongArithmetic(); b.endReturn(); b.endRoot()
            }.getNode(0)
            val target = root.callTarget
            compile(target)
            assertEquals(0L, metrics.compiledEntries)
            for ((left, right) in listOf(Long.MAX_VALUE to 2L, Long.MIN_VALUE to -1L, 0L to 0L)) {
                val expected = when (operation) { 0 -> left + right; 1 -> left - right; else -> left * right }
                val before = metrics.compiledEntries
                assertEquals(expected, Calls.target(target, arrayOf(left, right)))
                assertEquals(before + 1, metrics.compiledEntries)
                valid(target)
            }
        }
    }

    @Test fun staticLocalAndOperationsEnterColdCompiledCodeThenRetainSourceReplayAndCloneMetadata() = withLanguage { language ->
        var sourceReads = 0
        val metrics = Metrics(true)
        val certificate = FrameSlotKind.Long
        val nodes = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            if (b.isParsingSources()) {
                sourceReads++
                b.beginSource(Source.newBuilder("thc", "f x = x + 2", "Cold.hs").build())
                b.beginSourceSection(0, 11)
            }
            b.beginRoot()
            val local = b.createLocal("x", certificate)
            b.emitEnterRoot(metrics)
            b.beginStaticStoreLong(local); b.emitLoadArgument(0); b.endStaticStoreLong()
            b.beginReturn(); b.beginStaticLongArithmetic(0)
            b.emitStaticLoadLong(local); b.emitLoadConstant(2L)
            b.endStaticLongArithmetic(); b.endReturn(); b.endRoot()
            if (b.isParsingSources()) { b.endSourceSection(); b.endSource() }
        }
        val root = nodes.getNode(0)
        val target = root.callTarget
        assertEquals(0, sourceReads)
        assertEquals(0L, metrics.compiledEntries)
        compile(target)
        assertEquals(FrameSlotKind.Long, root.bytecodeNode.locals.single().typeProfile)
        assertEquals(42L, Calls.target(target, arrayOf(40L)))
        assertEquals(1L, metrics.compiledEntries)
        valid(target)
        assertEquals(0, sourceReads)
        fun instructions() = root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) }
        val code = instructions()
        root.bytecodeNode.ensureSourceInformation()
        assertEquals(1, sourceReads)
        assertEquals(code, instructions())
        assertSame(target, root.callTarget)
        assertSame(certificate, root.bytecodeNode.locals.single().info)
        assertEquals(Long.MIN_VALUE + 2, Calls.target(target, arrayOf(Long.MIN_VALUE)))
        val clone = root.javaClass.getDeclaredMethod("cloneUninitialized").apply { isAccessible = true }.invoke(root) as BytecodeRoot
        val clonedTarget = clone.callTarget
        assertNotSame(target, clonedTarget)
        assertSame(certificate, clone.bytecodeNode.locals.single().info)
        val before = metrics.compiledEntries
        compile(clonedTarget)
        assertEquals(Long.MIN_VALUE + 1, Calls.target(clonedTarget, arrayOf(Long.MAX_VALUE)))
        assertEquals(before + 1, metrics.compiledEntries)
        valid(clonedTarget)
    }

    @Test fun realIngressChecksWrongCarriersAndExistingAdaptiveWritesStillWiden() = withLanguage { language ->
        val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            val local = b.createLocal("initial long", FrameSlotKind.Long)
            b.beginStaticStoreLong(local); b.emitLoadArgument(0); b.endStaticStoreLong()
            // Deliberately model an uncertified writer: ordinary DSL widening is retained.
            b.beginStoreLocal(local); b.emitLoadArgument(1); b.endStoreLocal()
            b.beginReturn(); b.emitLoadLocal(local); b.endReturn(); b.endRoot()
        }.getNode(0)
        val target = root.callTarget
        assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf<Any?>("wrong", 0L)) }
        val marker = Any()
        assertSame(marker, Calls.target(target, arrayOf(1L, marker)))
        assertEquals(FrameSlotKind.Object, root.bytecodeNode.locals.single().typeProfile)
        assertEquals(3L, Calls.target(target, arrayOf(2L, 3L)))
        compile(target)
        assertEquals(FrameSlotKind.Object, root.bytecodeNode.locals.single().typeProfile)
        assertEquals(5L, Calls.target(target, arrayOf(4L, 5L)))
        assertEquals(FrameSlotKind.Object, root.bytecodeNode.locals.single().typeProfile)
    }

    @Test fun declaredObjectScratchIsColdStatelessAndRetainsReplayAndCloneMetadata() = withLanguage { language ->
        val metrics = Metrics(true)
        var sourceReads = 0
        val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            if (b.isParsingSources()) {
                sourceReads++
                b.beginSource(Source.newBuilder("thc", "scratch", "Scratch.hs").build())
                b.beginSourceSection(0, 7)
            }
            b.beginRoot(); b.emitEnterRoot(metrics)
            val local = b.createLocal("scratch", FrameSlotKind.Object)
            b.beginStaticStoreObject(local); b.emitLoadArgument(0); b.endStaticStoreObject()
            b.beginReturn(); b.emitStaticLoadObject(local); b.endReturn(); b.endRoot()
            if (b.isParsingSources()) { b.endSourceSection(); b.endSource() }
        }.getNode(0)
        val target = root.callTarget
        compile(target)
        assertEquals(0, sourceReads)
        assertEquals(0L, metrics.compiledEntries)
        assertEquals(FrameSlotKind.Object, root.bytecodeNode.locals.single().typeProfile)
        for (value in listOf(Any(), 1L, "value", null)) {
            val before = metrics.compiledEntries
            assertSame(value, Calls.target(target, arrayOf(value)))
            assertEquals(before + 1, metrics.compiledEntries)
            valid(target)
        }
        val code = root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) }
        root.bytecodeNode.ensureSourceInformation()
        assertEquals(1, sourceReads)
        assertSame(target, root.callTarget)
        assertEquals(code, root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) })
        assertSame(FrameSlotKind.Object, root.bytecodeNode.locals.single().info)
        val clone = root.javaClass.getDeclaredMethod("cloneUninitialized").apply { isAccessible = true }.invoke(root) as BytecodeRoot
        compile(clone.callTarget)
        assertSame(FrameSlotKind.Object, clone.bytecodeNode.locals.single().info)
        assertEquals(FrameSlotKind.Object, clone.bytecodeNode.locals.single().typeProfile)
        val value = Any()
        assertSame(value, Calls.target(clone.callTarget, arrayOf(value)))
        valid(clone.callTarget)
    }

    @Test fun compilerCertifiesOnlyExactWideSingleWriteFormalsAndKeepsUnknownAndNarrowInputsGeneric() = withLanguage { language ->
        for (marker in listOf(FrameSlotKind.Int, "object", "primitive", null)) {
            val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                b.beginRoot()
                val local = b.createLocal("unapproved marker", marker)
                b.beginStoreLocal(local); b.emitLoadArgument(0); b.endStoreLocal()
                b.beginReturn(); b.emitLoadLocal(local); b.endReturn(); b.endRoot()
            }.getNode(0)
            compile(root.callTarget)
            assertEquals(FrameSlotKind.Illegal, root.bytecodeNode.locals.single().typeProfile,
                "Only the explicitly approved singleton may initialize a local: $marker")
        }
        fun program(rep: Map<String, Any?>, lifted: Boolean, async: Boolean): BytecodeProgram {
            val binder = mapOf("id" to "x", "name" to "x", "rep" to rep, "lifted" to lifted)
            val expression = listOf("lam", listOf(binder), listOf("var", "x", mapOf("rep" to rep)),
                mapOf("rep" to mapOf("kind" to "closure", "evaluated" to true,
                    "primReps" to listOf("BoxedRep (Just Lifted)")), "resultRep" to rep))
            return BytecodeProgram(language, mapOf("bindings" to listOf(mapOf("id" to "f", "name" to "f",
                "arity" to 1L, "lifted" to true, "expr" to expression)), "constructors" to emptyList<Any>()), async)
        }
        val wide = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        for (async in listOf(false, true)) {
            val selected = program(wide, false, async).entryTarget("f").rootNode as BytecodeRoot
            assertSame(FrameSlotKind.Long, selected.bytecodeNode.locals.single { it.name == "x" }.info)
        }
        for ((rep, lifted, async) in listOf(Triple(mapOf("kind" to "unknown", "evaluated" to false), true, false),
            Triple(mapOf("kind" to "long", "primReps" to listOf("Int32Rep"), "evaluated" to true), false, false))) {
            val root = program(rep, lifted, async).entryTarget("f").rootNode as BytecodeRoot
            assertTrue(root.bytecodeNode.locals.none { it.info === FrameSlotKind.Long })
        }
    }

    @Test fun unprofiledBooleanBranchRetainsItsFirstCompiledBothArmsAndReplay() = withLanguage { language ->
        val layout = DataLayout(language, "BranchTrue", "BranchTrue", emptyArray())
        val trueValue = layout.create(emptyArray())
        val falseValue = DataLayout(language, "BranchFalse", "BranchFalse", emptyArray()).create(emptyArray())
        for (primitiveCondition in listOf(false, true)) {
            val metrics = Metrics(true)
            var sourceReads = 0
            val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                if (b.isParsingSources()) {
                    sourceReads++
                    b.beginSource(Source.newBuilder("thc", "if p then 11 else 22", "ColdBranch.hs").build())
                    b.beginSourceSection(0, 20)
                }
                b.beginRoot(); b.emitEnterRoot(metrics)
                b.beginUnprofiledIfThen()
                // MatchData accepts an Object without an adaptive scalar
                // specialization and produces a primitive Boolean. Literal
                // tests have their own cold specialization boundary.
                if (primitiveCondition) b.beginMatchData(layout)
                b.emitLoadArgument(0)
                if (primitiveCondition) b.endMatchData()
                b.beginReturn(); b.emitLoadConstant(11L); b.endReturn()
                b.endUnprofiledIfThen()
                b.beginReturn(); b.emitLoadConstant(22L); b.endReturn()
                b.endRoot()
                if (b.isParsingSources()) { b.endSourceSection(); b.endSource() }
            }.getNode(0)
            val target = root.callTarget
            fun code() = root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) }
            assertTrue(code().any { it.first == "branch.false.unprofiled" })
            compile(target)
            // Cold compilation creates cached operation nodes but executes no
            // guest bytecode. Compare that prepared view before the FIRST call.
            val beforeCode = code()
            assertEquals(0L, metrics.compiledEntries)
            for ((index, condition) in listOf(true, false, true).withIndex()) {
                val input: Any = if (primitiveCondition) { if (condition) trueValue else falseValue } else condition
                assertEquals(if (condition) 11L else 22L, Calls.target(target, arrayOf(input)))
                assertEquals(index + 1L, metrics.compiledEntries,
                    "primitiveCondition=$primitiveCondition, call=$index, condition=$condition")
                valid(target)
            }
            assertEquals(beforeCode, code(), "Unprofiled branch does not require observed quickening")
            root.bytecodeNode.ensureSourceInformation()
            assertEquals(1, sourceReads)
            assertEquals(beforeCode, code())
            val clone = root.javaClass.getDeclaredMethod("cloneUninitialized").apply { isAccessible = true }.invoke(root) as BytecodeRoot
            val clonedTarget = clone.callTarget
            compile(clonedTarget)
            val before = metrics.compiledEntries
            val input: Any = if (primitiveCondition) falseValue else false
            assertEquals(22L, Calls.target(clonedTarget, arrayOf(input)))
            assertEquals(before + 1, metrics.compiledEntries)
            valid(clonedTarget)
            if (!primitiveCondition) {
                assertThrows(ClassCastException::class.java) { Calls.target(target, arrayOf<Any?>("not Boolean")) }
                assertThrows(NullPointerException::class.java) { Calls.target(target, arrayOfNulls<Any>(1)) }
            }
        }
    }
}
