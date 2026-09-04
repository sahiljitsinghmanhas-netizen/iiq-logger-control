package io.github.sahiljitsinghmanhas.loggermanager.core;

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
    /** user -> { matches, answeredAt, path, error }. */
    public static final String S_LOG_ANSWERS = "logAnswers";
    /** Keys inside one answer. */
    public static final String AN_MATCHES  = "matches";
    public static final String AN_ANSWERED = "answeredAt";
    public static final String AN_PATH     = "path";
    public static final String AN_ERROR    = "error";

    public static final String S_LOG_MATCHES = "logMatches";
    public static final String S_LOG_ANSWERED = "logAnsweredAt";
    public static final String S_LOG_PATH    = "logPath";
    /** Why this host could not answer, blank when it answered fine. */
    public static final String S_LOG_ERROR   = "logError";

    /**
     * Why this host's last sync tick did not finish, blank when it did.
     *
     * Deliberately separate from S_ERRORS, which records problems applying an
     * individual logger during a tick that otherwise completed. This one means
     * the tick itself died, so nothing else in the record was refreshed and
     * everything in it is as old as S_LAST_SYNC says.
     */
    public static final String S_TICK_ERROR  = "tickError";
    /** When that failure happened. Not lastSync - the sync did not happen. */
    public static final String S_TICK_ERROR_AT = "tickErrorAt";
    /** Loggers this plugin created here - the only ones cleanup may remove. */
    public static final String S_CREATED = "created";
    /** Every logger live in this host's JVM, with level and source. */
    public static final String S_LIVE = "liveLoggers";

    /** Set on the config object to ask every host to drop stranded loggers. */
    public static final String A_CLEAR_AT = "clearRuntimeAt";
    /** Optional: a single logger to remove, instead of sweeping our leftovers. */
    public static final String A_CLEAR_LOGGER = "clearRuntimeLogger";

    /** A cluster-wide log search: every host answers about its own file. */
    /**
     * Every live log request, keyed by the user who asked.
     *
     * There used to be one query for the whole deployment, which meant two
     * people searching overwrote each other and everybody saw whoever went
     * last. A search is a private act of reading - it changes nothing - so it
     * belongs to the person who ran it. Overrides stay shared, because turning
     * a logger on genuinely changes the JVM for everyone on it.
     *
     * Shape: user -> { text, mode, lines, host, at }.
     */
    public static final String A_LOG_QUERIES  = "logQueries";
    public static final String Q_TEXT  = "text";
    public static final String Q_MODE  = "mode";
    public static final String Q_LINES = "lines";
    public static final String Q_HOST  = "host";
    public static final String Q_AT    = "at";

    /**
     * How many people may have a search running at once.
     *
     * Measured on a 21-host cluster before this number was chosen: with twenty
     * concurrent searches the status records hold 4.5 MB between them, and
     * /state still answered in about a quarter of a second - because each
     * caller is sent only their own answers, so the payload stays flat at
     * ~31 KB however many people are looking. The cost that does grow is what
     * every host writes each tick, which is why there is a ceiling at all
     * rather than none.
     */
    public static final int DEFAULT_MAX_QUERIES = 50;

    public static final String A_LOG_QUERY    = "logQuery";
    public static final String A_LOG_QUERY_AT = "logQueryAt";
    /** "search" for matching lines, "tail" for the last N lines. */
    public static final String A_LOG_MODE     = "logQueryMode";
    public static final String A_LOG_LINES    = "logQueryLines";
    /** Which host should answer: a name, or blank for all of them. */
    public static final String A_LOG_HOST     = "logQueryHost";

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

    /**
     * Record that a sync tick failed, without pretending it succeeded.
     *
     * Deliberately does NOT touch lastSync. A failing host must keep going
     * stale - that is the signal that its record is old - and stamping the
     * clock here would hide exactly the fault this is reporting. All this adds
     * is the reason, so a stale host stops being a mystery.
     *
     * Writes as little as possible and swallows its own failure. It runs on
     * the path where something has already gone wrong, quite possibly the
     * database itself, and a diagnostic that can throw is worse than no
     * diagnostic.
     */
    public static void writeTickError(SailPointContext ctx, String host, String message) {
        try {
            Custom st = ctx.getObjectByName(Custom.class, statusName(host));
            if (st == null) return;   // never synced here; nothing to annotate
            if (st.getAttributes() == null) st.setAttributes(new Attributes<String, Object>());
            String m = message == null ? "" : message;
            if (m.length() > 500) m = m.substring(0, 500);
            st.put(S_TICK_ERROR, m);
            st.put(S_TICK_ERROR_AT, String.valueOf(System.currentTimeMillis()));
            ctx.saveObject(st);
            ctx.commitTransaction();
        } catch (Throwable ignored) {
            // Nothing useful left to do - the log already has the real failure.
        }
    }

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

    /** A search stops being answered after this, so hosts do not publish forever. */
    public static final long LOG_QUERY_TTL_MS = 900000L;   // 15 minutes

    /** True while hosts should be answering, whether searching or tailing. */
    /** Every query that has not aged out, keyed by user. */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, String>> activeQueries(SailPointContext ctx)
            throws GeneralException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return out;
        Object raw = cfg.get(A_LOG_QUERIES);
        if (!(raw instanceof Map)) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, String> q = new LinkedHashMap<>();
            for (Map.Entry<?, ?> f : ((Map<?, ?>) e.getValue()).entrySet()) {
                q.put(String.valueOf(f.getKey()), f.getValue() == null ? "" : String.valueOf(f.getValue()));
            }
            long at = asLong(q.get(Q_AT), 0L);
            if (at <= 0 || (now - at) > LOG_QUERY_TTL_MS) continue;
            out.put(String.valueOf(e.getKey()), q);
        }
        return out;
    }

    /**
     * Downloads are requests too, but they are not searches.
     *
     * A download used to reuse the one request slot a person has, which meant
     * pressing Download emptied the log viewer - the slot now said "download,
     * no text", so nothing matched and the output vanished until the file
     * arrived and the search was put back. It also meant the size of the file
     * and the number of lines on screen were the same field, so showing forty
     * lines downloaded forty lines.
     *
     * Both of those stop being possible if a download is simply a different
     * request. It is filed under the same person with this marker appended, so
     * it inherits the expiry, the answering and the per-user isolation that
     * already exist, and queryFor() - which looks the person up by their exact
     * name - never sees it. Nothing a download does can reach a search.
     */
    public static final String A_LOG_DOWNLOADS = "logDownloads";

    /** Per-host answers to download requests, keyed by user. */
    public static final String S_LOG_DL_ANSWERS = "logDownloadAnswers";

    /**
     * Ask one host for the end of its log. Passing a blank host clears it,
     * which is what the page does once the file has been saved - those
     * megabytes should not be carried on every poll afterwards.
     */
    @SuppressWarnings("unchecked")
    public static void setLogDownload(SailPointContext ctx, String user, String host)
            throws GeneralException {
        if (user == null) return;
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) {
            cfg = new Custom();
            cfg.setName(CONFIG_NAME);
        }
        Object raw = cfg.get(A_LOG_DOWNLOADS);
        Map<String, Object> all = (raw instanceof Map)
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<String, Object>();

        // Drop anyone else's stale request while we are here, so a person who
        // closed the tab mid-download does not leave a host reading its log
        // every tick forever.
        long cut = System.currentTimeMillis() - LOG_QUERY_TTL_MS;
        for (String k : new ArrayList<>(all.keySet())) {
            Object v = all.get(k);
            long at = (v instanceof Map)
                    ? asLong(String.valueOf(((Map<?, ?>) v).get(Q_AT)), 0L) : 0L;
            if (at <= cut) all.remove(k);
        }

        if (host == null || host.trim().isEmpty()) {
            all.remove(user);
        } else {
            Map<String, String> q = new LinkedHashMap<>();
            q.put(Q_TEXT, "");
            q.put(Q_MODE, "download");
            q.put(Q_LINES, "0");            // deliberately unused: bytes, not lines
            q.put(Q_HOST, host.trim());
            q.put(Q_AT, String.valueOf(System.currentTimeMillis()));
            all.put(user, q);
        }
        cfg.put(A_LOG_DOWNLOADS, all);
        ctx.saveObject(cfg);
        ctx.commitTransaction();
    }

    /** Every download request that has not aged out, keyed by user. */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, String>> activeDownloads(SailPointContext ctx)
            throws GeneralException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return out;
        Object raw = cfg.get(A_LOG_DOWNLOADS);
        if (!(raw instanceof Map)) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, String> q = new LinkedHashMap<>();
            for (Map.Entry<?, ?> f : ((Map<?, ?>) e.getValue()).entrySet()) {
                q.put(String.valueOf(f.getKey()), f.getValue() == null ? "" : String.valueOf(f.getValue()));
            }
            long at = asLong(q.get(Q_AT), 0L);
            if (at <= 0 || (now - at) > LOG_QUERY_TTL_MS) continue;
            out.put(String.valueOf(e.getKey()), q);
        }
        return out;
    }

    /** One user's live download request, or null. */
    public static Map<String, String> downloadFor(SailPointContext ctx, String user)
            throws GeneralException {
        if (user == null) return null;
        return activeDownloads(ctx).get(user);
    }

    /** One user's live query, or null. */
    public static Map<String, String> queryFor(SailPointContext ctx, String user)
            throws GeneralException {
        if (user == null) return null;
        return activeQueries(ctx).get(user);
    }

    /** Whether a given query wants an answer from this host. Blank means all. */
    public static boolean queryTargets(Map<String, String> q, String host) {
        if (q == null) return false;
        String want = q.get(Q_HOST);
        return want == null || want.trim().isEmpty() || want.trim().equals(host);
    }

    /**
     * Record, replace or clear one user's request. Blank text in search mode
     * clears it - "stop" and "I typed nothing" are the same intent.
     */
    @SuppressWarnings("unchecked")
    public static void setLogQuery(SailPointContext ctx, String user, String text,
                                   String mode, int lines, String host)
            throws GeneralException {
        if (user == null) return;
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) {
            cfg = new Custom();
            cfg.setName(CONFIG_NAME);
            cfg.setAttributes(new Attributes<String, Object>());
        }
        if (cfg.getAttributes() == null) cfg.setAttributes(new Attributes<String, Object>());

        Map<String, Object> all = new LinkedHashMap<>();
        Object raw = cfg.get(A_LOG_QUERIES);
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                all.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        long now = System.currentTimeMillis();
        // A download asks for bytes, not for text, so it is active with an empty
        // search box exactly as a tail is. Treating only "tail" that way meant a
        // download request was read as "stop", removed before any host saw it,
        // and the page then saved whatever the previous search had left behind.
        boolean tailing = "tail".equals(mode);
        boolean active = tailing || (text != null && !text.trim().isEmpty());

        if (!active) {
            all.remove(user);
        } else {
            // Drop anyone else's expired entry while we are here, so the map
            // cannot grow without bound, and refuse to start a new one past the
            // cap - each live query is a log read on every host every tick.
            long cut = now - LOG_QUERY_TTL_MS;
            for (String k : new ArrayList<>(all.keySet())) {
                Object v = all.get(k);
                long at = (v instanceof Map)
                        ? asLong(String.valueOf(((Map<?, ?>) v).get(Q_AT)), 0L) : 0L;
                if (at <= cut) all.remove(k);
            }
            int cap = PluginSettings.getInt(ctx, PluginSettings.S_MAX_SEARCHES,
                    DEFAULT_MAX_QUERIES);
            // Only searches are counted, and only searches are in this map:
            // downloads are kept separately. The limit exists because each live
            // search costs every host a scan of its log every tick, and letting
            // people saving files lock everyone else out of searching would be
            // the opposite of the point.
            if (cap > 0 && !all.containsKey(user) && all.size() >= cap) {
                throw new GeneralException(cap + " log searches are already running "
                        + "across this deployment. Wait for one to finish, or stop yours and retry.");
            }
            Map<String, String> q = new LinkedHashMap<>();
            q.put(Q_TEXT, text == null ? "" : text.trim());
            q.put(Q_MODE, tailing ? "tail" : "search");
            q.put(Q_LINES, String.valueOf(lines <= 0 ? 40 : lines));
            q.put(Q_HOST, host == null ? "" : host.trim());
            q.put(Q_AT, String.valueOf(now));
            all.put(user, q);
        }

        cfg.put(A_LOG_QUERIES, all);
        cfg.put(A_UPDATED, String.valueOf(now));
        cfg.put(A_UPDATED_BY, user);
        ctx.saveObject(cfg);
        ctx.commitTransaction();
    }

    public static String logHost(SailPointContext ctx) throws GeneralException {
        Custom cfg = ctx.getObjectByName(Custom.class, CONFIG_NAME);
        if (cfg == null) return "";
        String h = cfg.getString(A_LOG_HOST);
        return h == null ? "" : h.trim();
    }

    /** True if this host should answer the current request. */
    /** Ask every host to look in its own log - for text, or just for the last N lines. */

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
                                   long lastClear,
                                   Map<String, Map<String, Object>> logAnswers,
                                   Map<String, Map<String, Object>> logDownloadAnswers)
            throws GeneralException {
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
        // This tick got here, so whatever the last one failed at is history.
        st.put(S_TICK_ERROR, "");
        st.put(S_TICK_ERROR_AT, "0");
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
        // One answer per user who has a live query, rather than one answer for
        // whoever searched most recently. Written whole each tick, so a query
        // that has ended takes its answer with it.
        st.put(S_LOG_ANSWERS, logAnswers == null
                ? new LinkedHashMap<String, Map<String, Object>>()
                : new LinkedHashMap<>(logAnswers));
        st.put(S_LOG_DL_ANSWERS, logDownloadAnswers == null
                ? new LinkedHashMap<String, Map<String, Object>>()
                : new LinkedHashMap<>(logDownloadAnswers));
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
