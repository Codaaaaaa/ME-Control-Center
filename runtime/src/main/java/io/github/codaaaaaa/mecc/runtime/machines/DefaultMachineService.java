package io.github.codaaaaaa.mecc.runtime.machines;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.machines.MachineService;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.platform.PatternPlatform;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.MachineCapture;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.MachineState;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Whether the machines behind a network's pattern providers are working.
 *
 * <p>A machine that reports its own state (GregTech) is judged by that alone: it says whether it is running, has
 * nothing to make, is switched off, or cannot run and why. What a crafting CPU happens to wait for cannot overrule it,
 * and must not: AE2 waits for a <em>kind</em> of item, so every machine in a row of identical ones looks awaited while
 * only one of them was given the job.
 *
 * <p>A machine that reports nothing is only guessed at: it is <em>expected</em> to work when a crafting CPU waits for
 * something its patterns make, or when its provider could not push everything into it, and an expected machine that
 * holds something and has not changed for {@link #STUCK_AFTER} since that expectation began is stuck. Change means its
 * contents or its reported progress.
 *
 * <p>Captures are taken on demand, at most one per network every {@link #MAX_AGE}, and shared by the web page and the
 * machine-stuck alert.
 */
public final class DefaultMachineService implements MachineService {
    static final Duration MAX_AGE = Duration.ofSeconds(5);
    public static final Duration STUCK_AFTER = Duration.ofMinutes(2);
    /** Unchanged for less than this still counts as working: machines take a moment between operations. */
    static final Duration BUSY_WITHIN = Duration.ofSeconds(20);
    /** GregTech's recipe-logic statuses; anything else it might add is read as a failure to run. */
    private static final String WORKING_REPORT = "WORKING";
    private static final String IDLE_REPORT = "IDLE";
    private static final String SUSPEND_REPORT = "SUSPEND";
    private static final Duration SERVER_CALL_TIMEOUT = Duration.ofSeconds(5);

    private final NetworkGuard guard;
    private final PatternPlatform patterns;
    private final ServerThreadGateway gateway;
    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;
    private final Clock clock;
    private final Map<UUID, Entry> networks = new ConcurrentHashMap<>();

    /**
     * What was seen of one machine last time, and what it was called then.
     *
     * @param expectedSince since when something has been expected of it without it moving; {@code null} when nothing
     *                      has been expected of it since it last moved
     */
    record Track(long hash, Double progress, String reported, Instant lastChange, MachineStatus status,
                 StuckReason reason, Instant expectedSince) {
    }

    /**
     * @param stuckSince    since when an expected machine has not changed; {@code null} when nothing is expected of it
     * @param expectedSince carried to the next {@link Track}
     */
    public record Observation(MachineState state, MachineStatus status, StuckReason reason, Instant lastChange,
                              Instant stuckSince, Instant expectedSince) {
        Observation withExpectedSince(Instant since) {
            return new Observation(state, status, reason, lastChange, stuckSince, since);
        }
    }

    public record Snapshot(Instant capturedAt, List<Observation> machines) {
    }

    private static final class Entry {
        private Snapshot last;
        private CompletableFuture<Snapshot> pending;
        private final Map<String, Track> tracks = new HashMap<>();
    }

    public DefaultMachineService(NetworkGuard guard, PatternPlatform patterns, ServerThreadGateway gateway,
                                 ResourceLabels labels, Supplier<String> assetVersion, Clock clock) {
        this.guard = guard;
        this.patterns = patterns;
        this.gateway = gateway;
        this.labels = labels;
        this.assetVersion = assetVersion;
        this.clock = clock;
    }

    @Override
    public CompletionStage<MachineList> machines(Session session, UUID networkId, String locale) {
        String language = locale == null || locale.isBlank() ? "en_us" : locale;
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            return latest(networkId, guard.gridKey(networkId));
        }).thenApply(snapshot -> new MachineList(snapshot.capturedAt(), snapshot.machines().stream()
                .sorted(Comparator.comparing((Observation observation) -> observation.status().ordinal()).reversed()
                        .thenComparing(observation -> observation.state().id()))
                .map(observation -> view(observation, language)).toList(),
                STUCK_AFTER.toSeconds(), assetVersion.get()));
    }

    /** The network's machines, captured at most {@link #MAX_AGE} ago. */
    public CompletableFuture<Snapshot> latest(UUID networkId, String gridKey) {
        Entry entry = networks.computeIfAbsent(networkId, id -> new Entry());
        synchronized (entry) {
            Instant now = clock.instant();
            if (entry.last != null && Duration.between(entry.last.capturedAt(), now).compareTo(MAX_AGE) < 0) {
                return CompletableFuture.completedFuture(entry.last);
            }
            // A capture that completed at once has already cleared itself; never hand out a finished stale one.
            if (entry.pending == null || entry.pending.isDone()) {
                entry.pending = gateway.call("patterns.captureMachines", () -> patterns.captureMachines(gridKey),
                                SERVER_CALL_TIMEOUT)
                        .thenApply(capture -> record(entry, capture))
                        .whenComplete((snapshot, error) -> {
                            synchronized (entry) {
                                entry.pending = null;
                            }
                        });
            }
            return entry.pending;
        }
    }

    private Snapshot record(Entry entry, MachineCapture capture) {
        Instant now = clock.instant();
        synchronized (entry) {
            List<Observation> observations = new ArrayList<>();
            Map<String, Track> seen = new HashMap<>();
            for (MachineState state : capture.machines()) {
                Track previous = entry.tracks.get(state.id());
                Observation observation = observe(state, previous, now);
                observations.add(observation);
                seen.put(state.id(), new Track(state.contentsHash(), state.progress(), state.reported(),
                        observation.lastChange(), observation.status(), observation.reason(),
                        observation.expectedSince()));
            }
            entry.tracks.clear();
            entry.tracks.putAll(seen);
            entry.last = new Snapshot(now, observations);
            return entry.last;
        }
    }

    /** Where a machine stands, from this capture and what was seen before. Pure. */
    static Observation observe(MachineState state, Track previous, Instant now) {
        boolean changed = previous == null || previous.hash() != state.contentsHash()
                || !Objects.equals(previous.progress(), state.progress())
                || !Objects.equals(previous.reported(), state.reported());
        Instant lastChange = changed ? now : previous.lastChange();
        return state.reported() != null ? reported(state, lastChange, now)
                : guessed(state, previous, changed, lastChange, now);
    }

    /** A machine that knows its own state, taken at its word. */
    private static Observation reported(MachineState state, Instant lastChange, Instant now) {
        if (state.pendingSends() > 0) {
            // Its provider cannot even hand it the inputs. That is about the provider, whatever the machine is doing.
            return timed(state, StuckReason.OUTPUT_BLOCKED, lastChange, lastChange, now);
        }
        return switch (state.reported()) {
            case WORKING_REPORT -> settled(state, MachineStatus.WORKING, lastChange);
            case IDLE_REPORT -> settled(state, MachineStatus.IDLE, lastChange);
            // Switched off by a player or a cover: stopped on purpose, not broken.
            case SUSPEND_REPORT -> settled(state, MachineStatus.DISABLED, lastChange);
            default -> timed(state, StuckReason.MACHINE_WAITING, lastChange, lastChange, now);
        };
    }

    /** A machine with nothing to report, judged by what a crafting CPU waits for and whether it moves. */
    private static Observation guessed(MachineState state, Track previous, boolean changed, Instant lastChange,
                                       Instant now) {
        boolean expected = state.awaited() || state.pendingSends() > 0;
        boolean loaded = state.pendingSends() > 0 || state.hasContents();
        // The stuck clock runs from when something was first expected of it without it moving. A machine that sat
        // idle for an hour and only now appears in a crafting plan has not been failing for that hour; and a CPU
        // that stops waiting between batches must not restart the clock of one that really is not moving.
        // ponytail: AE2 pushes a pattern and forgets which provider took it (CraftingCpuLogic.executeCrafting), so
        // for a machine that reports nothing this is all the evidence there is. A mod-side hook on the push would be
        // the only way to do better.
        Instant expectedSince = !changed && previous != null && previous.expectedSince() != null
                ? previous.expectedSince() : expected ? now : null;
        if (!expected || !loaded) {
            // A CPU stops waiting for a machine between batches, and a provider's send queue drains and fills again.
            // While the machine itself still holds something and has not moved, that flapping must not clear a flag
            // it earned, or every refresh shows it stuck, then fine, then stuck again.
            if (!changed && loaded && previous.status() != null
                    && previous.status().compareTo(MachineStatus.WAITING) >= 0) {
                return new Observation(state, previous.status(), previous.reason(), lastChange, lastChange,
                        expectedSince);
            }
            boolean busy = state.hasContents() && previous != null
                    && Duration.between(lastChange, now).compareTo(BUSY_WITHIN) < 0;
            return new Observation(state, busy ? MachineStatus.WORKING : MachineStatus.IDLE, null, lastChange, null,
                    expectedSince);
        }
        Instant since = lastChange.isAfter(expectedSince) ? lastChange : expectedSince;
        StuckReason reason = state.pendingSends() > 0 ? StuckReason.OUTPUT_BLOCKED : StuckReason.NO_CHANGE;
        return timed(state, reason, lastChange, since, now).withExpectedSince(expectedSince);
    }

    /** Nothing is wrong with it, so there is nothing to have been waiting since. */
    private static Observation settled(MachineState state, MachineStatus status, Instant lastChange) {
        return new Observation(state, status, null, lastChange, null, null);
    }

    /**
     * Waiting, then stuck, the longer {@code since} goes on without the machine moving.
     *
     * @param since when it started going wrong, which is when it last moved unless it was fine until later
     */
    private static Observation timed(MachineState state, StuckReason reason, Instant lastChange, Instant since,
                                     Instant now) {
        Duration unchanged = Duration.between(since, now);
        MachineStatus status = unchanged.compareTo(STUCK_AFTER) >= 0 ? MachineStatus.STUCK
                : unchanged.compareTo(BUSY_WITHIN) >= 0 ? MachineStatus.WAITING : MachineStatus.WORKING;
        return status == MachineStatus.WORKING ? settled(state, status, lastChange)
                : new Observation(state, status, reason, lastChange, since, null);
    }

    private MachineView view(Observation observation, String locale) {
        MachineState state = observation.state();
        return new MachineView(state.id(), state.block() == null ? null : labels.label(state.block(), locale),
                state.location(), state.providers().size(), observation.status(), observation.reason(), state.reported(),
                state.waitingReason(), state.progress(), state.awaited(), state.pendingSends(),
                observation.lastChange());
    }
}
