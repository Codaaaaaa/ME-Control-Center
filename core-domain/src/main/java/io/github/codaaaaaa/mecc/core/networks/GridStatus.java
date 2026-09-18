package io.github.codaaaaaa.mecc.core.networks;

/**
 * Immutable live status of one loaded ME grid, copied on the server thread.
 *
 * <p>Boxed fields are optional capabilities: {@code null} means the platform cannot report the value
 * reliably, never "zero" (spec section 6.1).
 *
 * @param powered                 whether the grid has power
 * @param booting                 whether channels are still being assigned
 * @param controllerState         {@code NO_CONTROLLER}, {@code CONTROLLER_ONLINE}, or {@code CONTROLLER_CONFLICT}
 * @param channelMode             adapter-specific channel mode name, may be {@code null}
 * @param storedEnergy            AE currently stored
 * @param energyCapacity          maximum AE storable
 * @param averageEnergyUsage      AE/t average consumption
 * @param averageEnergyInjection  AE/t average injection
 * @param usedChannels            channels in use
 * @param nodeCount               grid nodes
 * @param storedResourceTypes     distinct resource types in network storage
 * @param craftingCpus            crafting CPU count
 * @param busyCraftingCpus        CPUs currently running a job (equals active crafting jobs)
 * @param patternProviders        online pattern providers
 */
public record GridStatus(
        boolean powered,
        boolean booting,
        String controllerState,
        String channelMode,
        Double storedEnergy,
        Double energyCapacity,
        Double averageEnergyUsage,
        Double averageEnergyInjection,
        Integer usedChannels,
        int nodeCount,
        Integer storedResourceTypes,
        Integer craftingCpus,
        Integer busyCraftingCpus,
        Integer patternProviders) {

    public static final String CONTROLLER_CONFLICT = "CONTROLLER_CONFLICT";
}
