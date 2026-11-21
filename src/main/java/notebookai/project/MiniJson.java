package notebookai.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON reader/writer for the project file (no external library, no object
 * deserialization). Values: {@code Map<String,Object>} (insertion ordered), {@code List<Object>},
 * {@code String}, {@code Long} (integer literals), {@code Double}, {@code Boolean}, {@code null}.
 *
 * <p>Strictness: RFC 8259 grammar only, depth limit {@link #MAX_DEPTH}, duplicate object keys
 * rejected, trailing content rejected, raw control characters in strings rejected. Text round-trips
 * exactly: every non-printable or unpaired-surrogate char is written as a {@code \}u escape, and
 * the parser restores it verbatim, so arbitrary Java strings (newlines, CRLF, emoji, lone
 * surrogates) survive unchanged.
 */
public final class MiniJson {

    public static final int MAX_DEPTH = 64;

    private MiniJson() {}

    /** Thrown for malformed or disallowed JSON. */
    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("Unexpected content after the JSON value at position " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonException err(String m) {
            return new JsonException(m + " at position " + pos);
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw err("Nesting deeper than " + MAX_DEPTH);
            }
            if (pos >= s.length()) {
                throw err("Unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw err("Unexpected character '" + c + "'");
            }
        }

        Object literal(String word, Object val) throws JsonException {
            if (!s.startsWith(word, pos)) {
                throw err("Invalid literal");
            }
            pos += word.length();
            return val;
        }

        Map<String, Object> object(int depth) throws JsonException {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw err("Expected a string key");
                }
                String k = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                Object v = value(depth + 1);
                if (m.containsKey(k)) {
                    throw err("Duplicate key \"" + k + "\"");
                }
                m.put(k, v);
                skipWs();
                if (pos >= s.length()) {
                    throw err("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    pos--;
                    throw err("Expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) throws JsonException {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw err("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    pos--;
                    throw err("Expected ',' or ']'");
                }
            }
        }

        String string() throws JsonException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw err("Raw control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw err("Unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw err("Truncated \\u escape");
                        }
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) {
                                throw err("Invalid \\u escape");
                            }
                            cp = cp * 16 + d;
                        }
                        pos += 4;
                        sb.append((char) cp);
                        break;
                    default:
                        pos--;
                        throw err("Invalid escape '\\" + e + "'");
                }
            }
        }

        Object number() throws JsonException {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length()) {
                throw err("Invalid number");
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
                    pos++;
                }
            } else {
                throw err("Invalid number");
            }
            boolean integer = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integer = false;
                pos++;
                int d = pos;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                }
                if (pos == d) {
                    throw err("Invalid number fraction");
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integer = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int d = pos;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                }
                if (pos == d) {
                    throw err("Invalid number exponent");
                }
            }
            String t = s.substring(start, pos);
            try {
                if (integer) {
                    return Long.parseLong(t);
                }
                double d = Double.parseDouble(t);
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw err("Number out of range");
                }
                return d;
            } catch (NumberFormatException e) {
                throw err("Number out of range");
            }
        }
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) throws JsonException {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw new JsonException("Nesting deeper than " + MAX_DEPTH);
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("Cannot write a non-finite number");
            }
            sb.append(Double.toString(d));
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new JsonException("Object keys must be strings");
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, (String) e.getKey());
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i), depth + 1);
            }
            sb.append(']');
        } else {
            throw new JsonException("Unsupported value type " + v.getClass().getSimpleName());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    boolean lone = Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                                    && Character.isLowSurrogate(s.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0
                                    && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || c == 0x7f || lone || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
