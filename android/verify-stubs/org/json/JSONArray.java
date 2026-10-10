package org.json;

import java.util.ArrayList;
import java.util.List;

/** Minimal stand-in for Android's org.json.JSONArray. */
public class JSONArray {

    private final List<Object> items = new ArrayList<>();

    public JSONArray() {
    }

    public int length() {
        return items.size();
    }

    public JSONObject optJSONObject(int index) {
        Object v = index >= 0 && index < items.size() ? items.get(index) : null;
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public String optString(int index) {
        Object v = index >= 0 && index < items.size() ? items.get(index) : null;
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    public Object get(int index) {
        return items.get(index);
    }

    void add(Object value) {
        items.add(value);
    }

    void parse(String s, int start, int[] end) {
        int i = start + 1;
        while (i < s.length()) {
            i = skip(s, i);
            if (s.charAt(i) == ']') {
                end[0] = i + 1;
                return;
            }
            int[] valueEnd = new int[1];
            items.add(Json.parseValue(s, i, valueEnd));
            i = valueEnd[0];
            i = skip(s, i);
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        throw new IllegalArgumentException("unterminated array");
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
