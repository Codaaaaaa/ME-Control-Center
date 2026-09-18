package io.github.codaaaaaa.mecc.core.patterns;

import java.util.List;

/**
 * Pattern provider settings a Manager can change from the web (spec section 15). A {@code null} field is left as it is.
 *
 * @param lockMode          one of {@link #LOCK_MODES}
 * @param visibleInTerminal shown in the Pattern Access Terminal
 */
public record ProviderSettings(Integer priority, Boolean blocking, String lockMode, Boolean visibleInTerminal) {
    /** AE2's lock-crafting modes, by name. */
    public static final List<String> LOCK_MODES =
            List.of("NONE", "LOCK_UNTIL_PULSE", "LOCK_WHILE_HIGH", "LOCK_WHILE_LOW", "LOCK_UNTIL_RESULT");

    public boolean isEmpty() {
        return priority == null && blocking == null && lockMode == null && visibleInTerminal == null;
    }
}
