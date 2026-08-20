package io.github.sahiljitsinghmanhas.loggermanager.service;

import io.github.sahiljitsinghmanhas.loggermanager.core.HostFacts;
import io.github.sahiljitsinghmanhas.loggermanager.core.LoggerSync;
import org.apache.log4j.Logger;
import sailpoint.api.SailPointContext;
import sailpoint.object.ServiceDefinition;
import sailpoint.server.Service;

/**
 * The piece that makes this a cluster feature rather than a single-host toy.
 *
 * The ServiceDefinition ships with hosts="global", so IIQ starts one instance
 * of this service in every JVM in the deployment - Windows, Linux, macOS,
 * container, does not matter. Each instance independently reconciles its own
 * log4j2 runtime against the shared configuration in the database.
 *
 * That gives three properties for free:
 *
 *   propagation - a change made in the UI on host A reaches host B within one
 *                 interval (30s by default), with no host-to-host networking,
 *                 no SSH, no message bus, and no shared filesystem.
 *   durability  - a JVM that restarts re-applies the current desired state on
 *                 its first tick, so overrides survive a bounce without ever
 *                 editing log4j2.properties.
 *   expiry      - a TTL'd entry stops being part of the desired state the
 *                 moment it lapses, and the next tick reverts it. Debug
 *                 logging left on by accident turns itself back off.
 *
 * execute() must never throw: IIQ's Servicer logs a stack trace per tick and
 * can suspend a service that keeps failing.
 */
public class LoggerSyncService extends Service {

    private static final Logger LOG = Logger.getLogger(LoggerSyncService.class);

    public static final String NAME = "TurnOnLoggersSync";

    private boolean announced = false;

    /** Checked once per JVM, not on every tick. */
    private static volatile boolean executorChecked = false;

    /**
     * Point the ServiceDefinition at the class that actually exists.
     *
     * The ServiceDefinition is imported by hand and names its executor as a
     * string, so it survives plugin upgrades untouched. If a release ever moves
     * or renames this class, every host keeps trying to load the old name and
     * IIQ's Servicer fails to install the service - once per cycle, forever,
     * with the plugin still looking installed. Nothing reconciles after that,
     * and because a running Servicer holds the instance it already built, the
     * breakage does not appear until the next restart. That is a long time to
     * wait to find out.
     *
     * So this corrects it. Only ever rewrites this plugin's own definition, and
     * only when the executor does not match the class doing the correcting.
     *
     * Called from the REST layer rather than from a tick, because if the
     * executor is wrong there are no ticks to call it.
     */
    public static void ensureExecutor(SailPointContext ctx) {
        if (executorChecked) return;
        executorChecked = true;
        try {
            ServiceDefinition sd = ctx.getObjectByName(ServiceDefinition.class, NAME);
            if (sd == null) return;   // never imported; nothing to correct
            String want = LoggerSyncService.class.getName();
            String have = sd.getExecutor();
            if (want.equals(have)) return;
            sd.setExecutor(want);
            ctx.saveObject(sd);
            ctx.commitTransaction();
            LOG.warn("[TurnOnLoggers] ServiceDefinition " + NAME + " pointed at '" + have
                    + "', which this build does not contain. Repointed it at '" + want
                    + "'. Hosts pick this up on their next Servicer cycle.");
        } catch (Throwable t) {
            // Never let this stop the page loading - a stale executor is bad
            // but a plugin that will not render is worse.
            LOG.warn("[TurnOnLoggers] could not check the ServiceDefinition executor: " + t);
        }
    }

    @Override
    public void execute(SailPointContext ctx) {
        try {
            if (!announced) {
                LOG.info("[TurnOnLoggers] sync service started on host '" + HostFacts.hostName()
                        + "' (" + HostFacts.collect().get("os") + "), log4j2 config: "
                        + HostFacts.collect().get("log4jConfig"));
                announced = true;
            }
            LoggerSync.run(ctx, "service");
        } catch (Throwable t) {
            LOG.warn("[TurnOnLoggers] sync tick failed on " + HostFacts.hostName() + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }
}
