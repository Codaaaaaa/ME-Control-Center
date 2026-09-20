package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.alerts.AlertRepository;
import io.github.codaaaaaa.mecc.core.audit.AuditRepository;
import io.github.codaaaaaa.mecc.core.automation.RestockRepository;
import io.github.codaaaaaa.mecc.core.auth.DeviceRepository;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository;
import io.github.codaaaaaa.mecc.core.crafting.SavedOrderRepository;
import io.github.codaaaaaa.mecc.core.insights.SampleRepository;
import io.github.codaaaaaa.mecc.core.insights.WatchlistRepository;
import io.github.codaaaaaa.mecc.core.networks.NetworkRepository;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeploymentRepository;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraftRepository;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.users.UserRepository;

final class SqliteRepositories implements Repositories {
    private final UserRepository users;
    private final DeviceRepository devices;
    private final NetworkRepository networks;
    private final AuditRepository audit;
    private final CraftingOrderRepository orders;
    private final PatternDraftRepository patternDrafts;
    private final PatternDeploymentRepository patternDeployments;
    private final WatchlistRepository watchlist;
    private final SampleRepository samples;
    private final SavedOrderRepository savedOrders;
    private final AlertRepository alerts;
    private final RestockRepository restock;

    SqliteRepositories(Jdbc jdbc) {
        this.users = new SqliteUserRepository(jdbc);
        this.devices = new SqliteDeviceRepository(jdbc);
        this.networks = new SqliteNetworkRepository(jdbc);
        this.audit = new SqliteAuditRepository(jdbc);
        this.orders = new SqliteOrderRepository(jdbc);
        this.patternDrafts = new SqlitePatternDraftRepository(jdbc);
        this.patternDeployments = new SqlitePatternDeploymentRepository(jdbc);
        this.watchlist = new SqliteWatchlistRepository(jdbc);
        this.samples = new SqliteSampleRepository(jdbc);
        this.savedOrders = new SqliteSavedOrderRepository(jdbc);
        this.alerts = new SqliteAlertRepository(jdbc);
        this.restock = new SqliteRestockRepository(jdbc);
    }

    @Override
    public UserRepository users() {
        return users;
    }

    @Override
    public DeviceRepository devices() {
        return devices;
    }

    @Override
    public NetworkRepository networks() {
        return networks;
    }

    @Override
    public AuditRepository audit() {
        return audit;
    }

    @Override
    public CraftingOrderRepository orders() {
        return orders;
    }

    @Override
    public PatternDraftRepository patternDrafts() {
        return patternDrafts;
    }

    @Override
    public PatternDeploymentRepository patternDeployments() {
        return patternDeployments;
    }

    @Override
    public WatchlistRepository watchlist() {
        return watchlist;
    }

    @Override
    public SampleRepository samples() {
        return samples;
    }

    @Override
    public SavedOrderRepository savedOrders() {
        return savedOrders;
    }

    @Override
    public AlertRepository alerts() {
        return alerts;
    }

    @Override
    public RestockRepository restock() {
        return restock;
    }
}
