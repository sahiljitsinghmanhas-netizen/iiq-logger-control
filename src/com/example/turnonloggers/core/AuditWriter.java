package com.example.turnonloggers.core;

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

    /** True when IIQ will actually persist our events. */
    public static boolean isEnabled() {
        try {
            return Auditor.isEnabled(ACTION);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Add our action to the AuditConfig if it is not already there, and enable
     * it. Purely additive - existing actions are read, kept, and written back
     * exactly as they were.
     *
     * @return true if auditing is on afterwards
     */
    public static boolean enable(SailPointContext ctx) {
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
     * Record one change.
     *
     * @param what   short verb: enabled, disabled, updated, silenced, cleared...
     * @param logger the logger acted on, or a summary for bulk actions
     */
    public static void log(SailPointContext ctx, String user, String what, String logger,
                           String level, String hosts, long expires, String note) {
        // Always in the application log, whether or not auditing is on.
        LOG.info("[TurnOnLoggers] " + user + " " + what + " " + logger
                + (level == null ? "" : "=" + level)
                + (hosts == null ? "" : " hosts=" + hosts)
                + (expires <= 0 ? "" : " until=" + new Date(expires))
                + (note == null || note.isEmpty() ? "" : " note=" + note));
        try {
            if (!Auditor.isEnabled(ACTION)) return;
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
            boolean written = Auditor.log(e, ctx);
            if (!written) ctx.saveObject(e);
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
