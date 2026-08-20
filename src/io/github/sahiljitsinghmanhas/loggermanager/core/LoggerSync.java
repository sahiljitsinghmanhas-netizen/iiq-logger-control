package io.github.sahiljitsinghmanhas.loggermanager.core;

import sailpoint.api.SailPointContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One pass of "make this host's log4j2 runtime match what the database says".
 *
 * Called from two places, and it is the same code both times:
 *   - the TurnOnLoggersSync service, on every host, on a timer. This is what
 *     propagates a change to the rest of the cluster and what restores state
 *     after a JVM restart.
 *   - the REST layer, immediately after an edit, so the host that served the
 *     click reflects the change before the response is even rendered.
 *
 * Idempotent by construction: it computes the full desired set every time and
 * reconciles, rather than replaying deltas. A host that missed ten changes
 * while it was down converges in one tick.
 */
public final class LoggerSync {

    private static final org.apache.log4j.Logger LOG =
            org.apache.log4j.Logger.getLogger(LoggerSync.class);

    private LoggerSync() {
    }

    public static final class SyncResult {
        public String host;
        public int revision;
        public boolean enabled;
        public Map<String, String> desired = new LinkedHashMap<>();
        public Map<String, String> applied = new LinkedHashMap<>();
        public List<String> reverted = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public long lastClear;
        public List<String> logMatches = new ArrayList<>();
        public long logAnsweredAt;
        public String logPath = "";
        public String logError = "";
    }

    public static synchronized SyncResult run(SailPointContext ctx, String trigger) {
        SyncResult r = new SyncResult();
        r.host = HostFacts.hostName();

        try {
            // The service context is long-lived; without this we can read our
            // own stale copy of the config object for minutes after an edit.
            ctx.decache();
        } catch (Throwable t) {
            LOG.debug("[TurnOnLoggers] decache failed (non-fatal): " + t);
        }

        r.enabled = PluginSettings.getBool(ctx, PluginSettings.S_ENABLED, true);
        boolean allowRoot = PluginSettings.getBool(ctx, PluginSettings.S_ALLOW_ROOT, false);
        long now = System.currentTimeMillis();

        // Take back ownership of anything a previous instance of this plugin
        // was managing before it was reinstalled. Without this, its loggers
        // are stranded in the live configuration with nothing able to revert
        // them, and turning them "off" in the UI silently does nothing.
        long lastClear = 0L;
        try {
            Log4jAgent.adopt(LoggerConfigStore.readOwned(ctx, r.host),
                    LoggerConfigStore.readCreated(ctx, r.host));
            lastClear = LoggerConfigStore.readLastClear(ctx, r.host);
            long requested = LoggerConfigStore.clearRequestedAt(ctx);
            if (requested > lastClear) {
                String target = LoggerConfigStore.clearRequestedLogger(ctx);
                List<String> cleared = new ArrayList<>();
                if (target != null && !target.trim().isEmpty()) {
                    if (Log4jAgent.removeRuntimeLogger(target)) cleared.add(target);
                } else {
                    cleared = Log4jAgent.clearRuntimeLeftovers();
                }
                lastClear = requested;
                if (!cleared.isEmpty()) {
                    LOG.info("[TurnOnLoggers] " + r.host + " cleared stranded loggers: " + cleared);
                }
            }
        } catch (Throwable t) {
            r.errors.add("could not restore previous ownership: " + t);
            LOG.warn("[TurnOnLoggers] adopt/cleanup failed on " + r.host + ": " + t);
        }
        r.lastClear = lastClear;

        Map<String, String> desired = new LinkedHashMap<>();

        if (r.enabled) {
            // Lowest precedence: loggers typed straight into the plugin's
            // settings page. Identical in effect to a UI entry, except they
            // have no TTL - anything set in the UI for the same logger wins.
            desired.putAll(permanentDesiredFor(
                    PluginSettings.getString(ctx, PluginSettings.S_PERMANENT, ""), r.host, r.errors));

            try {
                List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
                r.revision = LoggerConfigStore.revision(ctx);
                desired.putAll(LoggerConfigStore.desiredFor(entries, r.host, now));
            } catch (Throwable t) {
                // Could not read desired state. Do NOT revert what is already
                // applied - a transient DB hiccup should not silently switch
                // off the logging someone is mid-investigation with.
                r.errors.add("could not read configuration: " + t);
                LOG.warn("[TurnOnLoggers] config read failed on " + r.host + ": " + t);
                r.applied.putAll(currentlyApplied());
                writeStatusQuietly(ctx, r, trigger);
                return r;
            }
        } else {
            // Master switch off: hand every logger back to log4j2.properties.
            r.errors.add("plugin disabled - all overrides reverted");
        }

        if (!allowRoot) {
            for (String key : new ArrayList<>(desired.keySet())) {
                if (Log4jAgent.isRoot(key)) {
                    desired.remove(key);
                    r.errors.add("root logger override refused: enable 'Allow root logger' in plugin settings");
                }
            }
        }

        r.desired.putAll(desired);

        Log4jAgent.ApplyResult ar = Log4jAgent.apply(desired);
        r.applied.putAll(ar.applied);
        r.reverted.addAll(ar.reverted);
        r.errors.addAll(ar.errors);

        if (!ar.reverted.isEmpty() || !ar.errors.isEmpty()) {
            LOG.info("[TurnOnLoggers] " + r.host + " sync(" + trigger + "): applied=" + ar.applied
                    + " reverted=" + ar.reverted + " errors=" + ar.errors);
        }

        // Answer the cluster-wide log search, if one is running. No host can
        // read another's disk, so each one looks in its own file and publishes
        // what it found; the page merges them.
        try {
            if (LoggerConfigStore.logRequestActive(ctx)
                    && LoggerConfigStore.logTargets(ctx, r.host)) {
                String mode = LoggerConfigStore.logMode(ctx);
                LogTail.Answer a = "tail".equals(mode)
                        ? LogTail.tailLines(LoggerConfigStore.logLines(ctx))
                        : LogTail.search(LoggerConfigStore.logQuery(ctx));
                r.logMatches = a.lines;
                r.logError = a.error == null ? "" : a.error;
                r.logAnsweredAt = System.currentTimeMillis();
                List<String> files = HostFacts.logFilePaths();
                r.logPath = files.isEmpty() ? "" : files.get(0);
            }
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] log search failed on " + r.host + ": " + t);
            // Say so rather than staying silent: a host that never answers looks
            // like a host that is still thinking, and it would spin for ever.
            r.logError = String.valueOf(t);
            r.logAnsweredAt = System.currentTimeMillis();
        }

