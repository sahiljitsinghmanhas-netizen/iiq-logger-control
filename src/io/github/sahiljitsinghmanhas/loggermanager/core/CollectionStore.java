package io.github.sahiljitsinghmanhas.loggermanager.core;

import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.Custom;
import sailpoint.tools.GeneralException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Saved sets of loggers, shared by everyone who can use the plugin.
 *
 * Deliberately not per-user. The point of a collection is that the person who
 * worked out which five loggers matter for an LDAP bind failure can leave that
 * knowledge somewhere the next person finds it. A private favourites list would
 * defeat that.
 *
 * Stored in one Custom object, separate from the overrides, so saving a
 * collection never touches live logging and a collection outlives the override
 * it was captured from.
 */
public final class CollectionStore {

    public static final String OBJECT_NAME = "TurnOnLoggers Collections";

    public static final String A_COLLECTIONS = "collections";

    public static final String C_ID       = "id";
    public static final String C_NAME     = "name";
    public static final String C_DESC     = "description";
    public static final String C_LOGGERS  = "loggers";   // logger=LEVEL, comma separated
    public static final String C_CREATED  = "created";
    public static final String C_BY       = "createdBy";

    private static final int MAX_COLLECTIONS = 100;
    private static final int MAX_LOGGERS_EACH = 50;

    private CollectionStore() {
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> load(SailPointContext ctx) throws GeneralException {
        List<Map<String, String>> out = new ArrayList<>();
        Custom c = ctx.getObjectByName(Custom.class, OBJECT_NAME);
        if (c == null) return out;
        Object raw = c.get(A_COLLECTIONS);
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                    row.put(String.valueOf(e.getKey()),
                            e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static void save(SailPointContext ctx, List<Map<String, String>> all) throws GeneralException {
        Custom c = ctx.getObjectByName(Custom.class, OBJECT_NAME);
        if (c == null) {
            c = new Custom();
            c.setName(OBJECT_NAME);
            c.setAttributes(new Attributes<String, Object>());
        }
        if (c.getAttributes() == null) c.setAttributes(new Attributes<String, Object>());
        c.put(A_COLLECTIONS, new ArrayList<Object>(all));
        ctx.saveObject(c);
        ctx.commitTransaction();
    }

    /**
     * @param loggers logger -> level, already validated by the caller
     * @return the stored collection
     */
    public static Map<String, String> add(SailPointContext ctx, String name, String description,
                                          Map<String, String> loggers, String user)
            throws GeneralException {
        List<Map<String, String>> all = load(ctx);


        // Saving under an existing name replaces it, so refining a collection
        // does not leave three near-identical copies behind.
        for (int i = all.size() - 1; i >= 0; i--) {
            if (name.equalsIgnoreCase(all.get(i).get(C_NAME))) all.remove(i);
        }

        Map<String, String> row = new LinkedHashMap<>();
        row.put(C_ID, UUID.randomUUID().toString());
        row.put(C_NAME, name);
        row.put(C_DESC, description == null ? "" : description);
        row.put(C_LOGGERS, encode(loggers));
        row.put(C_CREATED, String.valueOf(System.currentTimeMillis()));
        row.put(C_BY, user == null ? "" : user);
        all.add(row);

        while (all.size() > MAX_COLLECTIONS) all.remove(0);
        save(ctx, all);
        return row;
    }

    /** The stored "logger=LEVEL,logger=LEVEL" form. */
    private static String encode(Map<String, String> loggers) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : loggers.entrySet()) {
            if (++n > MAX_LOGGERS_EACH) break;
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append("=")
              .append(String.valueOf(e.getValue()).toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Change an existing collection in place.
     *
     * Deliberately not add() with the same name. add() drops any collection
     * that shares the name and appends a new row, which would hand the
     * collection a new id and a new created date every time somebody removed a
     * logger from it - so "saved by X, 7d ago" would silently become "saved by
     * whoever edited it last, just now", and any link or audit line naming the
     * old id would dangle. Editing keeps the identity and the provenance; only
     * the contents change.
     *
     * @return the updated row, or null if no collection has that id.
     */
    public static Map<String, String> update(SailPointContext ctx, String id, String name,
                                             String description, Map<String, String> loggers)
            throws GeneralException {
        List<Map<String, String>> all = load(ctx);
        for (Map<String, String> c : all) {
            if (id == null || !id.equals(c.get(C_ID))) continue;
            if (name != null) c.put(C_NAME, name);
            if (description != null) c.put(C_DESC, description);
            if (loggers != null) c.put(C_LOGGERS, encode(loggers));
            save(ctx, all);
            return c;
        }
        return null;
    }

    public static Map<String, String> byId(SailPointContext ctx, String id) throws GeneralException {
        for (Map<String, String> c : load(ctx)) {
            if (id != null && id.equals(c.get(C_ID))) return c;
        }
        return null;
    }

    public static boolean remove(SailPointContext ctx, String id) throws GeneralException {
        List<Map<String, String>> all = load(ctx);
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (id != null && id.equals(all.get(i).get(C_ID))) {
                all.remove(i);
                removed = true;
            }
        }
        if (removed) save(ctx, all);
        return removed;
    }

    /** Parse the stored "logger=LEVEL,logger=LEVEL" form back into pairs. */
    public static List<Map<String, String>> parse(String encoded) {
        List<Map<String, String>> out = new ArrayList<>();
        if (encoded == null) return out;
        for (String item : encoded.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("logger", s.substring(0, eq).trim());
            m.put("level", s.substring(eq + 1).trim().toUpperCase(Locale.ROOT));
            out.add(m);
        }
        return out;
    }
}
