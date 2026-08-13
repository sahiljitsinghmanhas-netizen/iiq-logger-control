package com.example.turnonloggers.rest;

import com.example.turnonloggers.core.AuditWriter;
import com.example.turnonloggers.core.HostFacts;
import com.example.turnonloggers.core.Log4jAgent;
import com.example.turnonloggers.core.LoggerConfigStore;
import com.example.turnonloggers.core.LoggerSync;
import com.example.turnonloggers.core.PluginSettings;
import org.apache.log4j.Logger;
import sailpoint.api.SailPointContext;
import sailpoint.object.Capability;
import sailpoint.object.Identity;
import sailpoint.object.Server;
import sailpoint.rest.plugin.AllowAll;
import sailpoint.rest.plugin.BasePluginResource;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REST surface for the logger control page.
 *
 * URL shape is /identityiq/plugin/rest/TurnOnLoggers/... - the @Path value has
 * to equal the plugin name exactly, case included.
 *
 * Every mutating call does the same three things: validate, persist the
 * desired state to the database, then immediately reconcile the local JVM so
 * the caller sees the result straight away. The other hosts in the cluster
 * pick the change up on their next sync tick.
 */
@AllowAll
@Path("TurnOnLoggers")
public class LoggerControlResource extends BasePluginResource {

    private static final Logger LOG = Logger.getLogger(LoggerControlResource.class);

    public static final String PLUGIN_NAME = "TurnOnLoggers";

    /** Logger names are java package/class names, plus the "root" alias. */
    private static final Pattern LOGGER_NAME = Pattern.compile("^[A-Za-z0-9_.$#\\-]{1,255}$");

    /** A host is considered stale if it has not checked in for this long. */
    private static final long STALE_AFTER_MS = 150000L; // 2.5 x the default 60s interval

    private static final int MAX_ENTRIES = 200;

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    // ==================================================================
    // read
    // ==================================================================

    @GET
    @Path("state")
    @Produces(MediaType.APPLICATION_JSON)
    public Response state() {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] state failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ==================================================================
    // write
    // ==================================================================

    @POST
    @Path("entries")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addEntry(Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();

            String logger = str(body, "logger");
            String level = str(body, "level");
            String hosts = hostsOf(body);
            String note = str(body, "note");

            String bad = validate(ctx, logger, level);
            if (bad != null) return error(Response.Status.BAD_REQUEST, bad);

            long expires = resolveExpiry(ctx, body, level);
            if (expires < 0) {
                return error(Response.Status.BAD_REQUEST,
                        level.toUpperCase(Locale.ROOT) + " produces output, so it has to expire; "
                                + "set a TTL up to "
                                + PluginSettings.getInt(ctx, PluginSettings.S_MAX_TTL, 1440)
                                + " minutes. Only OFF may be permanent - a logger you switch off "
                                + "cannot flood a disk, so it is allowed to stay off.");
            }

            List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
            // Re-adding a logger for the same target replaces the old row
            // rather than stacking a second one nobody can tell apart.
            String normalized = Log4jAgent.normalize(logger);
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map<String, String> e = entries.get(i);
                if (equalsIgnoreCase(Log4jAgent.normalize(e.get(LoggerConfigStore.E_LOGGER)), normalized)
                        && equalsIgnoreCase(e.get(LoggerConfigStore.E_HOSTS), hosts)) {
                    entries.remove(i);
                }
            }
            if (entries.size() >= MAX_ENTRIES) {
                return error(Response.Status.BAD_REQUEST,
                        "Too many active overrides (" + MAX_ENTRIES + " max). Remove some first.");
            }
            entries.add(LoggerConfigStore.newEntry(
                    Log4jAgent.display(normalized), level.toUpperCase(Locale.ROOT),
                    hosts, expires, user.getName(), note));

            int rev = LoggerConfigStore.saveEntries(ctx, entries, user.getName());

