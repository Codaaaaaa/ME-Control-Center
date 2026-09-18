package io.github.codaaaaaa.mecc.runtime;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.AnchorProbe;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.ObservedAnchor;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.status.PlatformInfo;
import io.github.codaaaaaa.mecc.core.status.ServerSnapshot;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.Ae2Platform;
import io.github.codaaaaaa.mecc.platform.AssetPlatform;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.PlayerPlatform;
import io.github.codaaaaaa.mecc.platform.ServerInfoPlatform;
import io.github.codaaaaaa.mecc.platform.StoragePlatform;
import io.github.codaaaaaa.mecc.platform.MeccPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadExecutor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory platform with a single-thread executor standing in for the Minecraft server thread. */
public final class FakePlatform implements MeccPlatform, AutoCloseable {
    public static final GridStatus POWERED = new GridStatus(true, false, "CONTROLLER_ONLINE", "DEFAULT",
            1_000.0, 16_000.0, 12.5, 40.0, 17, 64, 1_234, 4, 1, null);

    private final Path configDirectory;
    private final Map<String, String> resources;
    private final AtomicReference<Thread> serverThread = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fake-server-thread");
        thread.setDaemon(true);
        serverThread.set(thread);
        return thread;
    });
    public volatile boolean running = true;
    public volatile String ae2Version = "15.4.10";
    /** Loaded grids reported by discovery. */
    public final List<DiscoveredGrid> grids = new CopyOnWriteArrayList<>();
    /** Operator level per player. */
    public final Map<UUID, Integer> opLevels = new ConcurrentHashMap<>();
    public final List<PlayerProfile> onlinePlayers = new CopyOnWriteArrayList<>();
    /** Storage contents reported for every grid. */
    public final List<StoredResource> storage = new CopyOnWriteArrayList<>();
    public final Map<String, List<String>> resourceTags = new ConcurrentHashMap<>();
    public final List<AssetPack> assetPacks = new CopyOnWriteArrayList<>();
    public final Map<String, String> modNames = new ConcurrentHashMap<>();
    public volatile int tagsVersion = 1;
    /** How long the last server-thread storage capture took. */
    public volatile Duration lastCaptureDuration = Duration.ZERO;

    /**
     * @param crafting amount being crafted, or {@link ResourceIndex#NOT_CRAFTING}
     */
    public record StoredResource(ResourceDescriptor descriptor, long amount, boolean craftable, long crafting) {
    }

    public void addResource(String type, String namespace, String path, long amount, boolean craftable) {
        String key = type + "." + namespace + "." + path.replace('/', '.');
        addResource(type, namespace, path, amount, craftable, ResourceText.translatable(key, List.of()));
    }

    /** @param name the display name as the game would compose it */
    public void addResource(String type, String namespace, String path, long amount, boolean craftable, ResourceText name) {
        ResourceId id = ResourceId.of(type, namespace, path);
        ResourceDescriptor.ResourceUnit unit = type.equals("fluid")
                ? new ResourceDescriptor.ResourceUnit("B", 1000)
                : null;
        storage.add(new StoredResource(
                new ResourceDescriptor(id, type + "." + namespace + "." + path.replace('/', '.'), name, namespace, unit,
                        type + "/" + namespace + "/" + path),
                amount, craftable, ResourceIndex.NOT_CRAFTING));
    }

    public FakePlatform(Path configDirectory, Map<String, String> resources) {
        this.configDirectory = configDirectory;
        this.resources = resources;
    }

    /** Adds a loaded grid with one Wireless Access Point owned by {@code owner}. */
    public BlockLocation addGrid(String runtimeKey, PlayerProfile owner, int x) {
        BlockLocation location = new BlockLocation("minecraft:overworld", x, 64, 0);
        grids.add(new DiscoveredGrid(runtimeKey, List.of(new ObservedAnchor(location, owner.uuid(), true)), POWERED));
        return location;
    }

    @Override
    public PlatformInfo info() {
        return new PlatformInfo("fake-1.0", "1.20.1", "fake", "1.0");
    }

    @Override
    public String meccVersion() {
        return "0.0.0-test";
    }

    @Override
    public Path configDirectory() {
        return configDirectory;
    }

    @Override
    public Path dataDirectory() {
        return configDirectory.resolve("world").resolve("mecc");
    }

    @Override
    public Optional<InputStream> openBundledResource(String path) {
        return Optional.ofNullable(resources.get(path))
                .map(content -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public ServerThreadExecutor serverThreadExecutor() {
        return new ServerThreadExecutor() {
            @Override
            public boolean isServerThread() {
                return Thread.currentThread() == serverThread.get();
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public void enqueue(Runnable task) {
                executor.execute(task);
            }
        };
    }

    @Override
    public ServerInfoPlatform serverInfo() {
        return () -> {
            requireServerThread();
            return new ServerSnapshot(true, 3, 20, 12.5, "A Minecraft Server");
        };
    }

    @Override
    public PlayerPlatform players() {
        return new PlayerPlatform() {
            @Override
            public int permissionLevel(PlayerProfile player) {
                requireServerThread();
                return opLevels.getOrDefault(player.uuid(), 0);
            }

            @Override
            public Optional<PlayerProfile> findOnlinePlayer(String name) {
                requireServerThread();
                return onlinePlayers.stream().filter(player -> player.name().equalsIgnoreCase(name)).findFirst();
            }
        };
    }

    @Override
    public NetworkPlatform networks() {
        return new NetworkPlatform() {
            @Override
            public DiscoverySnapshot discover(Collection<BlockLocation> knownAnchors) {
                requireServerThread();
                Map<BlockLocation, AnchorProbe> probes = new HashMap<>();
                knownAnchors.forEach(location -> probes.put(location, AnchorProbe.UNLOADED));
                grids.forEach(grid -> grid.anchors().forEach(anchor -> probes.remove(anchor.location())));
                return new DiscoverySnapshot(Instant.now(), List.copyOf(grids), probes);
            }
        };
    }

    @Override
    public StoragePlatform storage() {
        return new StoragePlatform() {
            @Override
            public StorageCapture capture(String gridKey) {
                requireServerThread();
                long start = System.nanoTime();
                if (grids.stream().noneMatch(grid -> grid.runtimeKey().equals(gridKey))) {
                    throw new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now");
                }
                List<StoredResource> copy = List.copyOf(storage);
                lastCaptureDuration = Duration.ofNanos(System.nanoTime() - start);
                return new FakeCapture(copy);
            }

            @Override
            public ResourceIndex describe(StorageCapture capture) {
                if (Thread.currentThread() == serverThread.get()) {
                    throw new IllegalStateException("describe() must not run on the server thread");
                }
                List<StoredResource> entries = ((FakeCapture) capture).entries;
                int size = entries.size();
                ResourceDescriptor[] descriptors = new ResourceDescriptor[size];
                long[] amounts = new long[size];
                boolean[] craftable = new boolean[size];
                long[] crafting = new long[size];
                for (int i = 0; i < size; i++) {
                    StoredResource entry = entries.get(i);
                    descriptors[i] = entry.descriptor();
                    amounts[i] = entry.amount();
                    craftable[i] = entry.craftable();
                    crafting[i] = entry.crafting();
                }
                return new ResourceIndex(capture.capturedAt(), descriptors, amounts, craftable, crafting);
            }

            @Override
            public Map<String, List<String>> captureTags() {
                requireServerThread();
                return Map.copyOf(resourceTags);
            }

            @Override
            public int tagsVersion() {
                return tagsVersion;
            }
        };
    }

    private record FakeCapture(List<StoredResource> entries) implements StoragePlatform.StorageCapture {
        @Override
        public Instant capturedAt() {
            return Instant.now();
        }

        @Override
        public int size() {
            return entries.size();
        }
    }

    /** Crafting behaviour: CPUs, craftable recipes, and running jobs. Mutate from tests; read on the fake server thread. */
    public final FakeCrafting crafting = new FakeCrafting();

    @Override
    public CraftingPlatform crafting() {
        return crafting;
    }

    /** A crafting CPU of every grid. */
    public static final class FakeCpu {
        public final String id;
        public volatile String name;
        public volatile long storage;
        public volatile boolean online = true;
        public volatile String selectionMode = "ANY";
        public volatile FakeJob job;

        FakeCpu(String id, String name, long storage) {
            this.id = id;
            this.name = name;
            this.storage = storage;
        }
    }

    public static final class FakeJob {
        public final String jobId;
        public final ResourceDescriptor output;
        public final long amount;
        public volatile double progress;

        FakeJob(String jobId, ResourceDescriptor output, long amount) {
            this.jobId = jobId;
            this.output = output;
            this.amount = amount;
        }
    }

    /** What calculating a resource yields. */
    public record FakeRecipe(ResourceDescriptor output, boolean complete, long bytes, List<CraftingPlatform.PlanEntry> entries) {
    }

    public final class FakeCrafting implements CraftingPlatform {
        public final List<FakeCpu> cpus = new CopyOnWriteArrayList<>();
        public final Map<ResourceId, FakeRecipe> recipes = new ConcurrentHashMap<>();
        public final Map<String, JobFate> ended = new ConcurrentHashMap<>();
        public final java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        /** Calculations stay unfinished while this is set. */
        public volatile boolean holdCalculations;
        /** When set, the next submission is rejected with this code. */
        public volatile String rejectNext;

        public FakeCpu addCpu(String id, String name, long storage) {
            FakeCpu cpu = new FakeCpu(id, name, storage);
            cpus.add(cpu);
            return cpu;
        }

        /** Makes a resource craftable; the plan uses {@code bytes} of storage and involves the given entries. */
        public ResourceDescriptor addRecipe(String type, String namespace, String path, boolean complete, long bytes,
                                           List<CraftingPlatform.PlanEntry> entries) {
            ResourceDescriptor output = descriptor(type, namespace, path);
            recipes.put(output.id(), new FakeRecipe(output, complete, bytes, entries));
            return output;
        }

        public FakeCpu cpu(String id) {
            return cpus.stream().filter(cpu -> cpu.id.equals(id)).findFirst().orElseThrow();
        }

        /** Finishes the job on a CPU as the crafting system would. */
        public void complete(String cpuId) {
            FakeCpu cpu = cpu(cpuId);
            FakeJob job = cpu.job;
            cpu.job = null;
            ended.put(job.jobId, JobFate.COMPLETED);
        }

        /** Cancels the job on a CPU as a player in game would. */
        public void cancelInGame(String cpuId) {
            FakeCpu cpu = cpu(cpuId);
            FakeJob job = cpu.job;
            cpu.job = null;
            ended.put(job.jobId, JobFate.CANCELLED);
        }

        @Override
        public CpuCapture captureCpus(String gridKey, java.util.Set<String> watchedJobIds) {
            requireServerThread();
            requireGrid(gridKey);
            List<CpuState> states = new java.util.ArrayList<>();
            Map<String, JobFate> fates = new HashMap<>();
            for (FakeCpu cpu : cpus) {
                FakeJob job = cpu.job;
                JobState jobState = job == null ? null
                        : new JobState(job.jobId, job.output, job.amount, job.progress, Duration.ofSeconds(3));
                states.add(new CpuState(cpu.id, cpu.name == null ? null : ResourceText.literal(cpu.name),
                        null, job != null, cpu.online, cpu.storage, 1, cpu.selectionMode, jobState));
                if (job != null && watchedJobIds.contains(job.jobId)) {
                    fates.put(job.jobId, JobFate.RUNNING);
                }
            }
            for (String jobId : watchedJobIds) {
                fates.putIfAbsent(jobId, ended.getOrDefault(jobId, JobFate.NOT_FOUND));
            }
            return new CpuCapture(Instant.now(), states, fates);
        }

        @Override
        public Calculation beginCalculation(String gridKey, ResourceId resource, long amount, PlayerProfile requester) {
            requireServerThread();
            requireGrid(gridKey);
            FakeRecipe recipe = recipes.get(resource);
            if (recipe == null) {
                throw new MeccException(ErrorCode.NOT_CRAFTABLE, "Nothing can craft this");
            }
            return new FakeCalculation(recipe, amount);
        }

        @Override
        public SubmitOutcome submit(String gridKey, Calculation calculation, String cpuId, PlayerProfile requester) {
            requireServerThread();
            requireGrid(gridKey);
            submissions.incrementAndGet();
            String reject = rejectNext;
            if (reject != null) {
                rejectNext = null;
                return SubmitOutcome.rejected(reject, Map.of());
            }
            FakeCalculation plan = (FakeCalculation) calculation;
            if (!plan.recipe.complete()) {
                return SubmitOutcome.rejected("PLAN_INCOMPLETE", Map.of());
            }
            if (cpus.isEmpty()) {
                return SubmitOutcome.rejected("NO_CRAFTING_CPU", Map.of());
            }
            FakeCpu target;
            if (cpuId != null) {
                target = cpus.stream().filter(cpu -> cpu.id.equals(cpuId)).findFirst().orElse(null);
                if (target == null) return SubmitOutcome.rejected("CPU_NOT_FOUND", Map.of());
                if (target.job != null) return SubmitOutcome.rejected("CPU_BUSY", Map.of());
                if (target.storage < plan.recipe.bytes()) return SubmitOutcome.rejected("CPU_TOO_SMALL", Map.of());
            } else {
                target = cpus.stream().filter(cpu -> cpu.job == null && cpu.online && cpu.storage >= plan.recipe.bytes()
                        && !cpu.selectionMode.equals("MACHINE_ONLY")).findFirst().orElse(null);
                if (target == null) return SubmitOutcome.rejected("NO_SUITABLE_CPU", Map.of("busy", 1));
            }
            FakeJob job = new FakeJob(UUID.randomUUID().toString(), plan.recipe.output(), plan.amount);
            target.job = job;
            return SubmitOutcome.started(target.id, target.name == null ? null : ResourceText.literal(target.name), job.jobId);
        }

        @Override
        public CancelOutcome cancel(String gridKey, String cpuId, String jobId, ResourceId expectedOutput) {
            requireServerThread();
            FakeCpu cpu = cpus.stream().filter(candidate -> candidate.id.equals(cpuId)).findFirst().orElse(null);
            if (cpu == null) {
                return CancelOutcome.CPU_NOT_FOUND;
            }
            FakeJob job = cpu.job;
            if (job == null || (jobId != null && !jobId.equals(job.jobId))) {
                return CancelOutcome.NOT_RUNNING;
            }
            cpu.job = null;
            ended.put(job.jobId, JobFate.CANCELLED);
            return CancelOutcome.CANCELLED;
        }

        private void requireGrid(String gridKey) {
            if (grids.stream().noneMatch(grid -> grid.runtimeKey().equals(gridKey))) {
                throw new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now");
            }
        }

        private final class FakeCalculation implements Calculation {
            private final FakeRecipe recipe;
            private final long amount;

            private FakeCalculation(FakeRecipe recipe, long amount) {
                this.recipe = recipe;
                this.amount = amount;
            }

            @Override
            public boolean isDone() {
                return !holdCalculations;
            }

            @Override
            public PlanSummary summary() {
                return new PlanSummary(recipe.output(), amount, recipe.complete(), recipe.bytes(), false, recipe.entries());
            }

            @Override
            public void cancel() {
            }
        }
    }

    /** A descriptor as the storage platform would describe the resource. */
    public static ResourceDescriptor descriptor(String type, String namespace, String path) {
        String key = type + "." + namespace + "." + path.replace('/', '.');
        return new ResourceDescriptor(ResourceId.of(type, namespace, path), key, ResourceText.translatable(key, List.of()),
                namespace, type.equals("fluid") ? new ResourceDescriptor.ResourceUnit("B", 1000) : null,
                type + "/" + namespace + "/" + path);
    }

    @Override
    public AssetPlatform assets() {
        return new AssetPlatform() {
            @Override
            public List<AssetPack> assetPacks() {
                return List.copyOf(assetPacks);
            }

            @Override
            public Map<String, String> modNames() {
                return Map.copyOf(modNames);
            }
        };
    }

    @Override
    public Ae2Platform ae2() {
        return new Ae2Platform() {
            @Override
            public Optional<String> installedVersion() {
                return Optional.ofNullable(ae2Version);
            }

            @Override
            public String testedVersion() {
                return "15.4.10";
            }
        };
    }

    private void requireServerThread() {
        if (Thread.currentThread() != serverThread.get()) {
            throw new IllegalStateException("server-thread-only platform method called off the server thread");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
