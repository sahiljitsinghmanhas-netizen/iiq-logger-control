package com.example.turnonloggers.core;

import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.Custom;
import sailpoint.object.Filter;
import sailpoint.object.QueryOptions;
import sailpoint.tools.GeneralException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The shared state that makes this work across hosts: the IIQ database.
 *
 * There are two kinds of object, and each has exactly one writer, which is
 * what keeps a multi-host cluster from fighting over rows:
 *
 *   Custom "TurnOnLoggers Configuration"
 *       The desired state - which loggers, at which level, on which hosts,
 *       until when. Written only by the REST layer (i.e. by a human in the
 *       UI). Read by every host.
 *
 *   Custom "TurnOnLoggers Status &lt;hostname&gt;"
 *       What one host actually did, plus that host's OS/JVM/log-file facts.
 *       Written only by that host's own sync service. Read by the UI.
 *
 * No host ever writes another host's object and no host writes the config, so
 * there is no lock contention and no last-writer-wins data loss regardless of
 * cluster size.
 */
public final class LoggerConfigStore {

    public static final String CONFIG_NAME  = "TurnOnLoggers Configuration";
    public static final String STATUS_PREFIX = "TurnOnLoggers Status ";

    // Config attribute keys
    public static final String A_ENTRIES   = "entries";
    public static final String A_REVISION  = "revision";
    public static final String A_UPDATED   = "updated";
    public static final String A_UPDATED_BY = "updatedBy";

    // Entry keys (all values are Strings so the Custom XML stays trivially
    // serialisable across IIQ versions)
    public static final String E_ID        = "id";
    public static final String E_LOGGER    = "logger";
    public static final String E_LEVEL     = "level";
    public static final String E_HOSTS     = "hosts";      // "*" or comma-separated host names
    public static final String E_EXPIRES   = "expires";    // epoch millis, "0" = never
    public static final String E_CREATED   = "created";
    public static final String E_CREATED_BY = "createdBy";
    public static final String E_NOTE      = "note";

    // Status attribute keys
    public static final String S_HOST      = "host";
    public static final String S_REVISION  = "revision";
    public static final String S_LAST_SYNC = "lastSync";
    public static final String S_TRIGGER   = "trigger";
    public static final String S_APPLIED   = "applied";    // logger -> effective level
    public static final String S_ERRORS    = "errors";
    public static final String S_FACTS     = "facts";
    public static final String S_FILE_LOGGERS = "fileLoggers";
    /** Loggers live on this host that are neither in its file nor managed by us. */
    public static final String S_RUNTIME_LOGGERS = "runtimeLoggers";
    /** Durable record of what this host owns, so a plugin reinstall cannot strand loggers. */
    public static final String S_OWNED = "owned";
    public static final String S_LAST_CLEAR = "lastClearAt";
    /** "false" when this host's log4j2 config could not be read, so sources are unknown. */
    public static final String S_FILE_PARSED = "fileParsed";
    /** Loggers this plugin created here - the only ones cleanup may remove. */
    public static final String S_CREATED = "created";
    /** Every logger live in this host's JVM, with level and source. */
    public static final String S_LIVE = "liveLoggers";

    /** Set on the config object to ask every host to drop stranded loggers. */
    public static final String A_CLEAR_AT = "clearRuntimeAt";
    /** Optional: a single logger to remove, instead of sweeping our leftovers. */
    public static final String A_CLEAR_LOGGER = "clearRuntimeLogger";

    public static final String ALL_HOSTS = "*";

    private LoggerConfigStore() {
    }

