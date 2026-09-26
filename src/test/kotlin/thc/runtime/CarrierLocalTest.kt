// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CarrierLocalTest {
    @Test fun adapterAndCompiledCellShareUpdatesAndRemoveResetsAnExistingCell() {
        val local = CarrierLocal(0)
        val cell = local.cell(Thread.currentThread())
        assertEquals(0, local.get())
        local.set(17)
        assertEquals(17, cell.value)
        cell.value = 23
        assertEquals(23, local.get())
        local.remove()
        assertEquals(0, cell.value)
        assertEquals(0, local.get())
        assertSame(cell, local.cell(Thread.currentThread()))
    }

    @Test fun foreignCarrierFactorySharesItsCellWithoutSharingAnotherContextOrThread() {
        val local = CarrierLocal(0)
        val other = CarrierLocal(0)
        local.set(17)
        val result = CompletableFuture<Int>()
        val worker = Thread {
            try {
                assertEquals(0, local.get())
                local.set(23)
                result.complete(local.get())
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        val workerCell = local.cell(worker)
        assertNotSame(workerCell, local.cell(Thread.currentThread()))
        assertNotSame(workerCell, other.cell(worker))
        worker.start()
        assertEquals(23, result.get(5, TimeUnit.SECONDS))
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(23, workerCell.value)
        assertEquals(17, local.get())
        assertEquals(0, other.get())
    }
}
