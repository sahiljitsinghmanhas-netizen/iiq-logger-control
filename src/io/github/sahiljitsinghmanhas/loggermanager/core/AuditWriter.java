package io.github.sahiljitsinghmanhas.loggermanager.core;

import sailpoint.api.SailPointContext;
import sailpoint.object.AuditConfig;
import sailpoint.object.AuditEvent;
import sailpoint.server.Auditor;

import java.util.Date;

/**
 * Writes an IIQ audit event for every change made through this plugin, so
 * "who turned that on, when, and why" has an answer outside the application
 * log.
 *
 * One audit action is used for everything - {@value #ACTION} - so there is a
 * single thing to switch on in Audit Configuration rather than half a dozen.
 * What actually happened is in the event's fields.
 *
 * IIQ only persists an event if its action is enabled in the AuditConfig
 * singleton, and that object is emphatically not something a plugin should
 * import: doing so replaces the whole configuration and silently switches off
 * every other audit action in the environment. So the action is registered
 * additively and only when an administrator asks for it (see
 * {@link #enable(SailPointContext)}), leaving every existing action untouched.
 *
 * Auditing never gates the change itself: if the action is disabled, or the
 * write fails, the level change still happens and is still written to
 * sailpoint.log.
 */
public final class AuditWriter {

    private static final org.apache.log4j.Logger LOG =
            org.apache.log4j.Logger.getLogger(AuditWriter.class);

    /** The single audit action this plugin emits. */
    public static final String ACTION = "LoggerManagerChange";
    public static final String ACTION_DISPLAY = "Logger Manager change";

    private AuditWriter() {
    }

    /** Registration is attempted once per JVM, not on every write. */
    private static volatile boolean registrationTried = false;

    /**
     * Make sure the action appears in Audit Configuration, so these events show
     * up in the Audit Search action list like any other.
     *
     * This is presentation only. Whether or not it succeeds, events are written
     * regardless - see {@link #log}. Purely additive: existing actions are read,
     * kept, and written back exactly as they were.
     */
    static boolean ensureRegistered(SailPointContext ctx) {
        try {
            AuditConfig cfg = ctx.getObjectByName(AuditConfig.class, AuditConfig.OBJ_NAME);
            if (cfg == null) {
                LOG.warn("[TurnOnLoggers] no AuditConfig object found; cannot enable auditing");
                return false;
            }
            AuditConfig.AuditAction existing = cfg.getAuditAction(ACTION);
            if (existing != null) {
                if (!existing.isEnabled()) existing.setEnabled(true);
            } else {
                AuditConfig.AuditAction a = new AuditConfig.AuditAction();
                a.setName(ACTION);
                a.setDisplayName(ACTION_DISPLAY);
                a.setEnabled(true);
                if (cfg.getActions() == null) {
                    cfg.setActions(new java.util.ArrayList<AuditConfig.AuditAction>());
                }
                cfg.getActions().add(a);   // add, never replace the list
            }
            ctx.saveObject(cfg);
            ctx.commitTransaction();
            LOG.info("[TurnOnLoggers] audit action " + ACTION + " enabled");
            return true;
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] could not enable audit action: " + t);
            return false;
        }
    }

    /**
     * Verbs that record someone looking at something rather than changing it.
     *
     * Reading production logs is worth auditing - that is why these are written
     * at all - but they are not changes, and on a busy day a handful of
     * searches will bury the one override anyone is trying to find. The
     * distinction is stamped on the event at write time so it is durable, and
     * kept here rather than in the UI so there is one list rather than two that
     * can disagree.
     */
    private static final java.util.Set<String> READ_VERBS = new java.util.HashSet<>(
            java.util.Arrays.asList("read log", "read logs", "searched logs", "synced"));

    /** True if {@code what} records a look rather than a change. */
    public static boolean isRead(String what) {
        return what != null && READ_VERBS.contains(what);
    }

    /** Never let reading the revision be the thing that breaks an audit write. */
    private static int revisionOrZero(SailPointContext ctx) {
        try {
            return LoggerConfigStore.revision(ctx);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Record one change.
     *
     * @param what   short verb: enabled, disabled, updated, silenced, cleared...
     * @param logger the logger acted on, or a summary for bulk actions
     */
    public static void log(SailPointContext ctx, String user, String what, String logger,
                           String level, String hosts, long expires, String note) {
        // Always in the application log too.
        LOG.info("[TurnOnLoggers] " + user + " " + what + " " + logger
                + (level == null ? "" : "=" + level)
                + (hosts == null ? "" : " hosts=" + hosts)
                + (expires <= 0 ? "" : " until=" + new Date(expires))
                + " rev=" + revisionOrZero(ctx)
                + (note == null || note.isEmpty() ? "" : " note=" + note));
        try {
            if (!registrationTried) {
                registrationTried = true;
                ensureRegistered(ctx);
            }
            AuditEvent e = new AuditEvent();
            e.setAction(ACTION);
            e.setSource(user);
            e.setTarget(logger);
            e.setString1(what);
            e.setString2(level == null ? "" : level);
            e.setString3(hosts == null ? "" : hosts);
            e.setString4(expires <= 0 ? "never" : String.valueOf(new Date(expires)));
            if (note != null && !note.isEmpty()) e.setAttribute("note", note);
            e.setAttribute("plugin", PluginSettings.PLUGIN_NAME);
            // The revision this change produced. On its own the counter only
            // answers "is this host up to date"; stamped here it also answers
            // "what was revision 131", which is the question people actually
            // ask. Read rather than passed in, because every caller has already
            // saved the configuration by this point - so no call site changes,
            // and none of them can forget.
            e.setAttribute("revision", String.valueOf(revisionOrZero(ctx)));
            e.setAttribute("kind", isRead(what) ? "read" : "change");
            // Written unconditionally, NOT gated on the action being enabled in
            // Audit Configuration. These are privileged changes to what a
            // production system logs; whether they are recorded must not be
            // something the person making them can switch off. Auditor.log is
            // tried first so IIQ's own handling applies where it is enabled,
            // and the event is persisted directly when it is not.
            boolean written = false;
            try {
                written = Auditor.log(e, ctx);
            } catch (Throwable ignored) {
                written = false;
            }
            if (!written || e.getId() == null) ctx.saveObject(e);
            // Auditor.log only puts the event in the Hibernate session; it does
            // not commit. The very next thing the sync does is decache(), which
            // threw the pending event away - events were being written and
            // silently lost. Commit here so the trail is actually durable.
            ctx.commitTransaction();
        } catch (Throwable t) {
            // An audit failure must never undo or block the change itself.
            LOG.warn("[TurnOnLoggers] audit write failed: " + t);
        }
    }
}
