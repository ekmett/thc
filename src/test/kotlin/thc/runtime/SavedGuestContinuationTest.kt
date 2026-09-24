// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.executionContext

class SavedGuestContinuationTest {
    private class Driver : RootNode(null) {
        @Child private var force = Force(Metrics(false))
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
        fun force(thunk: Thunk): Any? = Calls.target(callTarget, arrayOf(thunk))
    }

    private class Saved(
        override val sourceRoot: Any,
        override val yielded: Any?,
        private val resume: (Any?) -> Any?
    ) : SavedGuestContinuation {
        override val identity: Any get() = this
        var resumes = 0
        override fun continueWith(input: Any?): Any? {
            resumes++
            return resume(input)
        }
    }

    private fun parked(target: RootCallTarget, saved: Saved): Thunk = Thunk(target, null).apply {
        this.target = null
        this.environment = null
        this.value = saved
        this.state = 5
    }

    @Test fun coldRecordResumesSharedChildBeforeParentAndPublishesOnce() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val original = object : RootNode(null) {
                    override fun execute(frame: VirtualFrame): Any? = fail<Any>("Original body replayed")
                }.callTarget
                val childSaved = Saved(original.rootNode, Unit) { 42L }
                val child = parked(original, childSaved)
                val parentSaved = Saved(original.rootNode, ThunkSuspended(child)) { input ->
                    val completed = input as ChildResume
                    assertNull(completed.failure)
                    assertEquals(42L, completed.value)
                    43L
                }
                val parent = parked(original, parentSaved)
                val driver = Driver()

                assertEquals(43L, driver.force(parent))
                assertEquals(2, parent.state)
                assertEquals(2, child.state)
                assertEquals(1, parentSaved.resumes)
                assertEquals(1, childSaved.resumes)
                assertEquals(43L, driver.force(parent))
                assertEquals(1, parentSaved.resumes)
            } finally { context.leave() }
        }
    }
}
