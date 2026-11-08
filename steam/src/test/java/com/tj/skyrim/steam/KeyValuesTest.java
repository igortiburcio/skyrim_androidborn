package com.tj.skyrim.steam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

/** Valve KeyValues, text (PICS appinfo) and binary forms. */
public class KeyValuesTest {

    @Test
    public void parsesTheTextTree() throws Exception {
        String vdf = "\"appinfo\"\n"
                + "{\n"
                + "  \"appid\" \"72850\"\n"
                + "  \"common\" { \"name\" \"The Elder Scrolls V: Skyrim\" }\n"
                + "  \"depots\" { \"1\" { \"manifests\" { \"public\" { \"gid\" \"1234567890\" } } } }\n"
                + "}\n";

        Map<String, Object> root = KeyValues.parse(utf8(vdf));
        Map<String, Object> app = KeyValues.map(root.get("appinfo"));

        assertEquals("72850", KeyValues.string(app.get("appid")));
        assertEquals("The Elder Scrolls V: Skyrim",
                KeyValues.string(KeyValues.map(app.get("common")).get("name")));
        Map<String, Object> depot = KeyValues.map(KeyValues.map(app.get("depots")).get("1"));
        Map<String, Object> pub = KeyValues.map(KeyValues.map(depot.get("manifests")).get("public"));
        assertEquals(1234567890L, KeyValues.number(pub.get("gid")));
    }

    @Test
    public void parsesEscapesCommentsAndConditionals() throws Exception {
        String vdf = "\"root\"\n"
                + "{\n"
                + "  // a line comment\n"
                + "  \"escaped\" \"line\\nnext\\tend\"\n"
                + "  /* a block comment */\n"
                + "  [$WIN32]\n"
                + "  \"platform\" \"win\"\n"
                + "  #base \"ignored.vdf\"\n"
                + "  \"empty\" \"\"\n"
                + "  \"bare\" 42\n"
                + "}\n";

        Map<String, Object> root = KeyValues.map(KeyValues.parse(utf8(vdf)).get("root"));

        assertEquals("line\nnext\tend", KeyValues.string(root.get("escaped")));
        assertEquals("win", KeyValues.string(root.get("platform")));
        assertEquals("", KeyValues.string(root.get("empty")));
        assertEquals("42", KeyValues.string(root.get("bare")));
        assertFalse(root.containsKey("base"));
    }

    @Test
    public void parsesTheBinaryTree() throws Exception {
        byte[] data = obj("appinfo",
                str("name", "value"),
                integer("number", 7));

        Map<String, Object> root = KeyValues.parse(data);
        Map<String, Object> app = KeyValues.map(root.get("appinfo"));

        assertEquals("value", KeyValues.string(app.get("name")));
        assertEquals(7L, KeyValues.number(app.get("number")));
    }

    @Test(expected = SteamException.class)
    public void rejectsAnUnknownBinaryType() throws Exception {
        KeyValues.parse(new byte[] {9});
    }

    @Test
    public void accessorsHandleMissingAndUnsignedValues() {
        assertTrue(KeyValues.map(null).isEmpty());
        assertEquals("", KeyValues.string(null));
        assertEquals(0L, KeyValues.number(null));
        assertEquals(0L, KeyValues.number("not a number"));
        assertEquals(-1L, KeyValues.number("18446744073709551615"));

        assertTrue(KeyValues.bool("1"));
        assertTrue(KeyValues.bool("true"));
        assertTrue(KeyValues.bool(2));
        assertFalse(KeyValues.bool("0"));
        assertFalse(KeyValues.bool(null));
    }

    /* ------------------------------------------------------------------ */

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] obj(String key, byte[]... children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        writeZ(out, key);
        for (byte[] child : children) {
            out.write(child, 0, child.length);
        }
        out.write(8);
        return out.toByteArray();
    }

    private static byte[] str(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        writeZ(out, key);
        writeZ(out, value);
        return out.toByteArray();
    }

    private static byte[] integer(String key, int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        writeZ(out, key);
        try {
            new DataOutputStream(out).writeInt(Integer.reverseBytes(value));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static void writeZ(ByteArrayOutputStream out, String text) {
        byte[] bytes = utf8(text);
        out.write(bytes, 0, bytes.length);
        out.write(0);
    }
}
