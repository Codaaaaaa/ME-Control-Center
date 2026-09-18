package io.github.codaaaaaa.mecc.core.auth;

import java.util.Locale;

/** Derives a friendly default device name such as "Chrome · Windows" from a User-Agent header. */
public final class DeviceNames {
    private DeviceNames() {
    }

    public static String fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Browser";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = browser(ua);
        String platform = platform(ua);
        if (browser == null && platform == null) {
            return "Browser";
        }
        if (browser == null) {
            return platform;
        }
        return platform == null ? browser : browser + " · " + platform;
    }

    private static String browser(String ua) {
        // Order matters: most browsers also claim to be Chrome and/or Safari.
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("firefox/") || ua.contains("fxios/")) return "Firefox";
        if (ua.contains("samsungbrowser/")) return "Samsung Internet";
        if (ua.contains("micromessenger/")) return "WeChat";
        if (ua.contains("chrome/") || ua.contains("crios/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        if (ua.contains("curl/")) return "curl";
        return null;
    }

    private static String platform(String ua) {
        if (ua.contains("iphone")) return "iPhone";
        if (ua.contains("ipad")) return "iPad";
        if (ua.contains("android")) return "Android";
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("linux")) return "Linux";
        return null;
    }
}
