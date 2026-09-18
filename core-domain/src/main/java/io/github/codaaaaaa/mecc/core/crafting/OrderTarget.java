package io.github.codaaaaaa.mecc.core.crafting;

import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.util.Map;
import java.util.Objects;

/**
 * What an order crafts, frozen at submission so history stays readable after the resource leaves storage
 * or the modpack changes.
 *
 * @param names display name per UI locale, e.g. {@code en_us} and {@code zh_cn}
 * @param unit  amount unit, or {@code null} for plain counts
 */
public record OrderTarget(ResourceId resourceId, Map<String, String> names, String modId, String iconKey, ResourceUnit unit) {
    public OrderTarget {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(iconKey, "iconKey");
        names = Map.copyOf(names);
    }

    /** Name in {@code locale}, falling back to English and then to the registry id. */
    public String name(String locale) {
        String name = names.get(locale);
        if (name == null || name.isEmpty()) {
            name = names.get("en_us");
        }
        return name == null || name.isEmpty() ? resourceId.registryId() : name;
    }
}
