// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

/** Row key shared by the scalar-entry vector and memory fixtures. */
final class IntegerSimdModel {
    private IntegerSimdModel() {}
    record Input(String name, List<Long> arguments) {}
}
