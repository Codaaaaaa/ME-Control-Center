package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.forge.ComponentText;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequestState;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequesterState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The ME Requesters of a grid: blocks of the optional ME Requester mod ({@code merequester}) that keep resources in
 * stock just as ME Control Center's Keep Stock rules do, configured in game instead of on the web.
 *
 * <p>The mod is optional, so everything goes through reflection by name ({@code getRequests}, {@code getKey},
 * {@code getAmount}, ...); without it a grid simply has no requesters and {@link #present()} is {@code false}.
 */
final class MeRequesters {
    private static final String REQUESTER = "com.almostreliable.merequester.requester.RequesterBlockEntity";
    private static final Class<?> TYPE = type();
    /** Per-slot {@code StatusState}; it is private, so a mod version without it costs only the status. */
    private static final Field STATUS = TYPE == null ? null : field(TYPE, "requestStatus");
    /** Reflective accessors per class and method; empty where the class lacks them. */
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    private MeRequesters() {
    }

    /** Whether this server has the ME Requester mod at all. */
    static boolean present() {
        return TYPE != null;
    }

    /** Every ME Requester of the grid with the request slots that ask for something. Server thread only. */
    static List<RequesterState> capture(IGrid grid) {
        List<RequesterState> requesters = new ArrayList<>();
        requesters(grid).forEach((requester, online) -> {
            RequesterState state = describe(requester, online);
            if (state != null) {
                requesters.add(state);
            }
        });
        return requesters;
    }

    /** Empties one request slot, as taking its request out in game would. Server thread only. */
    static boolean clear(IGrid grid, String requesterId, int slot) {
        for (Object requester : requesters(grid).keySet()) {
            if (!(requester instanceof BlockEntity blockEntity) || !id(blockEntity).equals(requesterId)) {
                continue;
            }
            Object requests = call(requester, "getRequests");
            if (requests == null || slot < 0 || slot >= size(requests)) {
                return false;
            }
            Method setStack = method(requests.getClass(), "setStack", int.class, GenericStack.class);
            if (setStack == null) {
                return false;
            }
            try {
                // A null stack is how the mod itself empties a slot: it forgets the resource, amount, and batch.
                setStack.invoke(requests, slot, null);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    /** The grid's requesters and whether each one is online (powered, with a channel). */
    private static Map<Object, Boolean> requesters(IGrid grid) {
        Map<Object, Boolean> found = new LinkedHashMap<>();
        if (TYPE == null) {
            return found;
        }
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!TYPE.isAssignableFrom(machineClass)) {
                continue;
            }
            var active = grid.getActiveMachines(machineClass);
            for (Object machine : grid.getMachines(machineClass)) {
                found.put(machine, active.contains(machine));
            }
        }
        return found;
    }

    private static RequesterState describe(Object requester, boolean online) {
        Object requests = call(requester, "getRequests");
        if (!(requester instanceof BlockEntity blockEntity) || requests == null) {
            return null;
        }
        Object storage = call(requester, "getStorageManager");
        Object[] statuses = statuses(requester);
        List<RequestState> slots = new ArrayList<>();
        for (int slot = 0; slot < size(requests); slot++) {
            Object request = call(requests, "get", slot);
            if (!(call(request, "getKey") instanceof AEKey key)) {
                // An empty slot: the requester asks for nothing there.
                continue;
            }
            slots.add(new RequestState(slot, Ae2StoragePlatform.convert(key), amount(request, "getAmount"),
                    amount(request, "getBatch"), Boolean.TRUE.equals(call(request, "getState")),
                    status(statuses, slot), stored(storage, slot)));
        }
        return new RequesterState(id(blockEntity), name(requester), location(blockEntity), online, slots);
    }

    /** Position-based identifier, stable while the requester stays in place. */
    private static String id(BlockEntity blockEntity) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        return "r" + Ae2Support.shortHash((level == null ? "?" : level.dimension().location().toString()) + "@"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    private static BlockLocation location(BlockEntity blockEntity) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return null;
        }
        BlockPos pos = blockEntity.getBlockPos();
        return new BlockLocation(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** The name the Requester Terminal shows, or {@code null}. */
    private static ResourceText name(Object requester) {
        return call(requester, "getTerminalName") instanceof Component component ? ComponentText.of(component) : null;
    }

    private static int size(Object requests) {
        return call(requests, "size") instanceof Integer size ? size : 0;
    }

    private static long amount(Object request, String getter) {
        return call(request, getter) instanceof Long amount ? amount : 0L;
    }

    /** What the requester last saw of this slot in network storage, or {@code null}. */
    private static Long stored(Object storageManager, int slot) {
        return call(call(storageManager, "get", slot), "getKnownAmount") instanceof Long amount ? amount : null;
    }

    private static Object[] statuses(Object requester) {
        if (STATUS == null) {
            return null;
        }
        try {
            return STATUS.get(requester) instanceof Object[] states ? states : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String status(Object[] statuses, int slot) {
        if (statuses == null || slot >= statuses.length) {
            return null;
        }
        return call(statuses[slot], "type") instanceof Enum<?> type ? type.name() : null;
    }

    // --- reflection ---------------------------------------------------------------------------------

    private static Class<?> type() {
        try {
            return Class.forName(REQUESTER, false, MeRequesters.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Calls a public method taking no arguments, or one {@code int}; {@code null} for anything unexpected. */
    private static Object call(Object target, String name) {
        return invoke(target, method(target == null ? null : target.getClass(), name));
    }

    private static Object call(Object target, String name, int argument) {
        return invoke(target, method(target == null ? null : target.getClass(), name, int.class), argument);
    }

    private static Object invoke(Object target, Method method, Object... arguments) {
        if (target == null || method == null) {
            return null;
        }
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        String key = owner.getName() + "#" + name + "/" + parameters.length;
        return METHODS.computeIfAbsent(key, ignored -> {
            try {
                Method found = owner.getMethod(name, parameters);
                return Modifier.isStatic(found.getModifiers()) ? Optional.empty() : Optional.of(found);
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
