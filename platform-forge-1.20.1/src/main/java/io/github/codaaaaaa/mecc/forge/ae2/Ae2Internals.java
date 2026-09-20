package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogic;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AE2 state that is not public: a CPU's running job (who asked for it, which patterns are left) and what a pattern
 * provider could not push yet. Read through reflection, pinned to AE2 {@value Ae2Integration#TESTED_VERSION}; when a field
 * is missing every accessor answers "unknown" ({@code null} or empty) instead of failing.
 */
final class Ae2Internals {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2Internals.class);
    private static final Field JOB = field(CraftingCpuLogic.class, "job");
    private static final Field TASKS = field(ExecutingCraftingJob.class, "tasks");
    private static final Field PLAYER_ID = field(ExecutingCraftingJob.class, "playerId");
    private static final Field TASK_VALUE = field("appeng.crafting.execution.ExecutingCraftingJob$TaskProgress", "value");
    private static final Field SEND_LIST = field(PatternProviderLogic.class, "sendList");
    private static final Field HOST = field(PatternProviderLogic.class, "host");

    private Ae2Internals() {
    }

    private static Field field(String type, String name) {
        try {
            return field(Class.forName(type, false, Ae2Internals.class.getClassLoader()), name);
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.warn("ME Control Center cannot read {}; the crafting tree and machine status are limited", type);
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("ME Control Center cannot read {}.{}; the crafting tree and machine status are limited",
                    type.getName(), name);
            return null;
        }
    }

    private static Object get(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The AE2 player ID of whoever requested the CPU's job, or {@code null}. */
    static Integer requesterId(CraftingCpuLogic logic) {
        return get(PLAYER_ID, get(JOB, logic)) instanceof Integer id ? id : null;
    }

    /** Patterns the CPU's job still has to push, with how many runs are left, or {@code null} when unreadable. */
    static Map<IPatternDetails, Long> tasks(CraftingCpuLogic logic) {
        if (!(get(TASKS, get(JOB, logic)) instanceof Map<?, ?> tasks)) {
            return null;
        }
        Map<IPatternDetails, Long> result = new LinkedHashMap<>();
        tasks.forEach((pattern, progress) -> {
            if (pattern instanceof IPatternDetails details && get(TASK_VALUE, progress) instanceof Long value) {
                result.put(details, value);
            }
        });
        return result;
    }

    /** The block or part a crafting provider belongs to, or {@code null}: pattern buffers are containers themselves. */
    static PatternContainer container(ICraftingProvider provider) {
        if (provider instanceof PatternContainer container) {
            return container;
        }
        return provider instanceof PatternProviderLogic logic && get(HOST, logic) instanceof PatternContainer host
                ? host : null;
    }

    /** Stacks the provider could not push into its target yet; 0 when unreadable. */
    static int pendingSends(PatternProviderLogic logic) {
        return get(SEND_LIST, logic) instanceof List<?> list ? list.size() : 0;
    }
}
