/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Valve KeyValues reader. PICS appinfo comes as <b>text</b> KeyValues
 * ("appinfo" { ... }); other messages use the binary form (type byte, key,
 * value). Both are accepted.
 */
final class KeyValues {
    static Map<String, Object> parse(byte[] data) throws SteamException {
        if (isText(data)) {
            return new TextParser(data).parse();
        }
        return parseBinary(data);
    }

    private static boolean isText(byte[] data) {
        for (byte value : data) {
            if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
                continue;
            }
            return value == '"' || value == '{';
        }
        return false;
    }

    /* ---- text KeyValues ---- */

    private static final class TextParser {
        final byte[] data;
        int pos;

        TextParser(byte[] data) {
            this.data = data;
        }

        Map<String, Object> parse() throws SteamException {
            Map<String, Object> root = new LinkedHashMap<>();
            object(root);
            return root;
        }

        private void object(Map<String, Object> out) throws SteamException {
            while (true) {
                skip();
                if (pos >= data.length) {
                    return;
                }
                if (data[pos] == '}') {
                    pos++;
                    return;
                }
                String key = quoted();
                if (key == null) {
                    throw new SteamException("VDF text: expected key at " + pos);
                }
                skip();
                if (pos < data.length && data[pos] == '{') {
                    pos++;
                    Map<String, Object> child = new LinkedHashMap<>();
                    object(child);
                    out.put(key, child);
                } else {
                    String value = value();
                    out.put(key, value == null ? "" : value);
                }
            }
        }

        private String value() {
            skip();
            if (pos < data.length && data[pos] == '"') {
                return quoted();
            }
            return bare();
        }

        private String quoted() {
            if (pos >= data.length || data[pos] != '"') {
                return null;
            }
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < data.length) {
                char c = (char) (data[pos++] & 0xFF);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && pos < data.length) {
                    char e = (char) (data[pos++] & 0xFF);
                    switch (e) {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case 'r': out.append('\r'); break;
                        default: out.append(e); break;
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private String bare() {
            int start = pos;
            while (pos < data.length) {
                char c = (char) (data[pos] & 0xFF);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '}') {
                    break;
                }
                pos++;
            }
            return new String(data, start, pos - start, StandardCharsets.UTF_8);
        }

        private void skip() {
            while (pos < data.length) {
                char c = (char) (data[pos] & 0xFF);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == 0) {
                    pos++;
                } else if (c == '/' && pos + 1 < data.length && data[pos + 1] == '/') {
                    pos += 2;
                    while (pos < data.length && data[pos] != '\n') pos++;
                } else if (c == '/' && pos + 1 < data.length && data[pos + 1] == '*') {
                    pos += 2;
                    while (pos + 1 < data.length
                            && !(data[pos] == '*' && data[pos + 1] == '/')) pos++;
                    pos = Math.min(data.length, pos + 2);
                } else if (c == '[') {
                    // platform conditional suffix, e.g. [$WIN32]
                    while (pos < data.length && data[pos] != ']') pos++;
                    if (pos < data.length) pos++;
                } else if (c == '#') {
                    // #base/#include are not expected in PICS; ignore the line
                    while (pos < data.length && data[pos] != '\n') pos++;
                } else {
                    return;
                }
            }
        }
    }

    /* ---- binary KeyValues ---- */

    private static Map<String, Object> parseBinary(byte[] data) throws SteamException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Object> root = new LinkedHashMap<>();
        readBinaryObject(buffer, root, data.length);
        return root;
    }

    private static void readBinaryObject(ByteBuffer buffer, Map<String, Object> out, int limit)
            throws SteamException {
        while (buffer.position() < limit) {
            int type = buffer.get() & 0xFF;
            if (type == 8 || type == 11) {
                return;
            }
            String key = readBinaryString(buffer, limit);
            if (key == null) {
                throw new SteamException("VDF: missing key");
            }
            switch (type) {
                case 0: {
                    Map<String, Object> child = new LinkedHashMap<>();
                    readBinaryObject(buffer, child, limit);
                    out.put(key, child);
                    break;
                }
                case 1:
                    out.put(key, readBinaryString(buffer, limit));
                    break;
                case 2:
                case 4:
                case 6:
                    out.put(key, buffer.getInt());
                    break;
                case 3:
                    out.put(key, buffer.getFloat());
                    break;
                case 7:
                case 10:
                    out.put(key, buffer.getLong());
                    break;
                default:
                    throw new SteamException("VDF: unsupported type " + type
                            + " at " + (buffer.position() - 1)
                            + " near " + hex(buffer.array(),
                            Math.max(0, buffer.position() - 17), 32));
            }
        }
    }

    private static String readBinaryString(ByteBuffer buffer, int limit) {
        int start = buffer.position();
        int end = start;
        while (end < limit && buffer.get(end) != 0) {
            end++;
        }
        if (end >= limit) {
            return null;
        }
        byte[] bytes = new byte[end - start];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = buffer.get(start + i);
        }
        buffer.position(end + 1);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /* ---- accessors ---- */

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseUnsignedLong((String) value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0;
        }
        if (value instanceof String) {
            return "1".equals(value) || "true".equalsIgnoreCase((String) value);
        }
        return false;
    }

    private static String hex(byte[] data, int offset, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = offset; i < Math.min(data.length, offset + count); i++) {
            out.append(String.format("%02x", data[i] & 0xFF));
        }
        return out.toString();
    }

    private KeyValues() {
    }
}
