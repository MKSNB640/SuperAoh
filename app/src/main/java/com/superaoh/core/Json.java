package com.superaoh.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析与序列化。
 *
 * 为什么自己写：
 *   本工程刻意不依赖任何第三方库（无 Gradle、无 OkHttp、无 Gson），
 *   只靠 javac + D8 就能构建。而 AI Agent 必须解析 OpenAI 兼容接口返回的
 *   choices[].message.tool_calls[].function.arguments（JSON 里套 JSON 字符串），
 *   所以需要一个可靠的小型解析器。
 *
 * 映射规则：
 *   object  -> LinkedHashMap<String,Object>（保持字段顺序，便于调试）
 *   array   -> ArrayList<Object>
 *   string  -> String
 *   number  -> Double
 *   true/false -> Boolean
 *   null    -> null
 *
 * 只做标准 JSON（RFC 8259），不接受注释与尾逗号，遇到非法输入抛 JsonException。
 */
public final class Json {

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private Json() {
    }

    // ============================================================
    // 解析
    // ============================================================

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object v = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("JSON 末尾有多余内容，位置 " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("期望 JSON 对象");
        }
        return (Map<String, Object>) v;
    }

    static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("JSON 意外结束");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("期望 " + word + "，位置 " + pos);
            }
            pos += word.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') {
                    throw new JsonException("对象键必须是字符串，位置 " + pos);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') {
                    throw new JsonException("缺少冒号，位置 " + pos);
                }
                pos++;
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("对象未闭合");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new JsonException("对象内期望 , 或 }，位置 " + pos);
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("数组未闭合");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new JsonException("数组内期望 , 或 ]，位置 " + pos);
            }
        }

        private String readString() {
            pos++; // 开引号
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("转义符不完整");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u': {
                        if (pos + 4 > s.length()) {
                            throw new JsonException("\\u 转义不完整");
                        }
                        String hex = s.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("非法 \\u 转义: " + hex);
                        }
                        break;
                    }
                    default:
                        throw new JsonException("未知转义: \\" + e);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (!atEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new JsonException("非法字符 '" + s.charAt(pos) + "'，位置 " + pos);
            }
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字: " + s.substring(start, pos));
            }
        }
    }

    // ============================================================
    // 序列化
    // ============================================================

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            writeString(sb, (String) v);
            return;
        }
        if (v instanceof Boolean) {
            sb.append(v.toString());
            return;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        writeString(sb, v.toString());
    }

    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ============================================================
    // 便捷读取
    // ============================================================

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) parent).get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) parent).get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static String str(Object parent, String key) {
        if (!(parent instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) parent).get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static int intVal(Object parent, String key, int def) {
        if (!(parent instanceof Map)) {
            return def;
        }
        Object v = ((Map<String, Object>) parent).get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return def;
    }

    /**
     * long 版取值。
     *
     * 单独写而不是复用 intVal：解析出来的数字可能是 Long（JSON 里大整数），
     * 走 intVal 再转 long 在超 21 亿毫秒时会溢出，虽然实际不会到那个量级，
     * 但时间戳这类字段多一道保险更好。
     */
    public static long longVal(Object parent, String key, long def) {
        if (!(parent instanceof Map)) {
            return def;
        }
        Object v = ((Map<String, Object>) parent).get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return def;
    }

    /** 按路径取值，例如 path(root, "choices", "0", "message")。 */
    @SuppressWarnings("unchecked")
    public static Object path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(k);
            } else if (cur instanceof List) {
                try {
                    cur = ((List<Object>) cur).get(Integer.parseInt(k));
                } catch (Throwable t) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur;
    }
}