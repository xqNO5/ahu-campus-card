package org.json;

/** Shared value parsing for the JSONObject/JSONArray stubs. */
final class Json {

    private Json() {
    }

    static Object parseValue(String s, int start, int[] end) {
        char c = s.charAt(start);
        if (c == '{') {
            JSONObject o = new JSONObject();
            o.parse(s, start, end);
            return o;
        }
        if (c == '[') {
            JSONArray a = new JSONArray();
            a.parse(s, start, end);
            return a;
        }
        if (c == '"') {
            return parseString(s, start, end);
        }
        if (s.startsWith("true", start)) {
            end[0] = start + 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", start)) {
            end[0] = start + 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", start)) {
            end[0] = start + 4;
            return JSONObject.NULL;
        }
        int i = start;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        end[0] = i;
        if (num.isEmpty()) {
            throw new IllegalArgumentException("unexpected character at " + start);
        }
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    static String parseString(String s, int start, int[] end) {
        int i = start + 1;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 4;
                        break;
                    default: sb.append(n);
                }
                i += 2;
            } else if (c == '"') {
                end[0] = i + 1;
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }
}
