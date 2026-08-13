package com.example.turnonloggers.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies logger level overrides to the log4j2 runtime of the JVM this class
 * is loaded in.
 *
 * Everything here is in-memory: we mutate the live log4j2 {@link Configuration}
 * rather than rewriting log4j2.properties on disk. That is deliberate, and it
 * is what makes the plugin portable across Windows / Linux / macOS / container
 * hosts:
 *
 *   - no filesystem paths, no line-ending or file-permission handling
 *   - no assumption that the IIQ webapp directory is writable (it usually is
 *     not in a hardened deployment, and is read-only in most container images)
 *   - no shell, no SSH, no OS-specific service restart
 *
 * Durability across restarts comes from the DB instead: the desired state
 * lives in a Custom object and {@code LoggerSync} re-applies it on every host
 * on a timer, so a JVM that restarts picks the overrides back up on its first
 * service tick.
 *
 * Revert is precise. Before overriding a logger we snapshot whether an exact
 * LoggerConfig already existed for that name and at what level. On revert we
 * either put the original level back, or delete the LoggerConfig we created,
 * so removing an override cannot leave the host quieter or noisier than
 * log4j2.properties says it should be.
 */
public final class Log4jAgent {

    private static final org.apache.log4j.Logger LOG =
            org.apache.log4j.Logger.getLogger(Log4jAgent.class);

    /** What the UI shows and accepts for the root logger (log4j2 calls it ""). */
    public static final String ROOT_DISPLAY = "root";

    /** Levels we are willing to set. Ordered loudest-last for the UI. */
    public static final List<String> LEVELS = Collections.unmodifiableList(Arrays.asList(
            "OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"));

    private Log4jAgent() {
    }

    /** Original state of one logger, captured the first time we override it. */
    private static final class Snapshot {
        final boolean exact;   // an exact LoggerConfig existed before we touched it
        final Level level;     // ...and this was its level

        Snapshot(boolean exact, Level level) {
            this.exact = exact;
            this.level = level;
        }
    }

    /** Loggers this JVM is currently overriding -> their pre-override state. */
    private static final Map<String, Snapshot> OWNED = new LinkedHashMap<>();

    /**
     * The Configuration instance OWNED was captured against. log4j2 replaces
     * the whole Configuration object when it reconfigures (IIQ ships
     * monitorInterval=20, so editing log4j2.properties on the host triggers
     * exactly that). Snapshots taken against a dead Configuration are
     * meaningless, so we drop them and re-derive from the new one.
     */
    private static Configuration ownedAgainst;

    public static final class ApplyResult {
        /** display logger name -> effective level after apply */
        public final Map<String, String> applied = new LinkedHashMap<>();
        /** display logger names we handed back to log4j2.properties control */
        public final List<String> reverted = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean changed() {
            return !reverted.isEmpty();
        }
    }

    /**
     * Make this JVM's log4j2 runtime match {@code desired} exactly.
     *
     * @param desired logger name -> level name. Loggers previously set by this
     *                class but absent from the map are reverted.
     */
    public static synchronized ApplyResult apply(Map<String, String> desired) {
        ApplyResult res = new ApplyResult();

        LoggerContext ctx;
        try {
            ctx = context();
        } catch (Throwable t) {
            res.errors.add("log4j2 LoggerContext unavailable: " + t);
            return res;
        }

        Configuration cfg = ctx.getConfiguration();
        if (ownedAgainst != cfg) {
            // log4j2 reconfigured underneath us (file edit, or first call).
            // Our overrides are gone with the old Configuration; forget the
            // snapshots and re-apply from scratch against the new one.
            if (ownedAgainst != null && !OWNED.isEmpty()) {
                LOG.info("[TurnOnLoggers] log4j2 reconfigured; re-applying "
                        + OWNED.size() + " override(s) against the new configuration");
            }
            OWNED.clear();
            ownedAgainst = cfg;
        }

        // Normalise + validate first so one bad row can't half-apply the rest.
        Map<String, Level> want = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : desired.entrySet()) {
            String name = normalize(e.getKey());
            if (name == null) {
                res.errors.add("blank logger name ignored");
                continue;
            }
            Level lvl = parseLevel(e.getValue());
            if (lvl == null) {
                res.errors.add("unknown level '" + e.getValue() + "' for logger " + display(name));
                continue;
            }
            want.put(name, lvl);
        }

