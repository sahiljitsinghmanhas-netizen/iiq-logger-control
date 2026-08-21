package io.github.sahiljitsinghmanhas.loggermanager.core;

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
    public static final String S_UNTOUCHABLE   = "untouchableLoggers";
    public static final String S_LOGTAIL       = "showLogFiles";
    public static final String S_LOGTAIL_KB    = "logTailKb";
    public static final String S_SERVERS_ONLY  = "hostsFromServersOnly";

    private PluginSettings() {
    }

    /** Version from the installed Plugin object, so the UI cannot drift from it. */
    public static String getVersion(SailPointContext ctx) {
        try {
            Plugin p = ctx.getObjectByName(Plugin.class, PLUGIN_NAME);
            if (p == null || p.getVersion() == null) return "";
            return p.getVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Loggers this plugin refuses to touch, from the untouchableLoggers
     * setting. Matched exactly, not by prefix - protecting "sailpoint" must not
     * protect every logger beneath it, or the plugin would be useless.
     */
    public static java.util.Set<String> untouchable(SailPointContext ctx) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        String raw = getString(ctx, S_UNTOUCHABLE, "root,sailpoint");
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Whether a logger is protected, matching the patterns exactly or by glob.
     *
     * A bare name still means that name and nothing beneath it, which is why
     * the shipped default of "sailpoint" does not protect
     * sailpoint.api.Provisioner - protecting the whole tree by accident would
     * leave the plugin unable to do its job. Writing "sailpoint.*" is how you
     * ask for the tree, deliberately.
     *
     * '*' matches any run of characters, dots included, so "sailpoint.*"
     * covers sailpoint.api.Provisioner and sailpoint.connector.LDAPConnector
     * alike, and "*.Provisioner" covers it from the other end. "sailpoint.*"
     * does not match "sailpoint" itself - that is what the bare name is for.
     */
    public static boolean isUntouchable(SailPointContext ctx, String logger) {
        return untouchableMatch(ctx, logger) != null;
    }

    /**
     * The pattern that protects this logger, or null.
     *
     * Returned rather than a boolean so the refusal can name the entry
     * responsible. With wildcards allowed, "it is in the untouchable list" is
     * not much help when the list says sailpoint.* and the logger you typed was
     * sailpoint.api.Provisioner.
     */
    public static String untouchableMatch(SailPointContext ctx, String logger) {
        if (logger == null) return null;
        String name = logger.trim().toLowerCase(Locale.ROOT);
        for (String pattern : untouchable(ctx)) {
            if (matches(pattern, name)) return pattern;
        }
        return null;
    }

    /** Glob match on an already-lowercased pattern and name. '*' only. */
    public static boolean matches(String pattern, String name) {
        if (pattern == null || name == null) return false;
        if (pattern.indexOf('*') < 0) return pattern.equals(name);

        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') re.append(".*");
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        try {
            return name.matches(re.toString());
        } catch (Throwable t) {
            // A pattern that will not compile protects nothing rather than
            // taking the page down with it.
            return false;
        }
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