    // ------------------------------------------------------------------
    // configuration (desired state)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> loadEntries(SailPointContext ctx) throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return new ArrayList<>();
        Object raw = cfg.get(A_ENTRIES);
        List<Map<String, String>> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o instanceof Map) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                        row.put(String.valueOf(e.getKey()),
                                e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    public static int revision(SailPointContext ctx) throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return 0;
        return asInt(cfg.getString(A_REVISION), 0);
    }

    /**
     * Replace the entry list wholesale and bump the revision. Callers pass the
     * full list because the UI always edits against a freshly-loaded copy;
     * this keeps the write a single atomic commit.
     */
    public static int saveEntries(SailPointContext ctx, List<Map<String, String>> entries, String user)
            throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) {
            cfg = new Custom();
            cfg.setName(CONFIG_NAME);
            cfg.setAttributes(new Attributes<String, Object>());
        }
        if (cfg.getAttributes() == null) {
            cfg.setAttributes(new Attributes<String, Object>());
        }
        int rev = asInt(cfg.getString(A_REVISION), 0) + 1;
        cfg.put(A_ENTRIES, new ArrayList<Object>(prune(entries, System.currentTimeMillis())));
        cfg.put(A_REVISION, String.valueOf(rev));
        cfg.put(A_UPDATED, String.valueOf(System.currentTimeMillis()));
        cfg.put(A_UPDATED_BY, user == null ? "" : user);
        ctx.saveObject(cfg);
        ctx.commitTransaction();
        return rev;
    }

    /** How long an expired entry stays visible before it is swept away. */
    private static final long KEEP_EXPIRED_MS = 3600000L; // 1 hour

    /**
     * Drops entries that expired long enough ago that nobody is still looking
     * at them. Recently-expired ones are kept on purpose - "sailpoint.connector
     * turned itself off 4 minutes ago" is useful information, and without it a
     * logger would silently vanish from the page.
     *
     * Called from saveEntries so it happens on every mutation and the list
     * cannot grow without bound.
     */
    static List<Map<String, String>> prune(List<Map<String, String>> entries, long now) {
        List<Map<String, String>> keep = new ArrayList<>();
        for (Map<String, String> e : entries) {
            long exp = asLong(e.get(E_EXPIRES), 0L);
            if (exp > 0 && exp < (now - KEEP_EXPIRED_MS)) continue;
            keep.add(e);
        }
        return keep;
    }

    public static Map<String, String> newEntry(String logger, String level, String hosts,
                                               long expires, String user, String note) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put(E_ID, UUID.randomUUID().toString());
        e.put(E_LOGGER, logger);
        e.put(E_LEVEL, level);
        e.put(E_HOSTS, (hosts == null || hosts.trim().isEmpty()) ? ALL_HOSTS : hosts.trim());
        e.put(E_EXPIRES, String.valueOf(expires));
        e.put(E_CREATED, String.valueOf(System.currentTimeMillis()));
        e.put(E_CREATED_BY, user == null ? "" : user);
        e.put(E_NOTE, note == null ? "" : note);
        return e;
    }

    public static boolean isExpired(Map<String, String> entry, long now) {
        long exp = asLong(entry.get(E_EXPIRES), 0L);
        return exp > 0 && exp <= now;
    }

    public static boolean targetsHost(Map<String, String> entry, String host) {
        return hostMatches(entry.get(E_HOSTS), host);
    }

    /** @param hostsSpec "*", blank, or a comma-separated list of host names. */
    public static boolean hostMatches(String hostsSpec, String host) {
        if (hostsSpec == null || hostsSpec.trim().isEmpty() || ALL_HOSTS.equals(hostsSpec.trim())) {
            return true;
        }
        for (String h : hostsSpec.split(",")) {
            if (h.trim().equalsIgnoreCase(host)) return true;
        }
        return false;
    }

    /**
     * Collapse the entry list into the level map one host should be running.
     *
     * Conflict rule when two live entries name the same logger for the same
     * host: a host-specific entry beats a wildcard entry, and among equals the
     * most recently created one wins. Deterministic, and it matches the mental
     * model of "I just pinned this host, so my pin should stick".
     */
    public static Map<String, String> desiredFor(List<Map<String, String>> entries, String host, long now) {
        List<Map<String, String>> live = new ArrayList<>();
        for (Map<String, String> e : entries) {
            if (isExpired(e, now)) continue;
            if (!targetsHost(e, host)) continue;
            live.add(e);
        }
        Collections.sort(live, new Comparator<Map<String, String>>() {
            @Override
            public int compare(Map<String, String> a, Map<String, String> b) {
                int sa = ALL_HOSTS.equals(String.valueOf(a.get(E_HOSTS)).trim()) ? 0 : 1;
                int sb = ALL_HOSTS.equals(String.valueOf(b.get(E_HOSTS)).trim()) ? 0 : 1;
                if (sa != sb) return sa - sb;
                return Long.compare(asLong(a.get(E_CREATED), 0L), asLong(b.get(E_CREATED), 0L));
            }
        });

        Map<String, String> desired = new LinkedHashMap<>();
        for (Map<String, String> e : live) {
            String name = Log4jAgent.normalize(e.get(E_LOGGER));
            if (name == null) continue;
            desired.put(name, String.valueOf(e.get(E_LEVEL)).toUpperCase(Locale.ROOT));
        }
        return desired;
    }

    // ------------------------------------------------------------------
    // per-host status (observed state)
    // ------------------------------------------------------------------

    public static String statusName(String host) {
        return STATUS_PREFIX + host;
    }

    /** Ownership this host recorded on a previous run. See Log4jAgent.adopt(). */
    @SuppressWarnings("unchecked")
    public static Map<String, String> readOwned(SailPointContext ctx, String host) throws GeneralException {
        Map<String, String> out = new LinkedHashMap<>();
        Custom st = ctx.getObjectByName(Custom.class, statusName(host));
        if (st == null) return out;
        Object raw = st.get(S_OWNED);
        if (raw instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) raw).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    /** Loggers this plugin previously created on this host. */
    @SuppressWarnings("unchecked")
    public static List<String> readCreated(SailPointContext ctx, String host) throws GeneralException {
        List<String> out = new ArrayList<>();
        Custom st = ctx.getObjectByName(Custom.class, statusName(host));
        if (st == null) return out;
        Object raw = st.get(S_CREATED);
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    public static long readLastClear(SailPointContext ctx, String host) throws GeneralException {
        Custom st = ctx.getObjectByName(Custom.class, statusName(host));
        if (st == null) return 0L;
        return asLong(st.getString(S_LAST_CLEAR), 0L);
    }

    public static long clearRequestedAt(SailPointContext ctx) throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return 0L;
        return asLong(cfg.getString(A_CLEAR_AT), 0L);
    }

    /** Ask every host to drop loggers stranded by an earlier plugin instance. */
    public static String clearRequestedLogger(SailPointContext ctx) throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        return cfg == null ? "" : String.valueOf(cfg.getString(A_CLEAR_LOGGER) == null ? "" : cfg.getString(A_CLEAR_LOGGER));
    }

    public static long requestRuntimeCleanup(SailPointContext ctx, String user, String logger)
            throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) {
            cfg = new Custom();
            cfg.setName(CONFIG_NAME);
            cfg.setAttributes(new Attributes<String, Object>());
        }
        if (cfg.getAttributes() == null) cfg.setAttributes(new Attributes<String, Object>());
        long now = System.currentTimeMillis();
        // A cleanup is a change every host has to act on, so it bumps the
        // revision like any other. Without this the Hosts table kept reporting
        // "in sync" while hosts still had the logger, and nothing showed the
        // request travelling.
        cfg.put(A_REVISION, String.valueOf(asInt(cfg.getString(A_REVISION), 0) + 1));
        cfg.put(A_CLEAR_AT, String.valueOf(now));
        cfg.put(A_CLEAR_LOGGER, logger == null ? "" : logger.trim());
        cfg.put(A_UPDATED, String.valueOf(now));
        cfg.put(A_UPDATED_BY, user == null ? "" : user);
        ctx.saveObject(cfg);
        ctx.commitTransaction();
        return now;
    }

    public static void writeStatus(SailPointContext ctx,
                                   String host,
                                   int revision,
                                   String trigger,
                                   Map<String, String> applied,
                                   List<String> errors,
                                   long lastClear) throws GeneralException {
        String name = statusName(host);
        Custom st = ctx.getObjectByName(Custom.class, name);
        if (st == null) {
            st = new Custom();
            st.setName(name);
            st.setAttributes(new Attributes<String, Object>());
        }
        if (st.getAttributes() == null) {
            st.setAttributes(new Attributes<String, Object>());
        }
        st.put(S_HOST, host);
        st.put(S_REVISION, String.valueOf(revision));
        st.put(S_LAST_SYNC, String.valueOf(System.currentTimeMillis()));
        st.put(S_TRIGGER, trigger == null ? "" : trigger);
        st.put(S_APPLIED, new LinkedHashMap<String, String>(applied));
        st.put(S_ERRORS, new ArrayList<String>(errors));
        st.put(S_FACTS, new LinkedHashMap<String, String>(HostFacts.collect()));

        // What this host's log4j2.properties defines, as distinct from what we
        // set. Reported per host because the file can differ between hosts -
        // that is exactly the case a single shared UI cannot otherwise reveal.
        Map<String, String> fileLoggers = new LinkedHashMap<>();
        Map<String, String> runtimeLoggers = new LinkedHashMap<>();
        boolean fileParsed = true;
        List<Object> live = new ArrayList<>();
        for (Map<String, String> row : Log4jAgent.configuredLoggers()) {
            String source = row.get("source");
            if ("unknown".equals(source)) fileParsed = false;
            live.add(new LinkedHashMap<String, String>(row));
            if ("file".equals(source) || "unknown".equals(source)) {
                fileLoggers.put(row.get("logger"), row.get("level"));
            } else if ("leftover".equals(source)) {
                runtimeLoggers.put(row.get("logger"), row.get("level"));
            }
        }
        st.put(S_FILE_LOGGERS, fileLoggers);
        st.put(S_RUNTIME_LOGGERS, runtimeLoggers);
        st.put(S_LIVE, live);
        st.put(S_CREATED, new ArrayList<String>(Log4jAgent.createdSnapshot()));
        st.put(S_OWNED, new LinkedHashMap<String, String>(Log4jAgent.ownedSnapshot()));
        st.put(S_LAST_CLEAR, String.valueOf(lastClear));
        st.put(S_FILE_PARSED, String.valueOf(fileParsed));
        ctx.saveObject(st);
        ctx.commitTransaction();
    }

    /** host name -> that host's status attributes. */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> allStatuses(SailPointContext ctx) throws GeneralException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        QueryOptions qo = new QueryOptions();
        qo.add(Filter.like("name", STATUS_PREFIX, Filter.MatchMode.START));
        List<Custom> objs = ctx.getObjects(Custom.class, qo);
        if (objs == null) return out;
        for (Custom c : objs) {
            Map<String, Object> m = new LinkedHashMap<>();
            Attributes<String, Object> attrs = c.getAttributes();
            if (attrs != null) m.putAll(attrs.getMap());
            String host = m.get(S_HOST) == null
                    ? c.getName().substring(STATUS_PREFIX.length())
                    : String.valueOf(m.get(S_HOST));
            out.put(host, m);
        }
        return out;
    }

    /** Delete a host's status object - used when an admin retires a host. */
    public static boolean deleteStatus(SailPointContext ctx, String host) throws GeneralException {
        Custom st = ctx.getObjectByName(Custom.class, statusName(host));
        if (st == null) return false;
        ctx.removeObject(st);
        ctx.commitTransaction();
        return true;
    }

    // ------------------------------------------------------------------

    public static int asInt(String s, int dflt) {
        try {
            return (s == null || s.trim().isEmpty()) ? dflt : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public static long asLong(String s, long dflt) {
        try {
            return (s == null || s.trim().isEmpty()) ? dflt : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
