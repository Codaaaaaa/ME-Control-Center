package io.github.codaaaaaa.mecc.core.command;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side chat text in en_us and zh_cn. ME Control Center is server-only, so clients have no ME Control Center language
 * files and command output cannot use client-side translation keys.
 */
public final class ServerText {
    private static final Map<String, String> EN_US = Map.ofEntries(
            Map.entry("mecc.not_running", "ME Control Center is not running. Check the server log."),
            Map.entry("mecc.starting", "ME Control Center is still starting. Try again in a moment."),
            Map.entry("mecc.internal_error", "ME Control Center could not complete the command. See the server log."),
            Map.entry("pair.players_only", "Only players can pair a browser."),
            Map.entry("pair.rate_limited", "Too many pairing keys requested. Try again in a few minutes."),
            Map.entry("pair.header", "ME Control Center pairing key: "),
            Map.entry("pair.copy_hint", " (click to copy)"),
            Map.entry("pair.instructions", "Enter it on the ME Control Center page within %d minutes. It works once. Never share it."),
            Map.entry("pair.open", "Open ME Control Center: "),
            Map.entry("pair.no_url", "Ask the server admin for the ME Control Center address."),
            Map.entry("devices.header_own", "Your ME Control Center devices (%d):"),
            Map.entry("devices.header_other", "ME Control Center devices of %s (%d):"),
            Map.entry("devices.none", "No paired devices."),
            Map.entry("devices.entry", " — last used %s"),
            Map.entry("devices.hint", "Revoke with /mecc revoke <id> or /mecc revoke all"),
            Map.entry("devices.unknown_player", "%s has never used ME Control Center."),
            Map.entry("devices.admin_only", "Only server admins can manage another player's devices."),
            Map.entry("revoke.done", "Device %s revoked."),
            Map.entry("revoke.all_done", "Revoked %d device(s)."),
            Map.entry("revoke.not_found", "No active device with ID %s."),
            Map.entry("revoke.console_all", "The console must name a device ID."),
            Map.entry("time.just_now", "just now"),
            Map.entry("time.minutes", "%d min ago"),
            Map.entry("time.hours", "%d h ago"),
            Map.entry("time.days", "%d d ago"));

    private static final Map<String, String> ZH_CN = Map.ofEntries(
            Map.entry("mecc.not_running", "ME 控制中心未运行，请查看服务器日志。"),
            Map.entry("mecc.starting", "ME 控制中心正在启动，请稍后再试。"),
            Map.entry("mecc.internal_error", "ME 控制中心无法完成该命令，请查看服务器日志。"),
            Map.entry("pair.players_only", "只有玩家可以配对浏览器。"),
            Map.entry("pair.rate_limited", "申请配对密钥过于频繁，请几分钟后再试。"),
            Map.entry("pair.header", "ME 控制中心配对密钥："),
            Map.entry("pair.copy_hint", "（点击复制）"),
            Map.entry("pair.instructions", "请在 %d 分钟内于 ME 控制中心页面输入。密钥仅可使用一次，切勿分享给他人。"),
            Map.entry("pair.open", "打开 ME 控制中心："),
            Map.entry("pair.no_url", "请向服务器管理员询问 ME 控制中心的访问地址。"),
            Map.entry("devices.header_own", "你的 ME 控制中心设备（%d）："),
            Map.entry("devices.header_other", "%s 的 ME 控制中心设备（%d）："),
            Map.entry("devices.none", "没有已配对的设备。"),
            Map.entry("devices.entry", " — 最近使用：%s"),
            Map.entry("devices.hint", "使用 /mecc revoke <id> 或 /mecc revoke all 撤销设备"),
            Map.entry("devices.unknown_player", "%s 从未使用过 ME 控制中心。"),
            Map.entry("devices.admin_only", "只有服务器管理员可以管理其他玩家的设备。"),
            Map.entry("revoke.done", "设备 %s 已撤销。"),
            Map.entry("revoke.all_done", "已撤销 %d 台设备。"),
            Map.entry("revoke.not_found", "没有 ID 为 %s 的有效设备。"),
            Map.entry("revoke.console_all", "控制台必须指定设备 ID。"),
            Map.entry("time.just_now", "刚刚"),
            Map.entry("time.minutes", "%d 分钟前"),
            Map.entry("time.hours", "%d 小时前"),
            Map.entry("time.days", "%d 天前"));

    private ServerText() {
    }

    /** Formats {@code key} for a Minecraft locale code such as {@code zh_cn}; unknown locales fall back to en_us. */
    public static String get(String locale, String key, Object... args) {
        Map<String, String> table = isChinese(locale) ? ZH_CN : EN_US;
        String template = table.getOrDefault(key, EN_US.get(key));
        if (template == null) {
            throw new IllegalArgumentException("Unknown server text key: " + key);
        }
        return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
    }

    public static String ago(String locale, Duration elapsed) {
        long minutes = Math.max(0, elapsed.toMinutes());
        if (minutes < 1) return get(locale, "time.just_now");
        if (minutes < 60) return get(locale, "time.minutes", minutes);
        if (minutes < 48 * 60) return get(locale, "time.hours", minutes / 60);
        return get(locale, "time.days", minutes / (24 * 60));
    }

    static boolean isChinese(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    static Map<String, String> table(String locale) {
        return isChinese(locale) ? ZH_CN : EN_US;
    }
}
