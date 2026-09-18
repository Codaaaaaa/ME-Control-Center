package io.github.codaaaaaa.mecc.core.crafting;

/** Where a crafting order came from (spec section 10). Stored by name. */
public enum OrderSource {
    MANUAL,
    SAVED_ORDER,
    AUTOMATION,
    API
}