        writeStatusQuietly(ctx, r, trigger);
        return r;
    }

    private static Map<String, String> currentlyApplied() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, String> row : Log4jAgent.inspect(
                new java.util.LinkedHashSet<>(Log4jAgent.managedLoggers()))) {
            m.put(row.get("logger"), row.get("level"));
        }
        return m;
    }

    private static void writeStatusQuietly(SailPointContext ctx, SyncResult r, String trigger) {
        try {
            LoggerConfigStore.writeStatus(ctx, r.host, r.revision, trigger, r.applied, r.errors,
                    r.lastClear, r.logMatches, r.logAnsweredAt, r.logPath, r.logError);
        } catch (Throwable t) {
            // Status is diagnostics. Failing to publish it must never undo a
            // successful apply.
            LOG.warn("[TurnOnLoggers] could not write status for " + r.host + ": " + t);
        }
    }

    /**
     * Parses the permanentLoggers setting into displayable rows.
     *
     * Format: comma-separated {@code logger=LEVEL}, with an optional
     * {@code @host} suffix to restrict an item to one host, e.g.
     *
     *   sailpoint.api.Provisioner=DEBUG, sailpoint.connector=TRACE@iiq-app-02
     *
     * The REST layer uses this to list these alongside the UI-managed
     * overrides, so the page shows one combined list of everything that is on
     * rather than hiding half of it behind the settings dialog.
     */
    public static List<Map<String, String>> parsePermanent(String raw, List<String> errors) {
        List<Map<String, String>> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        for (String item : raw.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;

            String hostFilter = null;
            int at = s.lastIndexOf('@');
            if (at > -1) {
                hostFilter = s.substring(at + 1).trim();
                s = s.substring(0, at).trim();
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                errors.add("Permanent loggers: '" + item.trim() + "' is not logger=LEVEL");
                continue;
            }
            String logger = Log4jAgent.normalize(s.substring(0, eq));
            String level = s.substring(eq + 1).trim().toUpperCase(Locale.ROOT);
            if (logger == null) {
                errors.add("Permanent loggers: blank logger name in '" + item.trim() + "'");
                continue;
            }
            if (Log4jAgent.parseLevel(level) == null) {
                errors.add("Permanent loggers: unknown level '" + level + "' in '" + item.trim() + "'");
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("logger", Log4jAgent.display(logger));
            row.put("level", level);
            row.put("hosts", (hostFilter == null || hostFilter.isEmpty())
                    ? LoggerConfigStore.ALL_HOSTS : hostFilter);
            out.add(row);
        }
        return out;
    }

    /** The subset of the permanent loggers that applies to one host. */
    static Map<String, String> permanentDesiredFor(String raw, String thisHost, List<String> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> row : parsePermanent(raw, errors)) {
            String hosts = row.get("hosts");
            if (!LoggerConfigStore.ALL_HOSTS.equals(hosts) && !hosts.equalsIgnoreCase(thisHost)) {
                continue; // meant for a different host
            }
            out.put(Log4jAgent.normalize(row.get("logger")), row.get("level"));
        }
        return out;
    }
}
