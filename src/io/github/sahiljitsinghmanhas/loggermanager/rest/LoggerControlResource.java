package io.github.sahiljitsinghmanhas.loggermanager.rest;

import io.github.sahiljitsinghmanhas.loggermanager.core.AuditWriter;
import io.github.sahiljitsinghmanhas.loggermanager.core.CollectionStore;
import io.github.sahiljitsinghmanhas.loggermanager.core.LogTail;
import io.github.sahiljitsinghmanhas.loggermanager.core.HostFacts;
import io.github.sahiljitsinghmanhas.loggermanager.core.Log4jAgent;
import io.github.sahiljitsinghmanhas.loggermanager.core.LoggerConfigStore;
import io.github.sahiljitsinghmanhas.loggermanager.core.LoggerSync;
import io.github.sahiljitsinghmanhas.loggermanager.core.PluginSettings;
import io.github.sahiljitsinghmanhas.loggermanager.service.LoggerSyncService;
import org.apache.log4j.Logger;
import sailpoint.api.SailPointContext;
import sailpoint.object.AuditEvent;
import sailpoint.object.Capability;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.QueryOptions;
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
import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
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

    /**
     * Whether to draw this plugin's icon in IdentityIQ's header for the caller.
     *
     * Called by ui/js/snippets/header.js on the first page of a browser
     * session. IdentityIQ has already decided the caller holds the SPRight
     * named by the snippet's rightRequired, or this file would never have been
     * sent; this confirms the two things a right cannot say - that the icon is
     * switched on, and that the caller also passes the plugin's own capability
     * check, so nobody is handed a link to a 403.
     *
     * Always 200. A denial here is a routine answer about the caller's own
     * access, not an error, and this runs on ordinary product pages where an
     * error status would surface in consoles and logs for no reason. For the
     * same reason it checks the capability directly rather than through
     * capabilityDenial(), which warns on every denial - that would put a line
     * in sailpoint.log for every such user.
     */
    @GET
    @Path("nav")
    @Produces(MediaType.APPLICATION_JSON)
    public Response nav() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("show", Boolean.FALSE);
        try {
            Identity user = requireUser();
            if (user == null) return json(Response.Status.OK, out);
            if (!PluginSettings.getBool(getContext(), PluginSettings.S_NAV_ICON, true)) {
                return json(Response.Status.OK, out);
            }
            String required = PluginSettings.getString(getContext(),
                    PluginSettings.S_REQUIRED_CAP, "SystemAdministrator");
            out.put("show", Boolean.valueOf(hasCapability(user, required)));
            return json(Response.Status.OK, out);
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] nav check failed: " + t);
            return json(Response.Status.OK, out);
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

            // Required, not optional. Six months on, "who turned this on" is
            // answerable from the audit trail but "why" is not, unless someone
            // was made to say so at the time.
            if (note == null || note.trim().length() < 3) {
                return error(Response.Status.BAD_REQUEST,
                        "A note is required - say why this logger is being turned on. "
                                + "A ticket number or a sentence is enough; it is recorded in the "
                                + "audit trail and shown to whoever finds the logger later.");
            }
            note = note.trim();

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

    /**
     * Drop overrides. Everything by default; only the expired ones with
     * {@code ?expiredOnly=true}.
     *
     * A query parameter rather than a second path, because "entries/expired"
     * and "entries/{id}" are the same shape to a router and the difference
     * would rest on JAX-RS preferring the literal - a subtlety nobody should
     * have to know about to be sure the panic button still works.
     */
    @DELETE
    @Path("entries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearAll(@QueryParam("expiredOnly") @DefaultValue("false") boolean expiredOnly) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();

            if (expiredOnly) {
                long now = System.currentTimeMillis();
                List<Map<String, String>> all = LoggerConfigStore.loadEntries(ctx);
                List<Map<String, String>> keep = new ArrayList<>();
                int removed = 0;
                for (Map<String, String> e : all) {
                    if (LoggerConfigStore.isExpired(e, now)) removed++;
                    else keep.add(e);
                }
                if (removed == 0) {
                    // Nothing to do is not an error, and re-saving would bump
                    // the revision and send every host chasing a no-op.
                    return json(Response.Status.OK, buildState(user));
                }
                LoggerConfigStore.saveEntries(ctx, keep, user.getName());
                AuditWriter.log(ctx, user.getName(), "removed expired overrides",
                        "(" + removed + " expired)", null, "*", 0L, null);
                LoggerSync.run(ctx, "rest:clearExpired");
                return json(Response.Status.OK, buildState(user));
            }

            LoggerConfigStore.saveEntries(ctx, new ArrayList<Map<String, String>>(), user.getName());
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
            if (target != null && !target.trim().isEmpty()) {
                String prot = PluginSettings.untouchableMatch(ctx, target);
                if (prot != null) {
                    return error(Response.Status.BAD_REQUEST, target
                            + (prot.equalsIgnoreCase(target)
                                ? " is in the untouchable loggers list"
                                : " is protected by the pattern '" + prot
                                  + "' in the untouchable loggers list")
                            + " and cannot be cleared from here.");
                }
            }
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

    /**
     * Delete the status records left behind by hosts IdentityIQ no longer
     * lists. Only reachable deliberately - orphans=true is required, so a
     * DELETE that loses its query string cannot wipe every host's record.
     *
     * The host serving this request is never an orphan even if its Server row
     * is momentarily missing, because it is demonstrably running: it answered.
     */
    @DELETE
    @Path("hosts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forgetOrphans(@QueryParam("orphans") String orphans) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);
            if (!"true".equalsIgnoreCase(orphans)) {
                return error(Response.Status.BAD_REQUEST, "Pass orphans=true to confirm.");
            }

            SailPointContext ctx = getContext();
            Set<String> known = new LinkedHashSet<>();
            List<Server> servers = ctx.getObjects(Server.class);
            if (servers == null) {
                return error(Response.Status.CONFLICT,
                        "IdentityIQ returned no Server list, so there is nothing to compare against.");
            }
            for (Server s : servers) known.add(s.getName());

            String me = HostFacts.hostName();
            List<String> gone = new ArrayList<>();
            for (String host : LoggerConfigStore.allStatuses(ctx).keySet()) {
                if (known.contains(host) || host.equalsIgnoreCase(me)) continue;
                LoggerConfigStore.deleteStatus(ctx, host);
                gone.add(host);
            }
            if (!gone.isEmpty()) {
                AuditWriter.log(ctx, user.getName(), "cleared orphaned host records",
                        String.join(", ", gone), null, String.join(", ", gone), 0L, null);
            }
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] forgetOrphans failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ==================================================================
    // saved collections
    // ==================================================================

    @POST
    @Path("collections")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveCollection(Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            String name = str(body, "name");
            if (name == null || name.trim().isEmpty()) {
                return error(Response.Status.BAD_REQUEST, "A collection needs a name.");
            }
            name = name.trim();
            if (name.length() > 80) name = name.substring(0, 80);

            // Either an explicit list, or a snapshot of what is on right now.
            Map<String, String> loggers = new LinkedHashMap<>();
            Object given = body == null ? null : body.get("loggers");
            if (given instanceof Collection) {
                for (Object o : (Collection<?>) given) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String lg = m.get("logger") == null ? null : String.valueOf(m.get("logger"));
                    String lv = m.get("level") == null ? null : String.valueOf(m.get("level"));
                    if (lg != null && Log4jAgent.parseLevel(lv) != null) loggers.put(lg, lv);
                }
            } else {
                long now = System.currentTimeMillis();
                for (Map<String, String> e : LoggerConfigStore.loadEntries(ctx)) {
                    if (LoggerConfigStore.isExpired(e, now)) continue;
                    loggers.put(e.get(LoggerConfigStore.E_LOGGER), e.get(LoggerConfigStore.E_LEVEL));
                }
            }
            if (loggers.isEmpty()) {
                return error(Response.Status.BAD_REQUEST,
                        "Nothing to save - turn some loggers on first, or pass a list.");
            }

            CollectionStore.add(ctx, name, str(body, "description"), loggers, user.getName());
            AuditWriter.log(ctx, user.getName(), "saved collection", name,
                    null, null, 0L, String.valueOf(loggers.keySet()));
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] saveCollection failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /**
     * Replace the contents of a saved collection.
     *
     * Editing rather than re-saving: POST /collections with an existing name
     * drops the old row and writes a new one, which would give the collection a
     * fresh id and reset "saved by" and the date to whoever last removed a
     * logger from it. A collection is shared with everyone using the plugin, so
     * that provenance is worth keeping.
     *
     * Logger names ARE validated here, unlike on save. Save takes what is
     * already running, so the names came from log4j2 itself; edit takes them
     * from somebody typing into a box.
     */
    @PUT
    @Path("collections/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateCollection(@PathParam("id") String id, Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            Map<String, String> existing = CollectionStore.byId(ctx, id);
            if (existing == null) {
                return error(Response.Status.NOT_FOUND,
                        "That collection no longer exists - somebody else may have deleted it.");
            }

            String name = str(body, "name");
            if (name != null) {
                name = name.trim();
                if (name.isEmpty()) return error(Response.Status.BAD_REQUEST, "A collection needs a name.");
                if (name.length() > 80) name = name.substring(0, 80);
                // Names are how people find these, and add() treats a repeated
                // name as a replacement. Letting an edit collide would make one
                // of the two vanish the next time anyone saved.
                for (Map<String, String> other : CollectionStore.load(ctx)) {
                    if (id.equals(other.get(CollectionStore.C_ID))) continue;
                    if (name.equalsIgnoreCase(other.get(CollectionStore.C_NAME))) {
                        return error(Response.Status.BAD_REQUEST,
                                "There is already a collection called \"" + name + "\".");
                    }
                }
            }

            Map<String, String> loggers = null;
            Object given = body == null ? null : body.get("loggers");
            if (given instanceof Collection) {
                loggers = new LinkedHashMap<>();
                for (Object o : (Collection<?>) given) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String lg = m.get("logger") == null ? null : String.valueOf(m.get("logger")).trim();
                    String lv = m.get("level") == null ? null : String.valueOf(m.get("level"));
                    if (lg == null || lg.isEmpty()) continue;
                    if (!LOGGER_NAME.matcher(lg).matches()) {
                        return error(Response.Status.BAD_REQUEST,
                                "\"" + lg + "\" is not a valid logger name.");
                    }
                    if (Log4jAgent.parseLevel(lv) == null) {
                        return error(Response.Status.BAD_REQUEST,
                                "\"" + lv + "\" is not a level. Use one of " + Log4jAgent.LEVELS + ".");
                    }
                    loggers.put(lg, lv);
                }
                if (loggers.isEmpty()) {
                    return error(Response.Status.BAD_REQUEST,
                            "A collection needs at least one logger. Delete it instead if you are "
                            + "finished with it.");
                }
            }

            Map<String, String> updated = CollectionStore.update(ctx, id, name,
                    str(body, "description"), loggers);
            if (updated == null) {
                return error(Response.Status.NOT_FOUND, "That collection no longer exists.");
            }

            AuditWriter.log(ctx, user.getName(), "edited collection",
                    updated.get(CollectionStore.C_NAME), null, null, 0L,
                    updated.get(CollectionStore.C_LOGGERS));
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] updateCollection failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @POST
    @Path("collections/{id}/apply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response applyCollection(@PathParam("id") String id, Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            Map<String, String> coll = CollectionStore.byId(ctx, id);
            if (coll == null) return error(Response.Status.NOT_FOUND, "No such collection.");

            String hosts = hostsOf(body);
            List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
            List<String> applied = new ArrayList<>();
            List<String> refused = new ArrayList<>();

            for (Map<String, String> pair : CollectionStore.parse(coll.get(CollectionStore.C_LOGGERS))) {
                String logger = pair.get("logger");
                String level = pair.get("level");
                String bad = validate(ctx, logger, level);
                if (bad != null) { refused.add(logger); continue; }

                long expires = resolveExpiry(ctx, body, level);
                if (expires < 0) { refused.add(logger); continue; }

                String normalized = Log4jAgent.normalize(logger);
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map<String, String> e = entries.get(i);
                    if (equalsIgnoreCase(Log4jAgent.normalize(e.get(LoggerConfigStore.E_LOGGER)), normalized)
                            && equalsIgnoreCase(e.get(LoggerConfigStore.E_HOSTS), hosts)) {
                        entries.remove(i);
                    }
                }
                // The collection name goes in the note, so the table and the
                // audit trail both say where an override came from.
                entries.add(LoggerConfigStore.newEntry(Log4jAgent.display(normalized), level,
                        hosts, expires, user.getName(),
                        "from collection: " + coll.get(CollectionStore.C_NAME)));
                applied.add(Log4jAgent.display(normalized) + "=" + level);
            }

            if (applied.isEmpty()) {
                return error(Response.Status.BAD_REQUEST,
                        "Nothing in that collection could be applied. Refused: " + refused);
            }
            LoggerConfigStore.saveEntries(ctx, entries, user.getName());
            AuditWriter.log(ctx, user.getName(), "applied collection",
                    coll.get(CollectionStore.C_NAME), null, hosts, 0L,
                    String.valueOf(applied) + (refused.isEmpty() ? "" : " refused=" + refused));
            LoggerSync.run(ctx, "rest:collection");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] applyCollection failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @DELETE
    @Path("collections/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteCollection(@PathParam("id") String id) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            Map<String, String> coll = CollectionStore.byId(ctx, id);
            if (coll == null) return error(Response.Status.NOT_FOUND, "No such collection.");
            CollectionStore.remove(ctx, id);
            AuditWriter.log(ctx, user.getName(), "deleted collection",
                    coll.get(CollectionStore.C_NAME), null, null, 0L, null);
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] deleteCollection failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ==================================================================
    // log tail
    // ==================================================================

    /**
     * The end of one of this host's own log files.
     *
     * The file is chosen by index into the list this host reported, never by
     * path, so there is nothing for a caller to traverse - only files this JVM
     * already writes to are reachable.
     */
    /** How much history the panel will show. Enough to cover a working week. */
    private static final int HISTORY_MAX = 200;

    /**
     * What has been changed through this plugin, most recent first.
     *
     * Read straight back out of the audit trail rather than from a separate
     * history object. The trail is already the record - it is written on every
     * action, it cannot be switched off from inside the plugin, and it outlives
     * the plugin being uninstalled. A second copy in a Custom would be a second
     * thing to keep correct, would reintroduce the multi-writer contention the
     * configuration object was shaped to avoid, and would eventually have to
     * throw history away to stay a sensible size. This throws nothing away.
     */
    @GET
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response history(@QueryParam("limit") @DefaultValue("50") int limit,
                            @QueryParam("kind") @DefaultValue("change") String kind) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            if (limit <= 0) limit = 50;
            if (limit > HISTORY_MAX) limit = HISTORY_MAX;

            SailPointContext ctx = getContext();
            QueryOptions qo = new QueryOptions();
            qo.add(Filter.eq("action", AuditWriter.ACTION));
            qo.setOrderBy("created");
            qo.setOrderAscending(false);
            // Over-fetch and filter here rather than in the query: the change/read
            // marker lives in the event's attribute map, which is a CLOB and not
            // something to build a Filter around. Fifty changes are worth finding
            // even if a few hundred searches happened in between.
            qo.setResultLimit(HISTORY_MAX);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<AuditEvent> events = ctx.getObjects(AuditEvent.class, qo);
            if (events != null) {
                boolean changesOnly = !"all".equalsIgnoreCase(kind);
                for (AuditEvent e : events) {
                    if (rows.size() >= limit) break;
                    // Older events predate the marker; fall back to the same
                    // verb list rather than guessing or dropping them.
                    Object k = e.getAttribute("kind");
                    boolean isRead = k != null
                            ? "read".equalsIgnoreCase(String.valueOf(k))
                            : AuditWriter.isRead(e.getString1());
                    if (changesOnly && isRead) continue;

                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("kind", isRead ? "read" : "change");
                    r.put("when", e.getCreated() == null ? "0"
                            : String.valueOf(e.getCreated().getTime()));
                    r.put("who", e.getSource());
                    r.put("what", e.getString1());
                    r.put("logger", e.getTarget());
                    r.put("level", e.getString2());
                    r.put("hosts", e.getString3());
                    r.put("expires", e.getString4());
                    Object rev = e.getAttribute("revision");
                    // Blank rather than a made-up number for anything recorded
                    // before the revision was stamped on.
                    r.put("revision", rev == null ? "" : String.valueOf(rev));
                    Object note = e.getAttribute("note");
                    r.put("note", note == null ? "" : String.valueOf(note));
                    rows.add(r);
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("limit", limit);
            out.put("kind", "all".equalsIgnoreCase(kind) ? "all" : "change");
            out.put("truncated", rows.size() >= limit);
            return json(Response.Status.OK, out);
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] history failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /**
     * Stream this host's whole log file to the browser as a download.
     *
     * Only this host's own file, and only a file its own log4j2 configuration
     * already writes to - the same rule the tail follows. There is no path
     * parameter to point somewhere else, and asking for another host is a
     * refusal rather than an attempt, because no host can read another's disk:
     * that is the whole reason the rest of this plugin publishes findings
     * through the database instead.
     *
     * Streamed rather than read into memory. These files reach hundreds of
     * megabytes, and this runs inside IdentityIQ's own JVM.
     */
    @GET
    @Path("logfile")
    public Response logFile(@QueryParam("host") @DefaultValue("") String host,
                            @QueryParam("index") @DefaultValue("0") int index) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            if (!PluginSettings.getBool(ctx, PluginSettings.S_LOGTAIL, true)) {
                return error(Response.Status.FORBIDDEN,
                        "Reading log files from this page is switched off in the plugin settings.");
            }

            String me = HostFacts.hostName();
            if (host != null && !host.trim().isEmpty() && !host.trim().equals(me)) {
                return error(Response.Status.BAD_REQUEST,
                        "Only " + me + " can stream its own log file, and this request reached "
                        + me + ". No host can read another host's disk - open " + host.trim()
                        + " from its own page, or save what it has already reported.");
            }

            final List<String> files = HostFacts.logFilePaths();
            if (files.isEmpty()) {
                return error(Response.Status.NOT_FOUND,
                        "This host's log4j2 configuration does not name a file to read.");
            }
            if (index < 0 || index >= files.size()) {
                return error(Response.Status.BAD_REQUEST,
                        "There is no log file " + index + " on this host.");
            }

            final java.io.File f = new java.io.File(files.get(index));
            if (!f.isFile() || !f.canRead()) {
                return error(Response.Status.NOT_FOUND,
                        "Cannot read " + f.getPath() + " from this host.");
            }

            // A five gigabyte log is not something anyone means to start
            // downloading. Past the ceiling the end of the file is sent instead,
            // which is the part somebody investigating wants anyway, and the
            // button says so rather than the download quietly being partial.
            int capMb = PluginSettings.getInt(ctx, PluginSettings.S_DL_FULL_MB, 0);
            final long total = f.length();
            final long cap = capMb <= 0 ? total : (long) capMb * 1024L * 1024L;
            final long from = total > cap ? total - cap : 0L;
            final long sending = total - from;

            AuditWriter.log(ctx, user.getName(), "downloaded log file", f.getName(),
                    null, me, 0L, sending + " of " + total + " bytes");

            javax.ws.rs.core.StreamingOutput body = new javax.ws.rs.core.StreamingOutput() {
                public void write(java.io.OutputStream out) throws java.io.IOException {
                    // Streamed in 64 KB pieces, never held whole: this runs
                    // inside IdentityIQ's own JVM and the file can be enormous.
                    java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r");
                    try {
                        if (from > 0) in.seek(from);
                        // Exactly the number of bytes promised in Content-Length,
                        // and not one more. A log is being written to while it is
                        // being read, so streaming to end-of-file sends more than
                        // was declared - which a browser tolerates and a proxy
                        // treats as a broken response.
                        byte[] buf = new byte[64 * 1024];
                        long left = sending;
                        while (left > 0) {
                            int want = (int) Math.min((long) buf.length, left);
                            int n = in.read(buf, 0, want);
                            if (n <= 0) break;          // truncated under us
                            out.write(buf, 0, n);
                            left -= n;
                        }
                        // If the file shrank - a rollover mid-download - pad the
                        // difference rather than close short, since a short body
                        // against a declared length is the same protocol error.
                        if (left > 0) {
                            byte[] pad = new byte[8 * 1024];
                            while (left > 0) {
                                int n = (int) Math.min((long) pad.length, left);
                                out.write(pad, 0, n);
                                left -= n;
                            }
                        }
                        out.flush();
                    } finally {
                        try { in.close(); } catch (java.io.IOException ignored) { }
                    }
                }
            };

            String name = (from > 0 ? me + "-last-" + capMb + "mb-" : me + "-") + f.getName();
            return Response.ok(body, MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                    .header("Content-Length", String.valueOf(sending))
                    .build();
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] logFile failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @GET
    @Path("logtail")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logTail(@QueryParam("index") @DefaultValue("0") int index,
                            @QueryParam("kb") @DefaultValue("64") int kb) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            if (!PluginSettings.getBool(ctx, PluginSettings.S_LOGTAIL, true)) {
                return error(Response.Status.FORBIDDEN,
                        "Reading log files is switched off in the plugin settings.");
            }
            int cap = PluginSettings.getInt(ctx, PluginSettings.S_LOGTAIL_KB, 64);
            if (kb > cap) kb = cap;

            LogTail.Result r = LogTail.tail(index, kb);
            AuditWriter.log(ctx, user.getName(), "read log", r.path,
                    null, HostFacts.hostName(), 0L, kb + "KB");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", HostFacts.hostName());
            out.put("path", r.path);
            out.put("fileBytes", String.valueOf(r.fileBytes));
            out.put("readBytes", String.valueOf(r.readBytes));
            out.put("truncated", r.truncated);
            out.put("lines", r.lines);
            out.put("error", r.error);
            return json(Response.Status.OK, out);
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] logTail failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    /**
     * Start or stop a cluster-wide log search.
     *
     * No host can read another one's disk, so this does not fetch anything: it
     * records what to look for, and every host answers about its own file on
     * its next sync, publishing matching lines into its own status object. The
     * host serving this request answers immediately; the rest follow within one
     * interval. The search stops being answered after fifteen minutes so hosts
     * are not left scanning their logs forever.
     */
    /**
     * Ask one host for the end of its log, as a file.
     *
     * Separate from logquery on purpose. A download used to be filed as the
     * asker's search, which emptied their log viewer for as long as it took to
     * arrive and tied the size of the file to the number of lines they had on
     * screen. It is its own request now: pressing this does not disturb a
     * search, anyone else's or your own.
     *
     * Passing no host clears the request, which the page does as soon as the
     * file has been saved - there is no reason to carry those megabytes on
     * every poll afterwards.
     */
    @POST
    @Path("logdownload")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response logDownload(Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            if (!PluginSettings.getBool(ctx, PluginSettings.S_LOGTAIL, true)) {
                return error(Response.Status.FORBIDDEN,
                        "Reading log files is switched off in the plugin settings.");
            }
            String host = str(body, "host");
            LoggerConfigStore.setLogDownload(ctx, user.getName(), host);
            if (host != null && !host.trim().isEmpty()) {
                int mb = PluginSettings.getInt(ctx, PluginSettings.S_DL_TRUNC_MB, 2);
                AuditWriter.log(ctx, user.getName(), "downloaded log",
                        "last " + mb + "MB", null, host.trim(), 0L, null);
                LoggerSync.run(ctx, "rest:logdownload");
            }
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] logDownload failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @POST
    @Path("logquery")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response logQuery(Map<String, Object> body) {
        try {
            Identity user = requireUser();
            if (user == null) return error(Response.Status.UNAUTHORIZED, "Not authenticated.");
            String denied = capabilityDenial(user);
            if (denied != null) return error(Response.Status.FORBIDDEN, denied);

            SailPointContext ctx = getContext();
            if (!PluginSettings.getBool(ctx, PluginSettings.S_LOGTAIL, true)) {
                return error(Response.Status.FORBIDDEN,
                        "Reading log files is switched off in the plugin settings.");
            }
            String text = str(body, "text");
            String mode = str(body, "mode");
            int lines = 40;
            try {
                if (body != null && body.get("lines") != null) {
                    lines = Integer.parseInt(String.valueOf(body.get("lines")).trim());
                }
            } catch (NumberFormatException ignored) {
                lines = 40;
            }
            String host = str(body, "host");
            LoggerConfigStore.setLogQuery(ctx, user.getName(), text, mode, lines, host);
            if ("tail".equals(mode)) {
                AuditWriter.log(ctx, user.getName(), "read logs",
                        "last " + lines + " lines", null,
                        (host == null || host.trim().isEmpty()) ? "*" : host.trim(), 0L, null);
            } else if (text != null && !text.trim().isEmpty()) {
                AuditWriter.log(ctx, user.getName(), "searched logs", text.trim(),
                        null, "*", 0L, null);
            }
            LoggerSync.run(ctx, "rest:logquery");
            return json(Response.Status.OK, buildState(user));
        } catch (Throwable t) {
            LOG.error("[TurnOnLoggers] logQuery failed", t);
            return error(Response.Status.INTERNAL_SERVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    // ==================================================================
    // state assembly
    // ==================================================================

    private Map<String, Object> buildState(Identity user) throws Exception {
        SailPointContext ctx = getContext();

        // If a release has moved the service executor, the ServiceDefinition
        // still names the old class and no host reconciles. This is the only
        // code path guaranteed to run in that state, because opening the page
        // does not depend on the service working.
        LoggerSyncService.ensureExecutor(ctx);

        long now = System.currentTimeMillis();
        String thisHost = HostFacts.hostName();

        List<Map<String, String>> entries = LoggerConfigStore.loadEntries(ctx);
        int revision = LoggerConfigStore.revision(ctx);
        Map<String, Map<String, Object>> statuses = LoggerConfigStore.allStatuses(ctx);

        // Which hosts count as existing, decided once. The override rows have
        // to agree with the host table: a status record that is not a host
        // must not turn up as a host an override is "pending on", or the page
        // reports waiting on something it refuses to show.
        boolean serversOnly = PluginSettings.getBool(ctx, PluginSettings.S_SERVERS_ONLY, true);
        List<Server> servers = null;
        try {
            servers = ctx.getObjects(Server.class);
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] could not list Server objects: " + t);
        }
        if (servers == null) serversOnly = false;

        Set<String> known = new LinkedHashSet<>();
        if (servers != null) for (Server s : servers) known.add(s.getName());

        // A host that has a status record but is not in IIQ's Server list.
        // Worked out the same way whichever mode we are in, because the label
        // is useful even when the host is shown: "retired in IIQ" is exactly
        // what someone needs to know before wondering why an override there is
        // stuck on pending.
        List<String> orphanNames = new ArrayList<>();
        for (String h : statuses.keySet()) {
            if (!known.contains(h) && !h.equalsIgnoreCase(thisHost)) orphanNames.add(h);
        }

        Set<String> visible = new LinkedHashSet<>(known);
        visible.add(thisHost);
        if (!serversOnly) visible.addAll(statuses.keySet());

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
        out.put("untouchableLoggers", new ArrayList<>(PluginSettings.untouchable(ctx)));
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
                    e.get(LoggerConfigStore.E_HOSTS), !expired, statuses, visible);
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
            addLiveOn(row, p.get("logger"), p.get("level"), p.get("hosts"), true, statuses, visible);
            rows.add(row);
        }
        out.put("entries", rows);

        // --- hosts --------------------------------------------------------
        out.put("hosts", buildHosts(servers, statuses, revision, now, thisHost, visible,
                orphanNames, user.getName()));
        out.put("orphanHosts", orphanNames);
        // Whether those orphans are also in the host table, so the page knows
        // whether it is offering cleanup for records nobody can see, or
        // labelling rows that are right there.
        out.put("showOrphans", !serversOnly);

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
        try {
            out.put("collections", CollectionStore.load(ctx));
        } catch (Throwable t) {
            out.put("collections", new ArrayList<Object>());
        }
        boolean tail = PluginSettings.getBool(ctx, PluginSettings.S_LOGTAIL, true);
        out.put("logTailEnabled", tail);
        out.put("logFiles", tail ? LogTail.files() : new ArrayList<Object>());
        out.put("logTailKb", PluginSettings.getInt(ctx, PluginSettings.S_LOGTAIL_KB, 64));
        try {
            // Only ever this caller's own request. Two people searching for
            // different things at once is normal; neither should see the other's.
            Map<String, String> mine = LoggerConfigStore.queryFor(ctx, user.getName());
            out.put("logQuery", mine == null ? "" : String.valueOf(mine.get(LoggerConfigStore.Q_TEXT)));
            out.put("logQueryAt", mine == null ? "0" : String.valueOf(mine.get(LoggerConfigStore.Q_AT)));
            out.put("logActive", mine != null);
            out.put("logMode", mine == null ? "search" : String.valueOf(mine.get(LoggerConfigStore.Q_MODE)));
            out.put("logLines", mine == null ? 40
                    : LoggerConfigStore.asInt(mine.get(LoggerConfigStore.Q_LINES), 40));
            out.put("logHost", mine == null ? "" : String.valueOf(mine.get(LoggerConfigStore.Q_HOST)));
            out.put("truncatedDownloadMb", PluginSettings.getInt(ctx, PluginSettings.S_DL_TRUNC_MB, 2));
            out.put("fullDownloadMaxMb", PluginSettings.getInt(ctx, PluginSettings.S_DL_FULL_MB, 0));
            try {
                List<String> lf = HostFacts.logFilePaths();
                java.io.File own = lf.isEmpty() ? null : new java.io.File(lf.get(0));
                out.put("thisHostLogBytes", own != null && own.isFile() ? own.length() : 0L);
            } catch (Throwable t) {
                out.put("thisHostLogBytes", 0L);
            }
            out.put("logHost", LoggerConfigStore.logHost(ctx));

            // A download in flight, and its lines once they land. Only ever
            // this caller's own, and only the one host that was asked - the
            // lines are megabytes, so they travel exactly once and the page
            // clears the request as soon as it has saved them.
            Map<String, Object> dl = new LinkedHashMap<>();
            Map<String, String> want = LoggerConfigStore.downloadFor(ctx, user.getName());
            if (want != null) {
                String target = String.valueOf(want.get(LoggerConfigStore.Q_HOST));
                dl.put("host", target);
                dl.put("askedAt", String.valueOf(want.get(LoggerConfigStore.Q_AT)));
                Map<String, Object> st = LoggerConfigStore.allStatuses(ctx).get(target);
                Map<String, Object> ans = answerIn(st, LoggerConfigStore.S_LOG_DL_ANSWERS,
                        user.getName());
                Object lines = ans == null ? null : ans.get(LoggerConfigStore.AN_MATCHES);
                dl.put("ready", lines instanceof List && !((List<?>) lines).isEmpty());
                dl.put("lines", lines);
                dl.put("error", ans == null ? "" : ans.get(LoggerConfigStore.AN_ERROR));
            }
            out.put("download", dl);
        } catch (Throwable t) {
            out.put("logQuery", "");
            out.put("logQueryAt", "0");
            out.put("logHost", "");
            out.put("logActive", false);
            out.put("logMode", "search");
            out.put("logLines", 40);
            out.put("logHost", "");
        }
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
                           Map<String, Map<String, Object>> statuses,
                           Set<String> visible) {
        List<String> confirmed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        // An expired override is not waiting for anything. It was applied and
        // has since been withdrawn, so every host would fall into "pending"
        // purely because the confirm test cannot pass once it is inactive -
        // which read as "this never landed", the opposite of what happened.
        if (!active) {
            row.put("confirmedOn", confirmed);
            row.put("pendingOn", pending);
            return;
        }
        for (Map.Entry<String, Map<String, Object>> se : statuses.entrySet()) {
            if (!visible.contains(se.getKey())) continue;
            if (!LoggerConfigStore.hostMatches(hostsSpec, se.getKey())) continue;
            Object appliedObj = se.getValue().get(LoggerConfigStore.S_APPLIED);
            String actual = null;
            if (appliedObj instanceof Map) {
                Object v = ((Map<?, ?>) appliedObj).get(loggerDisplay);
                actual = v == null ? null : String.valueOf(v);
            }
            if (actual != null && actual.equalsIgnoreCase(level)) {
                confirmed.add(se.getKey());
            } else {
                pending.add(se.getKey());
            }
        }
        row.put("confirmedOn", confirmed);
        row.put("pendingOn", pending);
    }

    private List<Map<String, Object>> buildHosts(List<Server> servers,
                                                 Map<String, Map<String, Object>> statuses,
                                                 int revision,
                                                 long now,
                                                 String thisHost,
                                                 Set<String> visible,
                                                 List<String> orphanNames,
                                                 String forUser) {
        // IdentityIQ's Server list is the source of truth for which hosts
        // exist: its heartbeat creates a Server the moment a JVM starts, and
        // recreates one if you delete it while the JVM is still running. So a
        // host that is genuinely retired is retired here too.
        //
        // Each host separately writes a status record - what it applied, which
        // log4j2 config it read, what is live in its JVM - which is the part
        // IIQ cannot tell us. Nothing garbage-collects those records, so when a
        // host leaves IIQ its record is left behind. Whether such a record is
        // still shown as a host is the caller's decision (hostsFromServersOnly);
        // either way it is flagged, because "retired in IIQ" is exactly what
        // someone needs to know before wondering why an override there is stuck.
        Map<String, Map<String, Object>> hosts = new LinkedHashMap<>();

        if (servers != null) {
            for (Server s : servers) {
                Map<String, Object> h = hostRow(hosts, s.getName());
                h.put("knownToIIQ", true);
                h.put("inactive", s.isInactive());
                h.put("heartbeat", s.getHeartbeat() == null
                        ? "" : String.valueOf(s.getHeartbeat().getTime()));
                h.put("serviceOff", !runsSyncService(s));
            }
        }

        for (Map.Entry<String, Map<String, Object>> e : statuses.entrySet()) {
            if (!visible.contains(e.getKey())) continue;
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
            // The lines themselves are the single biggest thing this endpoint
            // can return: every host stores up to TAIL_MAX_LINES of them, and
            // measured on a 21-host cluster they were 90% of the whole payload
            // - 353 KB against 31 KB idle - shipped to every open page every
            // ten seconds whether or not anyone had the log viewer open.
            //
            // The count always travels, because that is what the host chips
            // show and it costs nothing. The lines travel only when the caller
            // asks, which the page does once somebody is actually reading them.
            // Pull this caller's answer out of the per-user map and present it
            // in the shape the page has always read. No gating any more: the
            // only lines here are ones this person asked for.
            Map<String, Object> answer = answerFor(st, forUser);
            Object matches = answer == null ? null : answer.get(LoggerConfigStore.AN_MATCHES);
            h.put("logMatchCount", (matches instanceof List) ? ((List<?>) matches).size() : 0);
            h.put("logMatches", matches);
            h.put("logAnsweredAt", answer == null ? "0"
                    : String.valueOf(LoggerConfigStore.asLong(
                            String.valueOf(answer.get(LoggerConfigStore.AN_ANSWERED)), 0L)));
            h.put("logPath", answer == null ? "" : answer.get(LoggerConfigStore.AN_PATH));
            h.put("logError", answer == null ? "" : answer.get(LoggerConfigStore.AN_ERROR));
            h.put("tickError", st.get(LoggerConfigStore.S_TICK_ERROR));
            h.put("tickErrorAt", String.valueOf(
                    LoggerConfigStore.asLong(String.valueOf(st.get(LoggerConfigStore.S_TICK_ERROR_AT)), 0L)));
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

        // Flag the ones IIQ has forgotten. They are still fully operable - you
        // can read what is live on them and aim an override at them - they just
        // will not answer, so the label has to travel with the host everywhere
        // it is drawn rather than sit in one banner.
        for (String n : orphanNames) {
            Map<String, Object> h = hosts.get(n);
            if (h != null) h.put("orphaned", true);
        }

        markAmbiguousLeftovers(hosts.values());
        return new ArrayList<>(hosts.values());
    }

    /**
     * Flag "left over" rows that could equally be a rule's doing.
     *
     * The source of a logger is decided per host, from that host's own record
     * of what it created. That is right as far as it goes, but a rule can set a
     * logger on any host at any time, and it does not have to be the same host
     * it used last time. So a logger that this plugin created on host A, and
     * that is also being set at runtime somewhere else in the cluster, cannot
     * honestly be called litter on the strength of a per-host claim alone: the
     * thing on A might be ours, or it might be the rule, and nothing in the
     * running configuration distinguishes them.
     *
     * The cluster-wide view is the only place that ambiguity is visible, which
     * is why this lives here rather than in Log4jAgent. Nothing about the
     * classification changes - the row is still sourced "leftover", so filters,
     * counts and the clear logic all behave exactly as before. It only gains a
     * flag saying the label is not certain, which the page shows as
     * "left over / set at runtime".
     */
    @SuppressWarnings("unchecked")
    private void markAmbiguousLeftovers(Collection<Map<String, Object>> hostRows) {
        // Which logger names is something outside this plugin setting, anywhere?
        Set<String> runtimeSomewhere = new java.util.HashSet<>();
        for (Map<String, Object> h : hostRows) {
            Object live = h.get("liveLoggers");
            if (!(live instanceof List)) continue;
            for (Object o : (List<Object>) live) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> r = (Map<String, Object>) o;
                if ("runtime".equals(String.valueOf(r.get("source")))) {
                    runtimeSomewhere.add(String.valueOf(r.get("logger")));
                }
            }
        }
        if (runtimeSomewhere.isEmpty()) return;

        for (Map<String, Object> h : hostRows) {
            Object live = h.get("liveLoggers");
            if (!(live instanceof List)) continue;
            List<Object> rows = (List<Object>) live;
            List<Object> copy = new ArrayList<>(rows.size());
            boolean changed = false;
            for (Object o : rows) {
                if (!(o instanceof Map)) { copy.add(o); continue; }
                Map<String, Object> r = (Map<String, Object>) o;
                if ("leftover".equals(String.valueOf(r.get("source")))
                        && runtimeSomewhere.contains(String.valueOf(r.get("logger")))) {
                    // Copied rather than edited: these maps came straight off a
                    // cached Custom object, and writing through them would dirty
                    // something this request has no business changing.
                    Map<String, Object> flagged = new LinkedHashMap<>(r);
                    flagged.put("ambiguous", "true");
                    copy.add(flagged);
                    changed = true;
                } else {
                    copy.add(o);
                }
            }
            if (changed) h.put("liveLoggers", copy);
        }
    }

    /** One user's answer out of a host status record, or null. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> answerFor(Map<String, Object> status, String user) {
        return answerIn(status, LoggerConfigStore.S_LOG_ANSWERS, user);
    }

    /** One user's answer out of one of the per-user answer maps on a host. */
    private Map<String, Object> answerIn(Map<String, Object> status, String attribute, String user) {
        if (status == null || user == null) return null;
        Object raw = status.get(attribute);
        if (!(raw instanceof Map)) return null;
        Object mine = ((Map<?, ?>) raw).get(user);
        if (!(mine instanceof Map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) mine).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
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
                    + "e.g. rule.myCustomRule.";
        }
        if (level == null || Log4jAgent.parseLevel(level) == null) {
            return "Level must be one of " + Log4jAgent.LEVELS + ".";
        }
        // Greying the button out is a courtesy; this is the enforcement. A
        // protected logger cannot be changed through the API either.
        String shown = Log4jAgent.display(normalized);
        String pattern = PluginSettings.untouchableMatch(ctx, shown);
        if (pattern != null) {
            String by = pattern.equalsIgnoreCase(shown)
                    ? "is in the untouchable loggers list"
                    : "is protected by the pattern '" + pattern + "' in the untouchable loggers list";
            // Root is guarded twice, by two different settings, and someone who
            // has just turned 'Allow root logger' on and hit this deserves to
            // be told that rather than left toggling one switch.
            String also = Log4jAgent.isRoot(normalized)
                    ? " Root is guarded by two settings: 'Allow root logger' controls whether it may"
                      + " be targeted at all, and this list controls whether it may be changed or"
                      + " cleared. Both have to allow it."
                    : "";
            return shown + " " + by + " and cannot be changed from here. Edit 'Untouchable loggers'"
                    + " in the plugin settings if you really need to." + also;
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

    /**
     * Whether IdentityIQ will actually run this plugin's sync service on a host.
     *
     * A ServiceDefinition with hosts="global" is an offer, not a guarantee.
     * Host Configuration (gear icon -> Global Settings -> Host Configuration)
     * lets a deployment name, per Server, either the only services that host
     * runs or the ones it must not - IIQ stores those as the "includedServices"
     * and "excludedServices" attributes. Deployments that separate UI hosts
     * from task hosts use this routinely, and a UI host with an include list
     * will not run this service however global the definition is.
     *
     * The symptom, before this was detected, was indistinguishable from a
     * fault: that host never ticks, so it drifts past the stale threshold and
     * sits there - while its IIQ heartbeat stays green, because that is a
     * different service, and while anything done from the page brings it back
     * for one interval, because that runs on the request thread rather than on
     * a tick. The host looks broken and nothing anywhere says why.
     *
     * An include list that does not name the service excludes it just as
     * firmly as naming it in the exclude list. Both are checked.
     */
    private boolean runsSyncService(Server s) {
        try {
            // ONLY excludedServices. An earlier version of this also treated a
            // non-empty includedServices as "this host runs only these", and
            // reported hosts that were syncing perfectly well every thirty
            // seconds as not running the service at all. That reading is
            // wrong: for a definition that already covers every host,
            // includedServices is an opt-IN for services whose definition does
            // NOT cover it. A host that opts into some unrelated service has
            // not thereby opted out of this one.
            //
            // Inferring "not running" from configuration is guesswork against
            // a model with more cases than are visible from here, so this now
            // reports only the one case that is unambiguous - the service
            // named in this host's exclude list - and the page additionally
            // refuses to say it about a host that is demonstrably in sync.
            return !namesService(s.get(Server.ATT_EXCL_SERVICES));
        } catch (Throwable t) {
            // Never let a host-config quirk stop the page rendering; assume it
            // runs, which is the pre-existing behaviour.
            LOG.warn("[TurnOnLoggers] could not read host service config for "
                    + (s == null ? "?" : s.getName()) + ": " + t);
            return true;
        }
    }

    private boolean namesService(Object v) {
        for (String n : asNameList(v)) {
            if (LoggerSyncService.NAME.equalsIgnoreCase(n)) return true;
        }
        return false;
    }

    /** The attribute is a List on some versions and a CSV String on others. */
    @SuppressWarnings("unchecked")
    private List<String> asNameList(Object v) {
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                if (o != null && String.valueOf(o).trim().length() > 0) {
                    out.add(String.valueOf(o).trim());
                }
            }
        } else {
            for (String part : String.valueOf(v).split(",")) {
                if (part.trim().length() > 0) out.add(part.trim());
            }
        }
        return out;
    }

    /**
     * Lenient truth for a query parameter.
     *
     * Not a boolean parameter: JAX-RS binds those with Boolean.parseBoolean,
     * which is true only for the literal "true" - so ?logs=1 bound to false and
     * the lines were silently withheld from a caller who had plainly asked for
     * them. Anyone reaching for this endpoint by hand will write 1 as readily
     * as true.
     */
    private static boolean truthy(String v) {
        if (v == null) return false;
        String t = v.trim();
        return t.equalsIgnoreCase("true") || t.equals("1")
                || t.equalsIgnoreCase("yes") || t.equalsIgnoreCase("on");
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
            cat("io.github.sahiljitsinghmanhas.loggermanager", "This plugin itself")
    );
}
