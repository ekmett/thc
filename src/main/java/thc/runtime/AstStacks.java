// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import thc.Language;

public final class AstStacks {
    private static final LanguageReference<Language> LANGUAGES = LanguageReference.create(Language.class);
    private AstStacks() {}
    public static AstStackScope astStackScope(Node node) {
        return LANGUAGES.get(node).getThreadPollState().get().getAstStack();
    }
    public static boolean stackSpill(SavedGuestContinuation saved) { return saved.stackSpill(); }
}
