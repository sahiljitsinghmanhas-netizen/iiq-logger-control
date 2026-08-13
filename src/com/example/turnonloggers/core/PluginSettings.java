package com.example.turnonloggers.core;

import sailpoint.api.SailPointContext;
import sailpoint.object.Plugin;
import sailpoint.plugin.Setting;

import java.util.Locale;

/**
 * Reads this plugin's settings straight off the Plugin object.
 *
 * BasePluginResource already gives the REST layer getSettingString()/etc, but
 * the background sync service is not a REST resource and has no PluginContext.
 * Rather than have the two paths read settings in two different ways (and
 * drift), both go through here.
 *
 * Settings edited in gear -> Plugins take effect on the next read; there is no
 * cache to invalidate and no restart needed.
 */
public final class PluginSettings {

    public static final String PLUGIN_NAME = "TurnOnLoggers";

    public static final String S_ENABLED       = "enabled";
    public static final String S_REQUIRED_CAP  = "requiredCapability";
    public static final String S_DEFAULT_TTL   = "defaultTtlMinutes";
    public static final String S_MAX_TTL       = "maxTtlMinutes";
    public static final String S_ALLOW_ROOT    = "allowRootLogger";
    public static final String S_PERMANENT     = "permanentLoggers";

    private PluginSettings() {
    }

    public static String getString(SailPointContext ctx, String name, String dflt) {
        try {
            Plugin p = ctx.getObjectByName(Plugin.class, PLUGIN_NAME);
            if (p == null) return dflt;
            Setting s = p.getSetting(name);
            if (s == null) return dflt;
            String v = s.getValue();
            if (v == null) v = s.getDefaultValue();
            return (v == null || v.trim().isEmpty()) ? dflt : v.trim();
        } catch (Throwable t) {
            return dflt;
        }
    }

    public static boolean getBool(SailPointContext ctx, String name, boolean dflt) {
        String v = getString(ctx, name, null);
        if (v == null) return dflt;
        v = v.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v);
    }

    public static int getInt(SailPointContext ctx, String name, int dflt) {
        return LoggerConfigStore.asInt(getString(ctx, name, null), dflt);
    }
}
