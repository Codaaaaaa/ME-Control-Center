package io.github.codaaaaaa.mecc.forge.ae2;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What GregTech-like machines say about themselves: the status and progress of their recipe logic. GTCEu is optional,
 * so everything goes through reflection by method name ({@code getMetaMachine}, {@code getRecipeLogic},
 * {@code getStatus}, {@code getProgress}, {@code getMaxProgress}, {@code getWaitingReason}); anything else is
 * simply not a GregTech machine.
 */
final class GtMachines {
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    /**
     * @param progress      0-1, or {@code null} when not running a recipe
     * @param waitingReason what the machine says is stopping it, in its own words, or {@code null}
     */
    record Status(String name, Double progress, String waitingReason) {
    }

    private GtMachines() {
    }

    /**
     * @param blockEntityOrMachine a GregTech machine block entity, or the machine itself (a multiblock controller)
     * @return its recipe status, or {@code null} for anything that has none
     */
    static Status status(Object blockEntityOrMachine) {
        Object machine = machine(blockEntityOrMachine);
        Object logic = invoke(machine, "getRecipeLogic");
        if (logic == null) {
            return null;
        }
        Object status = invoke(logic, "getStatus");
        String name = status instanceof Enum<?> value ? value.name() : null;
        Double progress = null;
        if (invoke(logic, "getProgress") instanceof Integer done && invoke(logic, "getMaxProgress") instanceof Integer max
                && max > 0) {
            progress = Math.max(0.0, Math.min(1.0, done / (double) max));
        }
        return new Status(name, progress, "WAITING".equals(name) ? waitingReason(logic) : null);
    }

    /** The machine's own wording for why it cannot run ("not enough energy", "output full", ...), or {@code null}. */
    private static String waitingReason(Object logic) {
        if (!(invoke(logic, "getWaitingReason") instanceof Component reason)) {
            return null;
        }
        String text = reason.getString();
        return text.isBlank() ? null : text;
    }

    /** Where a GregTech machine is, or {@code null}. */
    static BlockPos pos(Object machine) {
        return invoke(machine, "getPos") instanceof BlockPos pos ? pos : null;
    }

    private static Object machine(Object blockEntityOrMachine) {
        Object machine = invoke(blockEntityOrMachine, "getMetaMachine");
        return machine != null ? machine : blockEntityOrMachine;
    }

    private static Object invoke(Object target, String name) {
        if (target == null) {
            return null;
        }
        Optional<Method> method = METHODS.computeIfAbsent(target.getClass().getName() + "#" + name, key -> {
            try {
                Method found = target.getClass().getMethod(name);
                return Modifier.isStatic(found.getModifiers()) ? Optional.empty() : Optional.of(found);
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) {
            return null;
        }
        try {
            return method.get().invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
