// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Info identities belong to one context, never a global guest-value cache. */
public final class ClosureInfoTables {
    private final ConcurrentHashMap<String, ManagedAddress> tables = new ConcurrentHashMap<>();
    @TruffleBoundary public ManagedAddress address(String descriptor) {
        return tables.computeIfAbsent(descriptor, key -> ManagedAddress.fromStaticBytes(
            ("THC closure v1: " + key + "\u0000").getBytes(StandardCharsets.UTF_8), 8));
    }
}
