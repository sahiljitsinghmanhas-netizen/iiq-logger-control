package com.example.turnonloggers.service;

import com.example.turnonloggers.core.HostFacts;
import com.example.turnonloggers.core.LoggerSync;
import org.apache.log4j.Logger;
import sailpoint.api.SailPointContext;
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
