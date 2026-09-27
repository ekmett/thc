// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import thc.Language;

/** Context-owned layout interning. */
public final class HandoffLayouts {
    private final Language language;
    private final boolean enabled = Boolean.getBoolean(HandoffKt.HANDOFF_PROPERTY);
    private final HashMap<List<String>, HandoffLayout> layouts = new HashMap<>();
    public HandoffLayouts(Language language) { this.language = language; }
    public boolean getEnabled() { return enabled; }
    public synchronized HandoffLayout intern(List<String> reps) {
        ArrayList<String> fields = new ArrayList<>(reps.size());
        for (String rep : reps) fields.add(HandoffLayout.fieldKind(rep));
        HandoffLayout layout = layouts.get(fields);
        if (layout == null) { layout = new HandoffLayout(language, layouts.size(), fields); layouts.put(fields, layout); }
        return layout;
    }
}
