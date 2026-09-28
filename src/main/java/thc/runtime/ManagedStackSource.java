// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Detached SourceSection coordinates; end columns are inclusive. */
public record ManagedStackSource(String name, String path, String uri, boolean available, Integer startLine, Integer startColumn, Integer endLine, Integer endColumn) {
    public String getName() { return name; }
    public String getPath() { return path; }
    public String getUri() { return uri; }
    public boolean getAvailable() { return available; }
    public Integer getStartLine() { return startLine; }
    public Integer getStartColumn() { return startColumn; }
    public Integer getEndLine() { return endLine; }
    public Integer getEndColumn() { return endColumn; }
}

