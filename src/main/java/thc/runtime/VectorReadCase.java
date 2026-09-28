// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

public record VectorReadCase(VectorMemoryOp operation, List<List<Object>> arguments,
        String stateBinder, String vectorBinder, List<Object> body) {
    public VectorMemoryOp getOperation() { return operation; }
    public List<List<Object>> getArguments() { return arguments; }
    public String getStateBinder() { return stateBinder; }
    public String getVectorBinder() { return vectorBinder; }
    public List<Object> getBody() { return body; }
}
