// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.function.BooleanSupplier;
import com.oracle.svm.core.SubstrateOptions;

public final class UseJamGC implements BooleanSupplier {
    @Override
    public boolean getAsBoolean() {
        return SubstrateOptions.useJamGC();
    }
}
