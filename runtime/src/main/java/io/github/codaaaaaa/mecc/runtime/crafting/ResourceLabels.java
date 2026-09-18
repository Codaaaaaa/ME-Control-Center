package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.resources.NameSpan;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import io.github.codaaaaaa.mecc.runtime.resources.DefaultResourceService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Turns resource descriptors and frozen order targets into localized labels. Thread-safe. */
public final class ResourceLabels {
    private final Supplier<ResourceNames> names;
    private final Map<String, String> modNames;

    public ResourceLabels(Supplier<ResourceNames> names, Map<String, String> modNames) {
        this.names = names;
        this.modNames = Map.copyOf(modNames);
    }

    public ResourceLabel label(ResourceDescriptor descriptor, String locale) {
        List<NameSpan> spans = names.get().spans(descriptor, locale);
        StringBuilder plain = new StringBuilder();
        spans.forEach(span -> plain.append(span.text()));
        return new ResourceLabel(descriptor.id().toString(), descriptor.id().type(), plain.toString(),
                spans.stream().anyMatch(NameSpan::styled) ? spans : null, descriptor.modId(), modName(descriptor.modId()),
                descriptor.unit(), descriptor.iconKey());
    }

    public ResourceLabel label(OrderTarget target, String locale) {
        return new ResourceLabel(target.resourceId().toString(), target.resourceId().type(), target.name(locale), null,
                target.modId(), modName(target.modId()), target.unit(), target.iconKey());
    }

    /** Freezes a resource for order history, with its name in every UI locale. */
    public OrderTarget target(ResourceDescriptor descriptor) {
        Map<String, String> localized = new HashMap<>();
        ResourceNames current = names.get();
        for (String locale : DefaultResourceService.LOCALES) {
            localized.put(locale, current.displayName(descriptor, locale));
        }
        return new OrderTarget(descriptor.id(), localized, descriptor.modId(), descriptor.iconKey(), descriptor.unit());
    }

    /** Game text such as a CPU name, or {@code null}. */
    public String text(ResourceText text, String locale) {
        return names.get().plainText(text, locale);
    }

    private String modName(String modId) {
        return modNames.getOrDefault(modId, modId);
    }
}
