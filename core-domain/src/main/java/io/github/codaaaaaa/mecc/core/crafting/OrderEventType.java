package io.github.codaaaaaa.mecc.core.crafting;

/** Entries of an order's history. Stored by name: add values, never rename. */
public enum OrderEventType {
    CREATED,
    STARTED,
    REJECTED,
    CANCELLED,
    COMPLETED,
    UNKNOWN
}
