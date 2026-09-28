// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.source.SourceSection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import thc.CoreCompactRecords;

/** One debugger location and the provenance of all collapsed source notes. */
public final class CoreSourceLocation {
    private record Details(SourceSection section, List<CoreSourceNote> notes) {}
    private final CoreCompactRecords.Origin compactOrigin;
    private final Supplier<CoreSourceLocation> resolve;
    private final Object resolveLock = new Object();
    private Details eager;
    private CoreSourceLocation resolved;
    private volatile boolean initialized;

    private CoreSourceLocation(CoreCompactRecords.Origin origin, Supplier<CoreSourceLocation> resolve) {
        compactOrigin = origin;
        this.resolve = resolve;
    }
    public CoreSourceLocation(SourceSection section, List<CoreSourceNote> notes) {
        this(null, () -> null);
        eager = new Details(Objects.requireNonNull(section), Objects.requireNonNull(notes));
    }
    private CoreSourceLocation resolved() {
        if (!initialized) synchronized (resolveLock) {
            if (!initialized) {
                resolved = resolve.get();
                // Failed resolution remains retryable, as with the original synchronized lazy.
                initialized = true;
            }
        }
        return resolved;
    }
    public CoreCompactRecords.Origin getCompactOrigin() { return compactOrigin; }
    public SourceSection getSection() {
        if (eager != null) return eager.section();
        var location = resolved();
        return location == null ? null : location.getSection();
    }
    public List<CoreSourceNote> getNotes() {
        if (eager != null) return eager.notes();
        var location = resolved();
        return location == null ? List.of() : location.getNotes();
    }
    public static CoreSourceLocation compact(CoreCompactRecords.Origin origin, boolean enabled) {
        Objects.requireNonNull(origin);
        return new CoreSourceLocation(origin, () -> {
            if (!enabled) return null;
            var debug = origin.getDebug();
            return debug == null ? null : debug.location(origin.getDataOffset());
        });
    }
}
