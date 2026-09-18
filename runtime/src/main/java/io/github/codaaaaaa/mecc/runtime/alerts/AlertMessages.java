package io.github.codaaaaaa.mecc.runtime.alerts;

import io.github.codaaaaaa.mecc.core.alerts.AlertEvent;
import io.github.codaaaaaa.mecc.core.alerts.AlertEvent.Kind;
import io.github.codaaaaaa.mecc.core.alerts.AlertType;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * One-line texts for webhook notifications, in English or Simplified Chinese. The web UI builds its own texts
 * from the structured event.
 */
final class AlertMessages {
    private AlertMessages() {
    }

    static String text(AlertEvent event, String networkName, String locale) {
        boolean zh = "zh_cn".equals(locale);
        String resource = event.resource() == null ? "" : event.resource().name(zh ? "zh_cn" : "en_us");
        boolean percent = event.type().threshold() == AlertType.Threshold.PERCENT;
        String value = amount(event.value(), percent ? null : event.resource());
        String threshold = amount(event.threshold(), event.type().threshold() == AlertType.Threshold.AMOUNT
                ? event.resource() : null);
        boolean triggered = event.kind() == Kind.TRIGGERED;
        String body = switch (event.type()) {
            case RESOURCE_BELOW -> triggered
                    ? (zh ? "%s 低于 %s：剩余 %s" : "%s is below %s: %s left").formatted(resource, threshold, value)
                    : (zh ? "%s 已恢复到 %s（阈值 %s）" : "%s is back at %s (threshold %s)").formatted(resource, value, threshold);
            case RESOURCE_ABOVE -> triggered
                    ? (zh ? "%s 高于 %s：当前 %s" : "%s is above %s: %s").formatted(resource, threshold, value)
                    : (zh ? "%s 已回落到 %s（阈值 %s）" : "%s is back at %s (threshold %s)").formatted(resource, value, threshold);
            case RESOURCE_DROP -> triggered
                    ? (zh ? "%s 下降了 %s%%（阈值 %s%%）" : "%s dropped %s%% (limit %s%%)").formatted(resource, value, threshold)
                    : (zh ? "%s 的下降已回到 %s%% 以内" : "%s is no longer down %s%% or more").formatted(resource, threshold);
            case RESOURCE_RISE -> triggered
                    ? (zh ? "%s 上涨了 %s%%（阈值 %s%%）" : "%s rose %s%% (limit %s%%)").formatted(resource, value, threshold)
                    : (zh ? "%s 的上涨已回到 %s%% 以内" : "%s is no longer up %s%% or more").formatted(resource, threshold);
            case CRAFT_STALLED -> triggered
                    ? (zh ? "合成 %s × %s 已 %s 分钟没有进展" : "Crafting %s × %s made no progress for %s min")
                            .formatted(value, resource, threshold)
                    : (zh ? "合成 %s × %s 恢复进展" : "Crafting %s × %s is progressing again").formatted(value, resource);
            case NETWORK_OFFLINE -> triggered
                    ? (zh ? "网络离线" : "The network is offline")
                    : (zh ? "网络已恢复在线" : "The network is back online");
            case ENERGY_LOW -> triggered
                    ? (zh ? "能量不足：%s%%（低于 %s%%）" : "Energy is low: %s%% (below %s%%)").formatted(value, threshold)
                    : (zh ? "能量已恢复：%s%%" : "Energy recovered: %s%%").formatted(value);
            case CPU_SATURATED -> triggered
                    ? (zh ? "所有合成 CPU 都在忙" : "All crafting CPUs are busy")
                    : (zh ? "有合成 CPU 空闲了" : "A crafting CPU is free again");
            case CRAFT_COMPLETED -> (zh ? "合成完成：%s × %s" : "Crafting finished: %s × %s").formatted(value, resource);
            case CRAFT_FAILED -> (zh ? "合成失败：%s × %s" : "Crafting failed: %s × %s").formatted(value, resource);
        };
        return networkName == null ? body : "[" + networkName + "] " + body;
    }

    /** Raw amount in the resource's unit (e.g. millibuckets as buckets), grouped; {@code ?} when unknown. */
    static String amount(Long raw, OrderTarget resource) {
        if (raw == null) {
            return "?";
        }
        ResourceUnit unit = resource == null ? null : resource.unit();
        if (unit == null || unit.amountPerUnit() <= 1) {
            return String.format(Locale.ROOT, "%,d", raw);
        }
        BigDecimal scaled = BigDecimal.valueOf(raw).divide(BigDecimal.valueOf(unit.amountPerUnit()), 3, RoundingMode.HALF_UP);
        return scaled.stripTrailingZeros().toPlainString() + " " + unit.symbol();
    }
}
