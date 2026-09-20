package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.AEBasePart;
import appeng.util.CustomNameUtil;
import appeng.util.SettingsFrom;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What ME Control Center needs to know about a pattern container beyond AE2's {@link PatternContainer} interface:
 * where it is, what it is, which machine it serves, and its player-given name.
 *
 * <p>AE2's own pattern providers are handled through AE2 classes. Containers from other mods - GregTech and GTL
 * multiblock pattern buffers - are recognized by shape, via reflection, so no mod is required: a {@code getPos()}
 * and {@code getLevel()} for the position, {@code getControllers()} for the multiblock it belongs to, and a
 * {@code customName} field with {@code setCustomName(String)} for the name.
 */
final class PatternContainers {
    /** Reflective accessors per container class; {@code null} members where the class lacks them. */
    private record Shape(Method pos, Method level, Method controllers, Field customName, Method setCustomName) {
    }

    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

    private PatternContainers() {
    }

    record Placement(Level level, BlockPos pos) {
    }

    /** Where the container is, or {@code null}. */
    static Placement placement(PatternContainer container) {
        if (container instanceof PatternProviderLogicHost host) {
            BlockEntity blockEntity = host.getBlockEntity();
            return new Placement(blockEntity.getLevel(), blockEntity.getBlockPos());
        }
        Shape shape = shape(container.getClass());
        if (shape.pos() == null || shape.level() == null) {
            return null;
        }
        try {
            BlockPos pos = (BlockPos) shape.pos().invoke(container);
            Level level = (Level) shape.level().invoke(container);
            return pos == null ? null : new Placement(level, pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The container as an item: the provider block or part, the pattern buffer machine. */
    static AEItemKey kind(PatternContainer container, Placement placement) {
        if (container instanceof PatternProviderLogicHost host) {
            return host.getTerminalIcon();
        }
        if (placement == null || placement.level() == null || !placement.level().isLoaded(placement.pos())) {
            return null;
        }
        // Machines of GregTech-like mods are one block (and item) per machine type.
        return AEItemKey.of(new ItemStack(placement.level().getBlockState(placement.pos()).getBlock()));
    }

    /**
     * The multiblock the container is part of, as its controller item, or {@code null}. Parts of several
     * multiblocks report the first.
     */
    static AEItemKey multiblock(PatternContainer container) {
        Object machine = controller(container);
        if (machine == null) {
            return null;
        }
        try {
            Method definition = find(machine.getClass(), "getDefinition");
            Object value = definition == null ? null : definition.invoke(machine);
            Method asStack = value == null ? null : find(value.getClass(), "asStack");
            return asStack == null ? null : AEItemKey.of((ItemStack) asStack.invoke(value));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The controller machine of the multiblock the container is part of (GregTech-like mods), or {@code null}. Parts of
     * several multiblocks report the first.
     */
    static Object controller(PatternContainer container) {
        Shape shape = shape(container.getClass());
        if (shape.controllers() == null) {
            return null;
        }
        try {
            if (!(shape.controllers().invoke(container) instanceof List<?> controllers) || controllers.isEmpty()) {
                return null;
            }
            Object controller = controllers.get(0);
            Method self = find(controller.getClass(), "self");
            return self == null ? controller : self.invoke(controller);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The player-given name, or {@code null}. */
    static String customName(PatternContainer container) {
        if (container instanceof Nameable nameable) {
            return nameable.hasCustomName() && nameable.getCustomName() != null ? nameable.getCustomName().getString() : null;
        }
        Field field = shape(container.getClass()).customName();
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(container);
            return value instanceof String text && !text.isEmpty() ? text : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static boolean renamable(PatternContainer container) {
        return container instanceof AEBaseBlockEntity || container instanceof AEBasePart
                || shape(container.getClass()).setCustomName() != null;
    }

    /** Renames the container; an empty name removes the custom name. Returns {@code false} if it cannot be renamed. */
    static boolean rename(PatternContainer container, String name) {
        if (container instanceof AEBaseBlockEntity || container instanceof AEBasePart) {
            // AE2 only exposes the name through its settings transfer (memory cards, dismantling). A tag holding
            // nothing but the name changes nothing else: no upgrades without a player, no other settings.
            CompoundTag settings = new CompoundTag();
            CustomNameUtil.setCustomName(settings, name.isEmpty() ? null : Component.literal(name));
            if (container instanceof AEBaseBlockEntity blockEntity) {
                blockEntity.importSettings(SettingsFrom.DISMANTLE_ITEM, settings, null);
                blockEntity.saveChanges();
                blockEntity.markForUpdate();
            } else {
                AEBasePart part = (AEBasePart) container;
                part.importSettings(SettingsFrom.DISMANTLE_ITEM, settings, null);
                part.getHost().markForSave();
                part.getHost().markForUpdate();
            }
            return true;
        }
        Method setter = shape(container.getClass()).setCustomName();
        if (setter == null) {
            return false;
        }
        try {
            setter.invoke(container, name);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Shape shape(Class<?> type) {
        return SHAPES.computeIfAbsent(type, PatternContainers::inspect);
    }

    private static Shape inspect(Class<?> type) {
        Method pos = find(type, "getPos");
        Method level = find(type, "getLevel");
        Method controllers = find(type, "getControllers");
        Method setCustomName = null;
        try {
            setCustomName = type.getMethod("setCustomName", String.class);
        } catch (NoSuchMethodException e) {
            // Not renamable this way.
        }
        return new Shape(
                pos != null && BlockPos.class.isAssignableFrom(pos.getReturnType()) ? pos : null,
                level != null && Level.class.isAssignableFrom(level.getReturnType()) ? level : null,
                controllers != null && List.class.isAssignableFrom(controllers.getReturnType()) ? controllers : null,
                stringField(type, "customName"),
                setCustomName);
    }

    /** A public no-argument method, or {@code null}. */
    private static Method find(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return Modifier.isStatic(method.getModifiers()) ? null : method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Field stringField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (field.getType() == String.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException e) {
                // Look further up.
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
