package io.github.sahiljitsinghmanhas.loggermanager.core;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Describes the JVM/OS this code is running on.
 *
 * An IIQ cluster is routinely heterogeneous - a Windows dev box, RHEL app
 * servers in prod, a macOS laptop, a Linux container. Each host reports these
 * facts into its own status object so the UI can show you what you are
 * actually pointing a logger at, including where that host writes its log
 * files (which is the one thing that genuinely differs per OS).
 *
 * Nothing here branches on OS to decide behaviour - the level-setting path is
 * identical everywhere. The OS is reported, not acted on.
 */
public final class HostFacts {

    private HostFacts() {
    }

    /**
     * IIQ's name for this host. This is the same value IIQ's HeartbeatService
     * uses to name its Server objects, so it lines up with the host list the
     * UI shows. Honours -Diiq.hostname when an admin has overridden it (common
     * in containers, where the generated hostname is a throwaway id).
     */
    public static String hostName() {
        try {
            String n = sailpoint.tools.Util.getHostName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Throwable ignored) {
            return "unknown-host";
        }
    }

    /** windows | linux | macos | aix | solaris | other - for UI grouping only. */
    public static String osFamily() {
        String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        if (os.contains("aix")) return "aix";
        if (os.contains("sunos") || os.contains("solaris")) return "solaris";
        return "other";
    }

    public static Map<String, String> collect() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", hostName());
        m.put("osFamily", osFamily());
        m.put("os", prop("os.name") + " " + prop("os.version"));
        m.put("arch", prop("os.arch"));
        m.put("java", prop("java.version") + " (" + prop("java.vm.name") + ")");
        m.put("jvmVendor", prop("java.vendor"));
        m.put("userTimezone", java.util.TimeZone.getDefault().getID());
        m.put("fileSeparator", System.getProperty("file.separator", "?"));
        m.put("containerHint", String.valueOf(looksContainerised()));
        m.put("log4jConfig", Log4jAgent.configurationSource());
        m.put("logFiles", join(logFilePaths()));
        return m;
    }

    /**
     * Absolute paths of the file-based appenders configured on this host, as
     * that host's OS spells them. This is the practical follow-up question
     * after "turn the logger on" - "...so where do I read it?" - and the
     * answer is different on every OS.
     */
    public static List<String> logFilePaths() {
        List<String> out = new ArrayList<>();
        try {
            LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager
                    .getContext(sailpoint.api.SailPointContext.class.getClassLoader(), false);
            Configuration cfg = ctx.getConfiguration();
            for (Map.Entry<String, Appender> e : cfg.getAppenders().entrySet()) {
                String path = fileNameOf(e.getValue());
                if (path != null && !path.isEmpty() && !out.contains(path)) {
                    out.add(path);
                }
            }
        } catch (Throwable ignored) {
            // best effort - never let diagnostics break the apply path
        }
        return out;
    }

    /**
     * FileAppender, RollingFileAppender, RandomAccessFileAppender and friends
     * all expose getFileName() but share no common interface that declares it,
     * so reflection is the least brittle way to ask.
     */
    private static String fileNameOf(Appender appender) {
        if (appender == null) return null;
        try {
            Method m = appender.getClass().getMethod("getFileName");
            Object v = m.invoke(appender);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Rough "am I in a container" hint. Only used to label the host in the UI
     * so you know a filesystem path there is probably ephemeral.
     */
    private static boolean looksContainerised() {
        try {
            if (new java.io.File("/.dockerenv").exists()) return true;
            java.io.File cgroup = new java.io.File("/proc/1/cgroup");
            if (cgroup.canRead()) {
                for (String line : java.nio.file.Files.readAllLines(cgroup.toPath())) {
                    if (line.contains("docker") || line.contains("kubepods") || line.contains("containerd")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Windows / restricted JVM - not containerised in the sense we mean
        }
        return false;
    }

    private static String prop(String key) {
        return String.valueOf(System.getProperty(key, "?"));
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(v);
        }
        return sb.toString();
    }
}
