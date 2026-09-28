// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarrierLocalTest {
    @Test void adapterAndCompiledCellShareUpdatesAndRemoveResetsAnExistingCell() {
        var local = new CarrierLocal<>(0);
        var cell = local.cell(Thread.currentThread());
        assertEquals(0, local.get());
        local.set(17);
        assertEquals(17, cell.getValue());
        cell.setValue(23);
        assertEquals(23, local.get());
        local.remove();
        assertEquals(0, cell.getValue());
        assertEquals(0, local.get());
        assertSame(cell, local.cell(Thread.currentThread()));
    }

    @Test void foreignCarrierFactorySharesItsCellWithoutSharingAnotherContextOrThread() throws Exception {
        var local = new CarrierLocal<>(0);
        var other = new CarrierLocal<>(0);
        local.set(17);
        var result = new CompletableFuture<Integer>();
        var worker = new Thread(() -> {
            try {
                assertEquals(0, local.get());
                local.set(23);
                result.complete(local.get());
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        var workerCell = local.cell(worker);
        assertNotSame(workerCell, local.cell(Thread.currentThread()));
        assertNotSame(workerCell, other.cell(worker));
        worker.start();
        assertEquals(23, result.get(5, TimeUnit.SECONDS));
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(23, workerCell.getValue());
        assertEquals(17, local.get());
        assertEquals(0, other.get());
    }
}
