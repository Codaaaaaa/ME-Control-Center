package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.audit.AuditRepository;
import io.github.codaaaaaa.mecc.core.auth.DeviceRepository;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository;
import io.github.codaaaaaa.mecc.core.networks.NetworkRepository;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.users.UserRepository;

final class SqliteRepositories implements Repositories {
    private final UserRepository users;
    private final DeviceRepository devices;
    private final NetworkRepository networks;
    private final AuditRepository audit;
    private final CraftingOrderRepository orders;

    SqliteRepositories(Jdbc jdbc) {
        this.users = new SqliteUserRepository(jdbc);
        this.devices = new SqliteDeviceRepository(jdbc);
        this.networks = new SqliteNetworkRepository(jdbc);
        this.audit = new SqliteAuditRepository(jdbc);
        this.orders = new SqliteOrderRepository(jdbc);
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
}
