// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import thc.Language;
public final class PromptTag {
    private final Language.State owner;
    public PromptTag(Language.State owner) { this.owner = owner; }
    public Language.State getOwner() { return owner; }
}
