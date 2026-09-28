// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CoreJoinDefinition(Map<String, Object> binding, String id,
                                 List<Map<String, Object>> parameters, List<Object> body, CoreRepresentation result) {
    public CoreJoinDefinition {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(id);
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(body);
        Objects.requireNonNull(result);
    }
    public Map<String, Object> getBinding() { return binding; }
    public String getId() { return id; }
    public List<Map<String, Object>> getParameters() { return parameters; }
    public List<Object> getBody() { return body; }
    public CoreRepresentation getResult() { return result; }
    @Override public String toString() {
        return "CoreJoinDefinition(binding=" + binding + ", id=" + id + ", parameters=" + parameters +
            ", body=" + body + ", result=" + result + ")";
    }
}
