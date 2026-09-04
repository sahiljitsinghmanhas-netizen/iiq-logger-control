package io.github.sahiljitsinghmanhas.loggermanager.core;

/**
 * The plugin version this class file was compiled from.
 *
 * Not read from the Plugin object, deliberately. That would tell you which
 * version is installed, and the whole point of this constant is to tell you
 * which version is <em>running</em> - which on a cluster is not always the
 * same thing.
 *
 * IdentityIQ's Servicer builds a service executor once and holds the instance.
 * Upgrading a plugin replaces the classes on disk but never rebuilds a service
 * that is already running, so a host keeps executing the code it loaded when it
 * last started until its JVM is restarted. On the host serving the page this
 * goes unnoticed, because the REST layer runs the current classes in-process
 * and covers for it. Every other host is running whatever it started with.
 *
 * That failure is silent and it does not look like what it is: the old code
 * still answers everything it always answered, so a host looks healthy and
 * simply ignores anything the release added. Publishing this constant in each
 * host's status is what lets the page say "this host is running 2.39.0, and
 * 2.49.2 is installed" instead of leaving somebody to wonder why one host
 * behaves differently from the others.
 *
 * Rewritten from manifest.xml by build.bat on every build, so it cannot drift.
 */
public final class Build {

    public static final String VERSION = "2.51.0";

    private Build() { }
}
