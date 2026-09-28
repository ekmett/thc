// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.source.SourceSection;
import java.util.Objects;

/** GHC coordinates retain their exclusive end even without source text. */
public record CoreSourceNote(String id, SourceSection section, String label,
                             int startLine, int startColumn, int endLine, int endColumn) {
    public CoreSourceNote { Objects.requireNonNull(id); Objects.requireNonNull(section); }
    public String getId() { return id; }
    public SourceSection getSection() { return section; }
    public String getLabel() { return label; }
    public int getStartLine() { return startLine; }
    public int getStartColumn() { return startColumn; }
    public int getEndLine() { return endLine; }
    public int getEndColumn() { return endColumn; }
    @Override public String toString() {
        return "CoreSourceNote(id=" + id + ", section=" + section + ", label=" + label + ", startLine=" + startLine +
            ", startColumn=" + startColumn + ", endLine=" + endLine + ", endColumn=" + endColumn + ")";
    }
}
