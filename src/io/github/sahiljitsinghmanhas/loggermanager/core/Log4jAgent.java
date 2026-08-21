package io.github.sahiljitsinghmanhas.loggermanager.core;

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
     * Every logger this plugin has ever created a LoggerConfig for on this
     * host, kept durably (see LoggerConfigStore) across plugin reinstalls.
     *
     * This exists because "live, not in the file, not currently mine" is NOT
     * enough to conclude a logger was stranded by an old copy of this plugin.
     * Anything running in the JVM can set a level programmatically - an IIQ
     * rule doing Logger.getLogger("Rule.X").setLevel(DEBUG) creates exactly
     * such a LoggerConfig. Treating those as our litter and deleting them
     * would switch off logging somebody deliberately configured elsewhere.
     *
     * Only names in here are ever eligible for automatic cleanup.
     */
    private static final Set<String> CREATED = new LinkedHashSet<>();

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

        // 3. Drop claims on loggers that are no longer there.
        //
        // Reverting releases its own claim, but that only helps from now on.
        // Hosts that reverted an override before this existed still carry the
        // name, and a logger can also disappear without going through us at all
        // - log4j2 rebuilding from an edited file takes every logger we added
        // with it. A name in CREATED that is not a live logger is a claim on
        // something that does not exist, and leaving it there is what makes a
        // rule's logger look like our litter later.
        pruneCreated(ctx.getConfiguration());

        // Report what log4j2 actually resolves to now, not what we asked for.
        // This is the bit the UI shows as proof the change landed on the host.
        Configuration after = ctx.getConfiguration();
        for (String name : want.keySet()) {
            res.applied.put(display(name), effectiveLevel(after, name));
        }
        return res;
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
     * Re-adopt loggers a previous instance of this plugin owned.
     *
     * OWNED is a static in the plugin's own classloader, but log4j2's
     * Configuration belongs to the webapp and outlives it. Reinstalling or
     * upgrading the plugin therefore replaces the classloader - wiping OWNED -
     * while every LoggerConfig the old instance created is still live. Turning
     * that logger off afterwards then reverted nothing, stranding it at DEBUG
     * or TRACE with no trace of it in the UI.
     *
     * So ownership is persisted per host (see LoggerConfigStore) and adopted
     * here on the first sync after a reinstall, which puts those loggers back
     * under management so they can be reverted normally.
     *
     * @param persisted logger name -> the level it had before we took it over,
     *                  or "" if we created it and reverting means deleting it.
     */
    public static synchronized void adopt(Map<String, String> persisted, List<String> created) {
        if (created != null) {
            for (String c : created) {
                String n = normalize(c);
                if (n != null) CREATED.add(n);
            }
        }
        if (persisted == null || persisted.isEmpty()) return;

        Configuration cfg;
        try {
            cfg = context().getConfiguration();
        } catch (Throwable t) {
            return;
        }
        // Bind to the current Configuration, otherwise apply() would treat this
        // as a reconfiguration and clear what we just adopted.
        if (ownedAgainst != cfg) {
            OWNED.clear();
            ownedAgainst = cfg;
        }
        for (Map.Entry<String, String> e : persisted.entrySet()) {
            String name = normalize(e.getKey());
            if (name == null || OWNED.containsKey(name)) continue;
            String original = e.getValue();
            if (original == null || original.trim().isEmpty()) {
                OWNED.put(name, new Snapshot(false, null));
            } else {
                Level lvl = parseLevel(original);
                OWNED.put(name, lvl == null ? new Snapshot(false, null) : new Snapshot(true, lvl));
            }
        }
    }

    /** Loggers this plugin created here, for durable storage. See adopt(). */
    public static synchronized List<String> createdSnapshot() {
        List<String> out = new ArrayList<>();
        for (String n : CREATED) out.add(display(n));
        return out;
    }

    /** Ownership in a form that survives a plugin reinstall. See adopt(). */
    public static synchronized Map<String, String> ownedSnapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Snapshot> e : OWNED.entrySet()) {
            Snapshot s = e.getValue();
            m.put(display(e.getKey()), (s.exact && s.level != null) ? s.level.name() : "");
        }
        return m;
    }

    /**
     * Logger names this host's log4j2 configuration file actually declares, or
     * null if that cannot be determined.
     *
     * Without this, "is this logger from the file?" can only be guessed as
     * "not currently owned by us", which is wrong for anything a previous
     * plugin instance stranded - it reports leftovers as though an admin had
     * put them in log4j2.properties. Reading the file the JVM already loaded
     * settles it.
     */
    public static synchronized Set<String> fileDeclaredLoggers() {
        Map<String, String> levels = fileDeclaredLevels();
        return levels == null ? null : new LinkedHashSet<>(levels.keySet());
    }

    /**
     * Logger name -> the level this host's configuration file declares for it,
     * or null if the file cannot be read.
     *
     * Reading the levels as well as the names is what lets the page say "this
     * logger is in the file, but it is running at something else" - drift that
     * name-matching alone cannot see, whether caused by a leftover override on
     * a file-declared logger or by something else changing it at runtime.
     */
    public static synchronized Map<String, String> fileDeclaredLevels() {
        java.io.InputStream in = null;
        try {
            Configuration cfg = context().getConfiguration();
            ConfigurationSource src = cfg.getConfigurationSource();
            if (src == null) return null;

            String loc = src.getLocation();
            // Only the properties format is parsed here; for XML/YAML/JSON we
            // return null and the UI says "source unknown" rather than lying.
            if (loc == null || !loc.toLowerCase(Locale.ROOT).endsWith(".properties")) return null;

            java.io.File f = null;
            try {
                f = src.getFile();
            } catch (Throwable ignored) {
                // some ConfigurationSource flavours have no file
            }
            if (f != null && f.canRead()) {
                in = new java.io.FileInputStream(f);
            } else {
                java.net.URL u = null;
                try {
                    u = src.getURL();
                } catch (Throwable ignored) {
                    // no URL either
                }
                if (u != null) in = u.openStream();
            }
            if (in == null) return null;

            java.util.Properties p = new java.util.Properties();
            p.load(in);

            // The properties format keys a logger by an arbitrary token:
            //   logger.foo.name  = sailpoint.api.Provisioner
            //   logger.foo.level = DEBUG
            // so gather names and levels by that token, then pair them up.
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, String> levels = new LinkedHashMap<>();
            String rootLevel = null;

            for (String key : p.stringPropertyNames()) {
                if ("rootLogger.level".equals(key)) {
                    rootLevel = p.getProperty(key);
                } else if (key.startsWith("logger.")) {
                    if (key.endsWith(".name")) {
                        names.put(key.substring(7, key.length() - 5), p.getProperty(key));
                    } else if (key.endsWith(".level")) {
                        levels.put(key.substring(7, key.length() - 6), p.getProperty(key));
                    }
                }
            }

            Map<String, String> declared = new LinkedHashMap<>();
            declared.put("", rootLevel == null ? "" : rootLevel.trim().toUpperCase(Locale.ROOT));
            for (Map.Entry<String, String> e : names.entrySet()) {
                String n = normalize(e.getValue());
                if (n == null) continue;
                String lvl = levels.get(e.getKey());
                declared.put(n, lvl == null ? "" : lvl.trim().toUpperCase(Locale.ROOT));
            }
            return declared;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (java.io.IOException ignored) { /* closing */ }
            }
        }
    }

    /**
     * Remove loggers that are live in this JVM but neither declared in the
     * configuration file nor currently managed by the plugin - i.e. stranded by
     * an earlier plugin instance.
     *
     * Refuses to act if the file's declared set cannot be determined, because
     * then "not in the file" is a guess and deleting on a guess could switch
     * off logging somebody else configured.
     *
     * @return the loggers removed
     */
    public static synchronized List<String> clearRuntimeLeftovers() {
        List<String> removed = new ArrayList<>();
        Set<String> declared = fileDeclaredLoggers();
        if (declared == null) return removed;

        LoggerContext ctx;
        Configuration cfg;
        try {
            ctx = context();
            cfg = ctx.getConfiguration();
        } catch (Throwable t) {
            return removed;
        }
        Map<String, LoggerConfig> loggers = cfg.getLoggers();
        if (loggers == null) return removed;

        for (String raw : new ArrayList<>(loggers.keySet())) {
            String name = normalize(raw == null ? "" : raw);
            if (isRoot(name)) continue;                 // never remove root
            if (declared.contains(name)) continue;      // the file wants it
            if (OWNED.containsKey(name)) continue;      // we are managing it now
            // Only ever remove loggers this plugin created. Anything else live
            // was put there by a rule or custom code and is not ours to touch.
            if (!CREATED.contains(name)) continue;
            try {
                cfg.removeLogger(name);
                CREATED.remove(name);
                removed.add(display(name));
            } catch (Throwable t) {
                LOG.warn("[TurnOnLoggers] could not remove stranded logger " + display(name) + ": " + t);
            }
        }
        if (!removed.isEmpty()) {
            ctx.updateLoggers();
            LOG.info("[TurnOnLoggers] cleared stranded runtime loggers: " + removed);
        }
        return removed;
    }

    /**
     * Remove one specific logger that is live, by name.
     *
     * Separate from clearRuntimeLeftovers because this is a deliberate, named
     * request from an administrator, not an automatic sweep. clearRuntimeLeftovers
     * only ever removes loggers this plugin created, because it runs on its own
     * with nobody looking at what it is about to do; this runs because a person
     * looked at one specific row and asked for it, which is a different amount
     * of trust. So it will remove a logger something else set (a rule, custom
     * Java) or one the file declares - it still refuses to touch root, or
     * anything currently held by an override, since those have their own
     * lifecycle.
     *
     * A file-declared logger removed this way is not gone for good: log4j2's
     * monitorInterval only rebuilds the running configuration when the file on
     * disk actually changes, so it stays cleared until someone edits and saves
     * log4j2.properties, or the JVM restarts, either of which rebuilds
     * everything from the file regardless of what this method did. That is the
     * same one-shot shape as clearing a rule-set logger, not a special case.
     *
     * @return true if it was removed
     */
    public static synchronized boolean removeRuntimeLogger(String rawName) {
        String name = normalize(rawName);
        if (name == null || isRoot(name)) return false;
        if (OWNED.containsKey(name)) return false;                      // managed here

        try {
            LoggerContext ctx = context();
            Configuration cfg = ctx.getConfiguration();
            Map<String, LoggerConfig> loggers = cfg.getLoggers();
            if (loggers == null || !loggers.containsKey(name)) return false;
            cfg.removeLogger(name);
            CREATED.remove(name);
            ctx.updateLoggers();
            LOG.info("[TurnOnLoggers] removed runtime logger " + display(name) + " on request");
            return true;
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] could not remove runtime logger " + display(name) + ": " + t);
            return false;
        }
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

        Map<String, String> fileLevels = fileDeclaredLevels();
        Set<String> declared = fileLevels == null ? null : fileLevels.keySet();

        for (Map.Entry<String, LoggerConfig> e : loggers.entrySet()) {
            String name = normalize(e.getKey() == null ? "" : e.getKey());
            LoggerConfig lc = e.getValue();
            if (lc == null) continue;
            boolean managed = OWNED.containsKey(name);

            String source;
            if (managed) {
                source = "plugin";
            } else if (declared == null) {
                source = "unknown";          // could not read the file
            } else if (declared.contains(name)) {
                source = "file";
            } else if (CREATED.contains(name)) {
                source = "leftover";         // we created it and lost track of it
            } else {
                // Live, not in the file, and not something we ever created -
                // so something else set it: a rule, custom Java, a connector.
                // Not ours to remove.
                source = "runtime";
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("logger", display(name));
            row.put("level", lc.getLevel() == null ? "?" : lc.getLevel().name());
            row.put("managed", String.valueOf(managed));
            row.put("source", source);
            row.put("fileLevel", (declared == null || fileLevels == null) ? ""
                    : String.valueOf(fileLevels.get(name) == null ? "" : fileLevels.get(name)));
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
            CREATED.add(name);   // durable: this one is ours to clean up later
        }
    }

    /**
     * Forget any logger we claim to have created that is not currently live.
     *
     * Runs on every apply, so a host heals itself on its next sync rather than
     * needing anyone to notice. Deliberately only touches the ledger - it never
     * removes a logger, so a mistake here costs a mislabelled source at worst,
     * never someone's logging.
     */
    private static void pruneCreated(Configuration cfg) {
        if (CREATED.isEmpty()) return;
        Map<String, LoggerConfig> live;
        try {
            live = cfg.getLoggers();
        } catch (Throwable t) {
            return;   // cannot tell; keep the ledger as it is
        }
        if (live == null) return;
        List<String> dropped = new ArrayList<>();
        for (String name : new ArrayList<>(CREATED)) {
            if (!live.containsKey(name)) {
                CREATED.remove(name);
                dropped.add(display(name));
            }
        }
        if (!dropped.isEmpty()) {
            LOG.info("[TurnOnLoggers] released stale created-logger claims: " + dropped);
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
            }
            // If it is gone, log4j2.properties no longer declares it - leave it
            // inheriting rather than re-creating a logger the file dropped.
        } else {
            // We created this LoggerConfig; take it away again so the logger
            // goes back to inheriting from its ancestor.
            cfg.removeLogger(name);
            // And stop claiming it. Without this the name stayed in CREATED for
            // the life of the host, so if a rule later set the same logger it
            // was reported as this plugin's litter - and "Clear all left over"
            // would have offered to delete a logger a rule was actively using.
            CREATED.remove(name);
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

    /**
     * True for levels that can only ever reduce output.
     *
     * The TTL guard rail exists because debug logging left on by accident
     * fills disks. Silencing a logger carries the opposite risk, so it is
     * allowed to be permanent - otherwise the only way to quieten something
     * noisy in log4j2.properties is an override that expires and lets the
     * noise straight back.
     */
    public static boolean isQuieting(String level) {
        // OFF only. WARN or ERROR can be a reduction or an increase depending
        // on what the logger was already at, so "is this definitely turning
        // logging off" is the only question with an unambiguous answer, and
        // it is the only one allowed to skip the expiry.
        return Level.OFF.equals(parseLevel(level));
    }

    /** null if the level name is not one we allow. */
    public static Level parseLevel(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(s)) return null;
        return Level.getLevel(s);
    }
}
