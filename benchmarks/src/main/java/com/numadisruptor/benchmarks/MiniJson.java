package com.numadisruptor.benchmarks;

import java.util.Iterator;
import java.util.Map;

/** Minimal JSON writer for result records (maps, lists, strings, numbers, booleans, null). No parsing, no dependency. */
final class MiniJson {
    private MiniJson() { }

    static String write(final Object value) {
        final StringBuilder sb = new StringBuilder(256);
        write(value, sb);
        return sb.toString();
    }

    private static void write(final Object v, final StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(s, sb);
            case Boolean b -> sb.append(b);
            case Double d -> number(d, sb);
            case Float f -> number(f.doubleValue(), sb);
            case Number n -> sb.append(n);
            case Enum<?> e -> string(e.name(), sb);
            case Map<?, ?> m -> {
                sb.append('{');
                final Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry<?, ?> e = it.next();
                    string(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                final Iterator<?> it = list.iterator();
                while (it.hasNext()) {
                    write(it.next(), sb);
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append(']');
            }
            case int[] a -> {
                sb.append('[');
                for (int i = 0; i < a.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(a[i]);
                }
                sb.append(']');
            }
            default -> string(v.toString(), sb);
        }
    }

    private static void number(final double d, final StringBuilder sb) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null"); // JSON has no NaN/Infinity
        } else {
            sb.append(d);
        }
    }

    private static void string(final String s, final StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses one JSON object (as produced by {@link #write}) into nested LinkedHashMap/ArrayList/Long/Double/String/Boolean/null. */
    static java.util.Map<String, Object> parseObject(final String json) {
        final Parser p = new Parser(json);
        final Object v = p.value();
        p.skipWs();
        if (!(v instanceof java.util.Map) || p.i != json.length()) {
            throw new IllegalArgumentException("not a single JSON object");
        }
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Object> m = (java.util.Map<String, Object>) v;
        return m;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(final String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            skipWs();
            final char c = s.charAt(i);
            if (c == '{') {
                i++;
                final java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                skipWs();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skipWs();
                    final String k = str();
                    skipWs();
                    expect(':');
                    m.put(k, value());
                    skipWs();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            if (c == '[') {
                i++;
                final java.util.List<Object> l = new java.util.ArrayList<>();
                skipWs();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skipWs();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            final int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            final String num = s.substring(st, i);
            if (num.isEmpty()) {
                throw new IllegalArgumentException("bad JSON at " + st);
            }
            return num.contains(".") || num.contains("e") || num.contains("E") ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
        }

        void expect(final char c) {
            if (s.charAt(i++) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
            }
        }

        String str() {
            expect('"');
            final StringBuilder sb = new StringBuilder();
            while (true) {
                final char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == 92) { // backslash
                    final char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
