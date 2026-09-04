package io.github.sahiljitsinghmanhas.loggermanager.core;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the tail of a log file on the host serving the request.
 *
 * The obvious worry with reading files across a mixed Windows / Linux / macOS
 * cluster is paths, and the answer is that this class never constructs one.
 * Every candidate path comes from {@link HostFacts#logFilePaths()}, which reads
 * the file names off the appenders in that host's own live log4j2
 * configuration. So the path is whatever that host's own config says, spelled
 * in that host's own syntax, and java.io does the rest. There is nothing to
 * branch on.
 *
 * That is also the security model. The API takes an <em>index</em> into the
 * host's own list, never a path, so there is no traversal to defend against:
 * a caller cannot name a file, only pick one of the files this host already
 * writes to. Anything not in that list is unreachable.
 *
 * Reads are bounded and taken from the end with a seek, so a multi-gigabyte
 * log costs the same as a small one and nothing is ever loaded whole.
 */
public final class LogTail {

    private static final org.apache.log4j.Logger LOG =
            org.apache.log4j.Logger.getLogger(LogTail.class);

    /** Hard ceiling regardless of what the caller or the setting asks for. */
    public static final int MAX_KB = 512;

    /** How much of the end of the file a cluster search looks through. */
    public static final int SEARCH_KB = 256;
    /** Matching lines kept per host. Enough to see a pattern, small enough to store. */
    public static final int SEARCH_MAX_LINES = 40;
    /** Ceiling on a raw tail, whatever the caller asks for - it travels through the database. */
    public static final int TAIL_MAX_LINES = 300;
    /** Long lines are truncated - a stack trace line can be enormous. */
    public static final int SEARCH_MAX_CHARS = 400;

    private LogTail() {
    }

    /** The files this host writes to, as the UI's picker. */
    public static List<Map<String, Object>> files() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> paths = HostFacts.logFilePaths();
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("path", p);
            File f = new File(p);
            boolean readable = false;
            long size = -1L;
            try {
                readable = f.isFile() && f.canRead();
                if (readable) size = f.length();
            } catch (Throwable t) {
                readable = false;
            }
            m.put("readable", readable);
            m.put("bytes", size);
            out.add(m);
        }
        return out;
    }

    public static final class Result {
        public String path = "";
        public long fileBytes = -1;
        public long readBytes = 0;
        public boolean truncated = false;
        public List<String> lines = new ArrayList<>();
        public String error = null;
    }

    /**
     * @param index which of this host's log files, as reported by {@link #files()}
     * @param kb    how much of the end to read, clamped to {@link #MAX_KB}
     */
    /**
     * What one host has to say about the current request.
     *
     * The lines and the failure are kept apart deliberately. They used to be
     * merged - a host that could not read its file returned the error as though
     * it were a line of log - which made "this host matched nothing" and "this
     * host is broken" indistinguishable to anything downstream. On a cluster
     * search most hosts legitimately match nothing, so that distinction is the
     * whole point of the answer.
     */
    public static final class Answer {
        public final List<String> lines = new ArrayList<>();
        public String error = null;
    }

    /**
     * Lines from the end of this host's first log file containing {@code text}.
     *
     * Used by the cluster-wide search: every host runs this against its own
     * file on its own sync tick and publishes the result, because no host can
     * read another one's disk. Bounded hard - a fixed search window, a capped
     * number of lines, and each line truncated - because the result is stored
     * in a Custom object rather than streamed.
     */
    public static Answer search(String text) {
        Answer a = new Answer();
        if (text == null || text.trim().isEmpty()) return a;
        String needle = text.trim().toLowerCase(java.util.Locale.ROOT);

        Result r = tail(0, SEARCH_KB);
        if (r.error != null) {
            a.error = r.error;
            return a;
        }
        for (String line : r.lines) {
            if (line == null) continue;
            if (line.toLowerCase(java.util.Locale.ROOT).indexOf(needle) < 0) continue;
            a.lines.add(line.length() > SEARCH_MAX_CHARS
                    ? line.substring(0, SEARCH_MAX_CHARS) + " ..." : line);
        }
        // Keep the most recent, which is what anyone chasing a live problem wants.
        while (a.lines.size() > SEARCH_MAX_LINES) a.lines.remove(0);
        return a;
    }

    /**
     * The last {@code lines} lines of this host's first log file.
     *
     * The counterpart to {@link #search}: same bounded shape, same publishing
     * path, but no filter - for when the search finds nothing and you just want
     * to see what the host is actually writing.
     */
    public static Answer tailLines(int lines) {
        Answer a = new Answer();
        if (lines <= 0) lines = 40;
        if (lines > TAIL_MAX_LINES) lines = TAIL_MAX_LINES;

        Result r = tail(0, SEARCH_KB);
        if (r.error != null) {
            a.error = r.error;
            return a;
        }
        int from = Math.max(0, r.lines.size() - lines);
        for (int i = from; i < r.lines.size(); i++) {
            String line = r.lines.get(i);
            if (line == null) continue;
            a.lines.add(line.length() > SEARCH_MAX_CHARS
                    ? line.substring(0, SEARCH_MAX_CHARS) + " ..." : line);
        }
        return a;
    }

    public static Result tail(int index, int kb) {
        Result r = new Result();
        List<String> paths = HostFacts.logFilePaths();
        if (index < 0 || index >= paths.size()) {
            r.error = "No such log file on this host.";
            return r;
        }
        if (kb <= 0) kb = 64;
        if (kb > MAX_KB) kb = MAX_KB;

        r.path = paths.get(index);
        File f = new File(r.path);
        if (!f.isFile()) {
            r.error = "This host lists the file but it does not exist yet: " + r.path;
            return r;
        }
        if (!f.canRead()) {
            r.error = "The IIQ process cannot read " + r.path + ".";
            return r;
        }

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            r.fileBytes = len;
            long want = (long) kb * 1024L;
            long from = Math.max(0, len - want);
            r.truncated = from > 0;
            raf.seek(from);

            byte[] buf = new byte[(int) Math.min(want, len - from)];
            raf.readFully(buf);
            r.readBytes = buf.length;

            String text = new String(buf, StandardCharsets.UTF_8);
            String[] split = text.split("\r?\n", -1);
            // The first line is almost certainly cut in half by the seek, so
            // drop it rather than show a fragment that looks like corruption.
            int start = (r.truncated && split.length > 1) ? 1 : 0;
            for (int i = start; i < split.length; i++) {
                r.lines.add(split[i]);
            }
            // Trailing newline produces a final empty element.
            if (!r.lines.isEmpty() && r.lines.get(r.lines.size() - 1).isEmpty()) {
                r.lines.remove(r.lines.size() - 1);
            }
        } catch (Throwable t) {
            r.error = "Could not read " + r.path + ": " + t;
            LOG.warn("[TurnOnLoggers] log tail failed for " + r.path + ": " + t);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { /* closing */ }
            }
        }
        return r;
    }
}
