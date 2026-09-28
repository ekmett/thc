// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Original Core coordinates with their exclusive end and optional label. */
public record ManagedStackNote(String id, String label, ManagedStackSource source, int startLine, int startColumn, int endLine, int endColumn) {
    public String getId() { return id; }
    public String getLabel() { return label; }
    public ManagedStackSource getSource() { return source; }
    public int getStartLine() { return startLine; }
    public int getStartColumn() { return startColumn; }
    public int getEndLine() { return endLine; }
    public int getEndColumn() { return endColumn; }
}