            AuditWriter.log(ctx, user.getName(),
                    "OFF".equalsIgnoreCase(level) ? "silenced" : "enabled",
                    Log4jAgent.display(normalized), level.toUpperCase(Locale.ROOT),
                    hosts, expires, note);
            LoggerSync.run(ctx, "rest:add");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] addEntry failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @PUT
    @Path("entries/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateEntry(@PathParam("id") String id, Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
            Map<String, String> target = null;
            for (Map<String, String> e : entries) {
                if (id.equals(e.get(LoggerConfigStore.E_ID))) {
                    target = e;
                    break;
                }
            }
            if (target == null) return error(Response.Status.NOT_FOUND, "No such override: " + id);

            String level = str(body, "level");
            if (level != null && !level.trim().isEmpty()) {
                if (Log4jAgent.parseLevel(level) == null) {
                    return error(Response.Status.BAD_REQUEST, "Unknown level: " + level);
                }
                target.put(LoggerConfigStore.E_LEVEL, level.toUpperCase(Locale.ROOT));
            }
            if (body != null && body.containsKey("ttlMinutes")) {
                long expires = resolveExpiry(ctx, body, target.get(LoggerConfigStore.E_LEVEL));
                if (expires < 0) {
                    return error(Response.Status.BAD_REQUEST,
                            target.get(LoggerConfigStore.E_LEVEL)
                                    + " produces output, so it has to expire. Only OFF may be permanent.");
                }
                target.put(LoggerConfigStore.E_EXPIRES, String.valueOf(expires));
            }
            if (body != null && (body.containsKey("hosts") || body.containsKey("host"))) {
                target.put(LoggerConfigStore.E_HOSTS, hostsOf(body));
            }

            int rev = LoggerConfigStore.saveEntries(ctx, entries, user.getName());
            AuditWriter.log(ctx, user.getName(), "updated",
                    String.valueOf(target.get(LoggerConfigStore.E_LOGGER)),
                    String.valueOf(target.get(LoggerConfigStore.E_LEVEL)),
                    String.valueOf(target.get(LoggerConfigStore.E_HOSTS)),
                    LoggerConfigStore.asLong(target.get(LoggerConfigStore.E_EXPIRES), 0L),
                    String.valueOf(target.get(LoggerConfigStore.E_NOTE)));
            LoggerSync.run(ctx, "rest:update");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] updateEntry failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @DELETE
    @Path("entries/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteEntry(@PathParam("id") String id) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
            Map<String, String> gone = null;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (id.equals(entries.get(i).get(LoggerConfigStore.E_ID))) {
                    gone = entries.remove(i);
                }
            }
            if (gone == null) return error(Response.Status.NOT_FOUND, "No such override: " + id);

            int rev = LoggerConfigStore.saveEntries(ctx, entries, user.getName());
            AuditWriter.log(ctx, user.getName(), "turned off",
                    String.valueOf(gone.get(LoggerConfigStore.E_LOGGER)),
                    String.valueOf(gone.get(LoggerConfigStore.E_LEVEL)),
                    String.valueOf(gone.get(LoggerConfigStore.E_HOSTS)), 0L,
                    String.valueOf(gone.get(LoggerConfigStore.E_NOTE)));
            LoggerSync.run(ctx, "rest:delete");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] deleteEntry failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /** Panic button: drop every override everywhere. */
    @DELETE
    @Path("entries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearAll() {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            int rev = LoggerConfigStore.saveEntries(ctx, new ArrayList<Map<String, String>>(), user.getName());
            AuditWriter.log(ctx, user.getName(), "turned everything off",
                    "(all overrides)", null, "*", 0L, null);
            LoggerSync.run(ctx, "rest:clear");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] clearAll failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /** Force this host to reconcile now, without changing desired state. */
    @POST
    @Path("sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncNow() {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            LoggerSync.run(ctx, "rest:sync");
            AuditWriter.log(ctx, user.getName(), "synced", HostFacts.hostName(),
                    null, HostFacts.hostName(), 0L, null);
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] syncNow failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /**
     * Drop loggers stranded in a host's live configuration by an earlier
     * instance of this plugin - ones that are neither in that host's
     * log4j2.properties nor currently managed here.
     *
     * Cluster-wide: it stamps a request on the config object and every host
     * acts on it during its next sync, the same way level changes propagate.
     */
    @POST
    @Path("cleanup")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cleanupRuntime(Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            String target = str(body, "logger");
            LoggerConfigStore.requestRuntimeCleanup(ctx, user.getName(), target);
            AuditWriter.log(ctx, user.getName(), "removed from the live configuration",
                    (target == null || target.trim().isEmpty()) ? "(loggers left over)" : target,
                    null, "*", 0L, null);
            LoggerSync.run(ctx, "rest:cleanup");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] cleanupRuntime failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /** Forget a decommissioned host's status row so it stops showing up. */
    @DELETE
    @Path("hosts/{host}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forgetHost(@PathParam("host") String host) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            if (HostFacts.hostName().equalsIgnoreCase(host)) {
                return error(Response.Status.BAD_REQUEST, "Refusing to forget the host serving this request.");
            }
            LoggerConfigStore.deleteStatus(getContext(), host);
            AuditWriter.log(getContext(), user.getName(), "forgot host", host, null, host, 0L, null);
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] forgetHost failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ==================================================================
    // state assembly
    // ==================================================================

    private Map<String, Object> buildState(Identity user) throws Exception {
        SailPointContext ctx = getContext();
        long now = System.currentTimeMillis();
        String thisHost = HostFacts.hostName();

        List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
        int revision = LoggerConfigStore.revision(ctx);
        Map<String, Map<String, Object>> statuses = LoggerConfigStore.allStatuses(ctx);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thisHost", thisHost);
        out.put("thisHostFacts", HostFacts.collect());
        out.put("serverTime", String.valueOf(now));
        out.put("revision", revision);
        out.put("enabled", PluginSettings.getBool(ctx, PluginSettings.S_ENABLED, true));
        out.put("allowRootLogger", PluginSettings.getBool(ctx, PluginSettings.S_ALLOW_ROOT, false));
        out.put("defaultTtlMinutes", PluginSettings.getInt(ctx, PluginSettings.S_DEFAULT_TTL, 60));
        out.put("maxTtlMinutes", PluginSettings.getInt(ctx, PluginSettings.S_MAX_TTL, 1440));
        String permanentRaw = PluginSettings.getString(ctx, PluginSettings.S_PERMANENT, "");
        List<String> permanentErrors = new ArrayList<>();
        List<Map<String, String>> permanent = LoggerSync.parsePermanent(permanentRaw, permanentErrors);
        out.put("permanentLoggers", permanentRaw);
        out.put("permanentErrors", permanentErrors);
        out.put("levels", Log4jAgent.LEVELS);
        List<String> quieting = new ArrayList<>();
        for (String l : Log4jAgent.LEVELS) {
            if (Log4jAgent.isQuieting(l)) quieting.add(l);
        }
        out.put("quietingLevels", quieting);
        out.put("user", user.getName());
        out.put("log4jAvailable", Log4jAgent.available());
        out.put("pluginVersion", PluginSettings.getVersion(ctx));
        out.put("auditAction", AuditWriter.ACTION);
        try {
            out.put("clearRequestedAt", String.valueOf(LoggerConfigStore.clearRequestedAt(ctx)));
            out.put("clearRequestedLogger", LoggerConfigStore.clearRequestedLogger(ctx));
        } catch (Throwable t) {
            out.put("clearRequestedAt", "0");
            out.put("clearRequestedLogger", "");
        }
        out.put("author", "Sahiljit Singh Manhas");
        out.put("projectUrl", "https://github.com/sahiljitsinghmanhas-netizen/iiq-logger-control");

        // --- entries, decorated for display -------------------------------
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> e : entries) {
            Map<String, Object> row = new LinkedHashMap<>(e);
            boolean expired = LoggerConfigStore.isExpired(e, now);
            row.put("expired", expired);
            long exp = LoggerConfigStore.asLong(e.get(LoggerConfigStore.E_EXPIRES), 0L);
            row.put("remainingMs", String.valueOf(exp == 0 ? -1 : Math.max(0, exp - now)));
            row.put("source", "ui");
            row.put("permanent", false);
            addLiveOn(row,
                    Log4jAgent.display(Log4jAgent.normalize(e.get(LoggerConfigStore.E_LOGGER))),
                    String.valueOf(e.get(LoggerConfigStore.E_LEVEL)),
                    e.get(LoggerConfigStore.E_HOSTS), !expired, statuses);
            rows.add(row);
        }

        // Loggers enabled from the plugin's settings page are shown in the
        // same table, marked read-only. Splitting them into a second list
        // would mean two places to look for "what is currently on", which is
        // exactly the confusion the single table avoids.
        for (Map<String, String> p : permanent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(LoggerConfigStore.E_ID, "");
            row.put(LoggerConfigStore.E_LOGGER, p.get("logger"));
            row.put(LoggerConfigStore.E_LEVEL, p.get("level"));
            row.put(LoggerConfigStore.E_HOSTS, p.get("hosts"));
            row.put(LoggerConfigStore.E_CREATED_BY, "plugin settings");
            row.put(LoggerConfigStore.E_NOTE, "");
            row.put("expired", false);
            row.put("remainingMs", "-1");
            row.put("source", "settings");
            row.put("permanent", true);
            addLiveOn(row, p.get("logger"), p.get("level"), p.get("hosts"), true, statuses);
            rows.add(row);
        }
        out.put("entries", rows);

        // --- hosts --------------------------------------------------------
        out.put("hosts", buildHosts(ctx, statuses, revision, now, thisHost));

        // --- live view of this host's log4j2 runtime ----------------------
        Set<String> interesting = new LinkedHashSet<>();
        for (Map<String, String> e : entries) {
            String n = Log4jAgent.normalize(e.get(LoggerConfigStore.E_LOGGER));
            if (n != null) interesting.add(n);
        }
        for (Map<String, String> p : permanent) {
            String n = Log4jAgent.normalize(p.get("logger"));
            if (n != null) interesting.add(n);
        }
        out.put("localLoggers", Log4jAgent.inspect(interesting));

        out.put("catalog", CATALOG);
        return out;
    }

    /**
     * Decorates a row with which hosts have confirmed this logger at this
     * level, and which are still catching up. This is the "did it actually
     * land" column, and it reads from what each host reported rather than
     * from what we asked for.
     */
    private void addLiveOn(Map<String, Object> row,
                           String loggerDisplay,
                           String level,
                           String hostsSpec,
                           boolean active,
                           Map<String, Map<String, Object>> statuses) {
        List<String> confirmed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> se : statuses.entrySet()) {
            if (!LoggerConfigStore.hostMatches(hostsSpec, se.getKey())) continue;
            Object appliedObj = se.getValue().get(LoggerConfigStore.S_APPLIED);
            String actual = null;
            if (appliedObj instanceof Map) {
                Object v = ((Map<?, ?>) appliedObj).get(loggerDisplay);
                actual = v == null ? null : String.valueOf(v);
            }
            if (active && actual != null && actual.equalsIgnoreCase(level)) {
                confirmed.add(se.getKey());
            } else {
                pending.add(se.getKey());
            }
        }
        row.put("confirmedOn", confirmed);
        row.put("pendingOn", pending);
    }

    private List<Map<String, Object>> buildHosts(SailPointContext ctx,
                                                 Map<String, Map<String, Object>> statuses,
                                                 int revision,
                                                 long now,
                                                 String thisHost) {
        // Union of three sources so a host shows up whether or not it has
        // heartbeated, and whether or not it has ever run the sync service.
        Map<String, Map<String, Object>> hosts = new LinkedHashMap<>();

        try {
            List<Server> servers = ctx.getObjects(Server.class);
            if (servers != null) {
                for (Server s : servers) {
                    Map<String, Object> h = hostRow(hosts, s.getName());
                    h.put("knownToIIQ", true);
                    h.put("inactive", s.isInactive());
                    h.put("heartbeat", s.getHeartbeat() == null
                            ? "" : String.valueOf(s.getHeartbeat().getTime()));
                }
            }
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] could not list Server objects: " + t);
        }

        for (Map.Entry<String, Map<String, Object>> e : statuses.entrySet()) {
            Map<String, Object> h = hostRow(hosts, e.getKey());
            Map<String, Object> st = e.getValue();
            long lastSync = LoggerConfigStore.asLong(
                    String.valueOf(st.get(LoggerConfigStore.S_LAST_SYNC)), 0L);
            int hostRev = LoggerConfigStore.asInt(
                    String.valueOf(st.get(LoggerConfigStore.S_REVISION)), -1);
            h.put("lastSync", String.valueOf(lastSync));
            h.put("revision", hostRev);
            h.put("trigger", String.valueOf(st.get(LoggerConfigStore.S_TRIGGER)));
            h.put("applied", st.get(LoggerConfigStore.S_APPLIED));
            h.put("errors", st.get(LoggerConfigStore.S_ERRORS));
            h.put("facts", st.get(LoggerConfigStore.S_FACTS));
            h.put("fileLoggers", st.get(LoggerConfigStore.S_FILE_LOGGERS));
            h.put("runtimeLoggers", st.get(LoggerConfigStore.S_RUNTIME_LOGGERS));
            h.put("liveLoggers", st.get(LoggerConfigStore.S_LIVE));
            h.put("lastClear", String.valueOf(
                    LoggerConfigStore.asLong(String.valueOf(st.get(LoggerConfigStore.S_LAST_CLEAR)), 0L)));
            h.put("fileParsed", !"false".equals(String.valueOf(st.get(LoggerConfigStore.S_FILE_PARSED))));
            h.put("reporting", true);
            h.put("stale", lastSync > 0 && (now - lastSync) > STALE_AFTER_MS);
            h.put("inSync", hostRev == revision && (now - lastSync) <= STALE_AFTER_MS);
        }

        Map<String, Object> me = hostRow(hosts, thisHost);
        me.put("isThisHost", true);
        if (!Boolean.TRUE.equals(me.get("reporting"))) {
            // Service has not ticked here yet - show what we know regardless.
            me.put("facts", HostFacts.collect());
        }

        return new ArrayList<>(hosts.values());
    }

    private Map<String, Object> hostRow(Map<String, Map<String, Object>> hosts, String name) {
        Map<String, Object> h = hosts.get(name);
        if (h == null) {
            h = new LinkedHashMap<>();
            h.put("name", name);
            h.put("knownToIIQ", false);
            h.put("reporting", false);
            h.put("inSync", false);
            h.put("stale", false);
            h.put("revision", -1);
            h.put("lastSync", "0");
            hosts.put(name, h);
        }
        return h;
    }

    // ==================================================================
    // validation / helpers
    // ==================================================================

    private String validate(SailPointContext ctx, String logger, String level) {
        if (logger == null || logger.trim().isEmpty()) {
            return "Logger name is required.";
        }
        String normalized = Log4jAgent.normalize(logger);
        if (Log4jAgent.isRoot(normalized)
                && !PluginSettings.getBool(ctx, PluginSettings.S_ALLOW_ROOT, false)) {
            return "The root logger is blocked. Turn on 'Allow root logger' in the plugin settings "
                    + "if you really want to raise the level for every logger on every host.";
        }
        // Easy mistake: the "Permanent loggers" setting takes logger=LEVEL, so
        // people paste that whole form in here. Say so, rather than leaving
        // them to spot the stray '=' in a generic name-format complaint.
        if (normalized.indexOf('=') > -1) {
            return "Enter the logger name on its own and pick the level from the Level list. "
                    + "The logger=LEVEL form is only used in the 'Permanent loggers' plugin setting.";
        }
        if (!Log4jAgent.isRoot(normalized) && !LOGGER_NAME.matcher(normalized).matches()) {
            return "Logger name must look like a Java package or class name, e.g. "
                    + "sailpoint.api.Provisioner - or any custom logger of your own, "
                    + "e.g. rule.groupAggregationRefresh.DBMSQL.";
        }
        if (level == null || Log4jAgent.parseLevel(level) == null) {
            return "Level must be one of " + Log4jAgent.LEVELS + ".";
        }
        return null;
    }

    /**
     * @return absolute expiry in epoch millis, 0 for never, or -1 if the
     *         requested TTL is not permitted.
     */
    private long resolveExpiry(SailPointContext ctx, Map<String, Object> body, String level) {
        int maxTtl = PluginSettings.getInt(ctx, PluginSettings.S_MAX_TTL, 1440);
        int defTtl = PluginSettings.getInt(ctx, PluginSettings.S_DEFAULT_TTL, 60);

        Integer requested = null;
        if (body != null && body.get("ttlMinutes") != null) {
            try {
                requested = Integer.valueOf(String.valueOf(body.get("ttlMinutes")).trim());
            } catch (NumberFormatException ignored) {
                requested = null;
            }
        }
        int ttl = requested == null ? defTtl : requested;
        if (ttl <= 0) {
            // "Never expire". The TTL guard rail is there to stop debug
            // logging being left on and filling a disk, so it only applies to
            // levels that can increase output. Silencing a logger is allowed
            // to be permanent - otherwise quietening something noisy in
            // log4j2.properties is impossible: the override lapses and the
            // noise comes straight back.
            if (maxTtl <= 0) return 0L;
            if (Log4jAgent.isQuieting(level)) return 0L;
            return -1L;
        }
        if (maxTtl > 0 && ttl > maxTtl) ttl = maxTtl;
        return System.currentTimeMillis() + (ttl * 60000L);
    }

    private String hostsOf(Map<String, Object> body) {
        if (body == null) return LoggerConfigStore.ALL_HOSTS;
        Object v = body.get("hosts");
        if (v == null) v = body.get("host");
        if (v == null) return LoggerConfigStore.ALL_HOSTS;

        List<String> names = new ArrayList<>();
        if (v instanceof Collection) {
            for (Object o : (Collection<?>) v) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) names.add(s);
            }
        } else {
            for (String s : String.valueOf(v).split(",")) {
                if (!s.trim().isEmpty()) names.add(s.trim());
            }
        }
        if (names.isEmpty() || names.contains(LoggerConfigStore.ALL_HOSTS)) {
            return LoggerConfigStore.ALL_HOSTS;
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(",");
            sb.append(n);
        }
        return sb.toString();
    }

    private Identity requireUser() {
        try {
            return getLoggedInUser();
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] getLoggedInUser failed: " + t);
            return null;
        }
    }

    /** @return null if allowed, otherwise the message to send back. */
    private String capabilityDenial(Identity user) {
        String required = PluginSettings.getString(getContext(),
                PluginSettings.S_REQUIRED_CAP, "SystemAdministrator");
        if (hasCapability(user, required)) return null;
        LOG.warn("[TurnOnLoggers] " + user.getName() + " denied - missing capability " + required);
        return "You need the '" + required + "' capability to change logger levels.";
    }

    private boolean hasCapability(Identity user, String required) {
        if (user == null || required == null) return false;
        List<Capability> caps = user.getCapabilities();
        if (caps != null) {
            for (Capability c : caps) {
                if (required.equals(c.getName())) return true;
            }
        }
        List<Identity> wgs = user.getWorkgroups();
        if (wgs != null) {
            for (Identity wg : wgs) {
                List<Capability> wcaps = wg.getCapabilities();
                if (wcaps == null) continue;
                for (Capability c : wcaps) {
                    if (required.equals(c.getName())) return true;
                }
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Response json(Response.Status status, Object body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    private Response error(Response.Status status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return json(status, m);
    }

    // ==================================================================
    // catalog - the loggers people actually reach for, so nobody has to
    // remember the fully-qualified class name under pressure.
    // ==================================================================

    private static Map<String, String> cat(String logger, String label) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("logger", logger);
        m.put("label", label);
        return m;
    }

    private static final List<Map<String, String>> CATALOG = Arrays.asList(
            cat("sailpoint.api.Provisioner", "Provisioning - plan compilation and execution"),
            cat("sailpoint.provisioning.PlanCompiler", "Provisioning - plan compiler detail"),
            cat("sailpoint.api.Aggregator", "Aggregation - account/entitlement aggregation"),
            cat("sailpoint.connector", "Connectors - all connector traffic"),
            cat("sailpoint.connector.JDBCConnector", "Connector - JDBC"),
            cat("sailpoint.connector.LDAPConnector", "Connector - LDAP"),
            cat("sailpoint.connector.DelimitedFileConnector", "Connector - delimited file"),
            cat("sailpoint.connector.webservices", "Connector - web services"),
            cat("sailpoint.integration", "Integration executors"),
            cat("sailpoint.api.Identitizer", "Identity refresh - attribute promotion"),
            cat("sailpoint.task.IdentityRefreshExecutor", "Task - identity refresh"),
            cat("sailpoint.api.Certificationer", "Certifications"),
            cat("sailpoint.api.CorrelationModel", "Role correlation model"),
            cat("sailpoint.api.Matchmaker", "Role assignment matching"),
            cat("sailpoint.workflow", "Workflows - engine"),
            cat("sailpoint.api.Workflower", "Workflows - launch and advance"),
            cat("sailpoint.server.BSFRuleRunner", "Rules - BeanShell execution"),
            cat("sailpoint.web.PageAuthenticationFilter", "Login - authentication filter"),
            cat("sailpoint.service.PageAuthenticationService", "Login - authentication service"),
            cat("sailpoint.web.sso", "Login - SSO authenticators"),
            cat("sailpoint.rest", "REST API requests"),
            cat("sailpoint.scim", "SCIM API requests"),
            cat("sailpoint.server.Servicer", "Services - scheduler and heartbeat"),
            cat("sailpoint.request", "Request processor"),
            cat("sailpoint.persistence.hql", "Persistence - generated HQL"),
            cat("org.hibernate.SQL", "Persistence - raw SQL"),
            cat("sailpoint.api.Meter", "Performance metering"),
            cat("sailpoint.plugin", "Plugin framework"),
            cat("com.example.turnonloggers", "This plugin itself")
    );
}