        // 1. Hand back anything we own that is no longer wanted.
        for (String owned : new ArrayList<>(OWNED.keySet())) {
            if (!want.containsKey(owned)) {
                try {
                    restore(cfg, owned);
                    res.reverted.add(display(owned));
                } catch (Throwable t) {
                    res.errors.add("revert " + display(owned) + " failed: " + t);
                }
            }
        }

        // 2. Apply / re-assert everything wanted.
        for (Map.Entry<String, Level> e : want.entrySet()) {
            String name = e.getKey();
            try {
                if (!OWNED.containsKey(name)) {
                    OWNED.put(name, snapshot(cfg, name));
                }
                set(cfg, name, e.getValue());
            } catch (Throwable t) {
                res.errors.add("apply " + display(name) + " failed: " + t);
            }
        }

        ctx.updateLoggers();

        // Report what log4j2 actually resolves to now, not what we asked for.
        // This is the bit the UI shows as proof the change landed on the host.
        Configuration after = ctx.getConfiguration();
        for (String name : want.keySet()) {
            res.applied.put(display(name), effectiveLevel(after, name));
        }
        return res;
    }

    /** Drop every override this JVM holds and restore log4j2.properties state. */
    public static synchronized ApplyResult revertAll() {
        return apply(Collections.<String, String>emptyMap());
    }

    /**
     * What log4j2 currently resolves the given loggers to on this host,
     * including whether the level is set on the logger itself or inherited
     * from an ancestor.
     */
    public static synchronized List<Map<String, String>> inspect(Set<String> loggerNames) {
        List<Map<String, String>> out = new ArrayList<>();
        Configuration cfg;
        try {
            cfg = context().getConfiguration();
        } catch (Throwable t) {
            return out;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String n : loggerNames) {
            String norm = normalize(n);
            if (norm != null) names.add(norm);
        }
        for (String name : names) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("logger", display(name));
            row.put("level", effectiveLevel(cfg, name));
            LoggerConfig lc = lookup(cfg, name);
            boolean exact = isRoot(name) || (lc != null && name.equals(lc.getName()));
            row.put("inherited", String.valueOf(!exact));
            row.put("inheritedFrom", exact ? "" : (lc == null ? "" : display(lc.getName())));
            row.put("managed", String.valueOf(OWNED.containsKey(name)));
            out.add(row);
        }
        return out;
    }

    /**
     * Every logger this JVM's log4j2 configuration currently defines, with a
     * flag saying whether this plugin is the one that set it.
     *
     * The unmanaged ones are the loggers that came from log4j2.properties -
     * either shipped by IIQ or added by hand on that host. The plugin never
     * touches them; this exists so the UI can show that they are there, since
     * otherwise a level set in the file is invisible from the page and someone
     * chasing "why is this logger already noisy" has nowhere to look.
     */
    public static synchronized List<Map<String, String>> configuredLoggers() {
        List<Map<String, String>> out = new ArrayList<>();
        Configuration cfg;
        try {
            cfg = context().getConfiguration();
        } catch (Throwable t) {
            return out;
        }
        Map<String, LoggerConfig> loggers;
        try {
            loggers = cfg.getLoggers();
        } catch (Throwable t) {
            return out;
        }
        if (loggers == null) return out;

        for (Map.Entry<String, LoggerConfig> e : loggers.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey();
            LoggerConfig lc = e.getValue();
            if (lc == null) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("logger", display(normalize(name)));
            row.put("level", lc.getLevel() == null ? "?" : lc.getLevel().name());
            row.put("managed", String.valueOf(OWNED.containsKey(normalize(name))));
            out.add(row);
        }
        return out;
    }

    /** Loggers this JVM is currently overriding, in display form. */
    public static synchronized List<String> managedLoggers() {
        List<String> out = new ArrayList<>();
        for (String n : OWNED.keySet()) out.add(display(n));
        return out;
    }

    /**
     * Where this host's log4j2 configuration was loaded from. Differs per OS
     * and per deployment (exploded webapp, WAR, container image, external
     * -Dlog4j.configurationFile) so it is worth surfacing in the UI: it tells
     * you which file to edit if you ever want to make an override permanent.
     */
    public static String configurationSource() {
        try {
            Configuration cfg = context().getConfiguration();
            ConfigurationSource src = cfg.getConfigurationSource();
            if (src == null) return "unknown";
            String loc = src.getLocation();
            if (loc != null && !loc.isEmpty()) return loc;
            return String.valueOf(src);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** True if we can talk to a real log4j2-core context in this JVM. */
    public static boolean available() {
        try {
            return context() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Resolve the LoggerContext that IIQ's own loggers live in.
     *
     * A plugin is loaded by a child classloader of the webapp classloader.
     * log4j2's default ClassLoaderContextSelector keys contexts by
     * classloader, so asking from the plugin's own loader can hand back a
     * different (empty) context. Asking on behalf of a class from
     * identityiq.jar pins us to the context that actually owns the
     * "sailpoint" loggers.
     */
    private static LoggerContext context() {
        ClassLoader iiqLoader = sailpoint.api.SailPointContext.class.getClassLoader();
        org.apache.logging.log4j.spi.LoggerContext c =
                LogManager.getContext(iiqLoader, false);
        if (c instanceof LoggerContext) {
            return (LoggerContext) c;
        }
        // Fall back to the caller's context; if that is not log4j2-core
        // either, the ClassCastException is caught by every caller.
        return (LoggerContext) LogManager.getContext(false);
    }

    private static Snapshot snapshot(Configuration cfg, String name) {
        if (isRoot(name)) {
            return new Snapshot(true, cfg.getRootLogger().getLevel());
        }
        LoggerConfig lc = lookup(cfg, name);
        if (lc != null && name.equals(lc.getName())) {
            return new Snapshot(true, lc.getLevel());
        }
        return new Snapshot(false, null);
    }

    private static void set(Configuration cfg, String name, Level level) {
        if (isRoot(name)) {
            cfg.getRootLogger().setLevel(level);
            return;
        }
        LoggerConfig lc = lookup(cfg, name);
        if (lc != null && name.equals(lc.getName())) {
            lc.setLevel(level);
        } else {
            // additive=true so records still reach the appenders configured on
            // the ancestor (in IIQ's case root -> stdout + the file appender).
            cfg.addLogger(name, new LoggerConfig(name, level, true));
        }
    }

    private static void restore(Configuration cfg, String name) {
        Snapshot s = OWNED.remove(name);
        if (s == null) return;

        if (isRoot(name)) {
            cfg.getRootLogger().setLevel(s.level == null ? Level.WARN : s.level);
            return;
        }
        if (s.exact) {
            LoggerConfig lc = lookup(cfg, name);
            if (lc != null && name.equals(lc.getName())) {
                lc.setLevel(s.level);
            } else {
                cfg.addLogger(name, new LoggerConfig(name, s.level, true));
            }
        } else {
            // We created this LoggerConfig; take it away again so the logger
            // goes back to inheriting from its ancestor.
            cfg.removeLogger(name);
        }
    }

    private static LoggerConfig lookup(Configuration cfg, String name) {
        return cfg.getLoggerConfig(isRoot(name) ? "" : name);
    }

    private static String effectiveLevel(Configuration cfg, String name) {
        LoggerConfig lc = lookup(cfg, name);
        if (lc == null || lc.getLevel() == null) return "?";
        return lc.getLevel().name();
    }

    /** null for an unusable name; "" is the (valid) root logger. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (ROOT_DISPLAY.equalsIgnoreCase(s)) return "";
        return s;
    }

    public static String display(String normalized) {
        return (normalized == null || normalized.isEmpty()) ? ROOT_DISPLAY : normalized;
    }

    public static boolean isRoot(String normalized) {
        return normalized == null || normalized.isEmpty();
    }

    /** null if the level name is not one we allow. */
    public static Level parseLevel(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(s)) return null;
        return Level.getLevel(s);
    }
}
