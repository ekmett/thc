// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

public record PackageScalarAdmission(PackageScalarLink link, Set<String> proved) {

    public PackageScalarLink getLink() { return link; }
    public Set<String> getProved() { return proved; }
}
