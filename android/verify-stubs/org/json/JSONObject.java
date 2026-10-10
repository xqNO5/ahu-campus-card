package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal stand-in for Android's org.json.JSONObject, enough for the offline verification
 * harness. Only the accessors the client uses are implemented.
 */
public class JSONObject {

    public static final Object NULL = new Object();

    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {
    }

    public JSONObject(String source) {
        parse(source, 0, new int[1]);
    }

    public int length() {
        return map.size();
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public String optString(String key) {
        return optString(key, "");
    }

    public String optString(String key, String fallback) {
        Object v = map.get(key);
        return v == null || v == NULL ? fallback : String.valueOf(v);
    }

    public String getString(String key) {
        return optString(key);
    }

    public int optInt(String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long optLong(String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? fallback : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double optDouble(String key, double fallback) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return v == null ? fallback : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public JSONObject optJSONObject(String key) {
        Object v = map.get(key);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray optJSONArray(String key) {
        Object v = map.get(key);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    void parse(String s, int start, int[] end) {
        int i = skip(s, start);
        if (i >= s.length() || s.charAt(i) != '{') {
            throw new IllegalArgumentException("not a JSON object");
        }
        i++;
        while (i < s.length()) {
            i = skip(s, i);
            if (s.charAt(i) == '}') {
                end[0] = i + 1;
                return;
            }
            int[] keyEnd = new int[1];
            String key = Json.parseString(s, i, keyEnd);
            i = skip(s, keyEnd[0]);
            i++; // ':'
            i = skip(s, i);
            int[] valueEnd = new int[1];
            map.put(key, Json.parseValue(s, i, valueEnd));
            i = skip(s, valueEnd[0]);
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        throw new IllegalArgumentException("unterminated object");
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
