package io.github.codaaaaaa.mecc.core.persistence;

import io.github.codaaaaaa.mecc.core.audit.AuditRepository;
import io.github.codaaaaaa.mecc.core.auth.DeviceRepository;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository;
import io.github.codaaaaaa.mecc.core.networks.NetworkRepository;
import io.github.codaaaaaa.mecc.core.users.UserRepository;

/** Repositories bound to the current {@link DataStore} unit of work. Only valid inside that callback. */
public interface Repositories {
    UserRepository users();

    DeviceRepository devices();

    NetworkRepository networks();

    AuditRepository audit();

    CraftingOrderRepository orders();
}
