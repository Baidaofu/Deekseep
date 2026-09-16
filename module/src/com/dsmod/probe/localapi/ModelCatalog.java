package com.dsmod.probe.localapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maps public model identifiers onto native DeepSeek roles.
 *
 * <p>The stock catalogue exposes three routes that already exist inside the
 * host app:
 * <ul>
 *   <li>{@code default} - the normal chat model</li>
 *   <li>{@code expert} - the deep reasoning / expert model</li>
 *   <li>{@code vision} - the model used when images are attached</li>
 * </ul>
 * Users can append their own aliases through {@code dq0_custom_models.json}.
 */
public final class ModelCatalog {

    /** Well known stock identifiers advertised on {@code /v1/models}. */
    private static final String[][] STOCK = new String[][] {
            {"deepseek-chat", ApiContract.MODEL_DEFAULT},
            {"deepseek-reasoner", ApiContract.MODEL_EXPERT},
            {"deepseek-vision", ApiContract.MODEL_VISION},
    };

    /** Aliases accepted on input but not advertised, kept for compatibility. */
    private static final String[][] INPUT_ALIASES = new String[][] {
            {"deepseek-v4-flash", ApiContract.MODEL_DEFAULT},
            {"deepseek-v4-pro", ApiContract.MODEL_EXPERT},
            {"deepseek-r1", ApiContract.MODEL_EXPERT},
            {"reasoner", ApiContract.MODEL_EXPERT},
    };

    public static final class Entry {
        public final String id;
        public final String ownedBy;
        public final String role;

        Entry(String id, String ownedBy, String role) {
            this.id = id;
            this.ownedBy = ownedBy;
            this.role = role;
        }
    }

    private ModelCatalog() {
    }

    /**
     * Advertised model list.
     *
     * @param customModels raw entries from the user catalog, each either a
     *                     plain string or {@code {"id":..,"native_model":..}}
     * @param forceVisionWhenImages unused by the catalogue itself, kept for symmetry
     */
    public static List<Entry> advertised(List<Object> customModels) {
        List<Entry> out = new ArrayList<Entry>();
        for (String[] row : STOCK) {
            out.add(new Entry(row[0], "deepseek", row[1]));
        }
        for (Object raw : customModels) {
            Entry entry = fromCustom(raw);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Resolves an incoming model name to a native role.
     *
     * <p>Unknown names fall through to {@link ApiContract#MODEL_DEFAULT} so a
     * client that invents a model name still gets an answer instead of a 404.
     */
    public static String resolveRole(String requested, List<Object> customModels) {
        if (requested == null || requested.trim().isEmpty()) {
            return ApiContract.MODEL_DEFAULT;
        }
        String lower = requested.trim().toLowerCase(Locale.US);
        for (String[] row : STOCK) {
            if (row[0].equals(lower)) {
                return row[1];
            }
        }
        for (String[] row : INPUT_ALIASES) {
            if (row[0].equals(lower)) {
                return row[1];
            }
        }
        for (Object raw : customModels) {
            Entry entry = fromCustom(raw);
            if (entry != null && entry.id.equalsIgnoreCase(lower)) {
                return entry.role;
            }
        }
        return ApiContract.MODEL_DEFAULT;
    }

    /** True when the identifier requests background helper behaviour. */
    public static boolean isAuxiliary(String requested) {
        if (requested == null) {
            return false;
        }
        String lower = requested.toLowerCase(Locale.US);
        return lower.equals("deepseek-aux") || lower.startsWith("deepseek-aux-");
    }

    /** Advertised identifiers, unique and in insertion order. */
    public static Set<String> ids(List<Entry> entries) {
        Set<String> out = new LinkedHashSet<String>();
        for (Entry entry : entries) {
            out.add(entry.id);
        }
        return Collections.unmodifiableSet(out);
    }

    private static Entry fromCustom(Object raw) {
        if (raw == null) {
            return null;
        }
        String id = null;
        String role = ApiContract.MODEL_DEFAULT;
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            id = trimToNull(object.optString("id", null));
            String declared = trimToNull(object.optString("native_model", null));
            if (declared != null) {
                role = declared;
            }
        } else if (raw instanceof String) {
            id = trimToNull((String) raw);
        }
        if (id == null) {
            return null;
        }
        String normalised = role == null ? ApiContract.MODEL_DEFAULT
                : role.toLowerCase(Locale.US);
        if (!ApiContract.MODEL_DEFAULT.equals(normalised)
                && !ApiContract.MODEL_EXPERT.equals(normalised)
                && !ApiContract.MODEL_VISION.equals(normalised)) {
            normalised = ApiContract.MODEL_DEFAULT;
        }
        return new Entry(id, "deepseek", normalised);
    }

    /** Parses the user catalog into opaque entries the router can re-render. */
    public static List<Object> parseCustom(String json) {
        List<Object> out = new ArrayList<Object>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (fromCustom(value) != null) {
                    out.add(value);
                }
            }
        } catch (Throwable ignored) {
            // Malformed catalogs degrade to the stock list rather than failing startup.
        }
        return out;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
