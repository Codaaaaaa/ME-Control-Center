package io.github.codaaaaaa.mecc.core.explorer;

/**
 * How a network device is grouped in the Network Explorer (spec section 26). Adapters classify only what they can
 * tell reliably; everything else is {@link #OTHER}, never guessed.
 */
public enum DeviceKind {
    /** Drives, chests, and other blocks that provide storage. */
    STORAGE,
    /** Crafting CPUs (crafting storage). */
    CRAFTING,
    /** Pattern providers and pattern buffers. */
    PATTERN_PROVIDER,
    /** Interfaces and other blocks that supply patterns or stock. */
    INTERFACE,
    /** Wireless access points. */
    ACCESS_POINT,
    /** Controllers. */
    CONTROLLER,
    OTHER
}
