package com.tj.skyrim.steam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/** Depot chunk decryption, decompression and integrity checks. */
public class ChunkDecoderTest {

    private static final byte[] KEY = key();

    @Test
    public void adler32UsesTheSteamSeed() {
        assertEquals(0, ChunkDecoder.adler32(new byte[0]));
        assertEquals(0x000A0006, ChunkDecoder.adler32(new byte[] {1, 2, 3}));
        assertEquals(0, ChunkDecoder.adler32(new byte[] {0}));
    }

    @Test
    public void decodesAPkzipChunk() throws Exception {
        byte[] payload = "hello world".getBytes("UTF-8");
        byte[] encrypted = encrypt(zip(payload));
        DepotManifest.Chunk chunk = chunk(encrypted, payload.length, ChunkDecoder.adler32(payload));

        assertArrayEquals(payload, ChunkDecoder.decode(chunk, encrypted, KEY));
    }

    @Test(expected = SteamException.class)
    public void rejectsAChunkWithTheWrongAdler32() throws Exception {
        byte[] payload = "hello world".getBytes("UTF-8");
        byte[] encrypted = encrypt(zip(payload));
        DepotManifest.Chunk chunk = chunk(
                encrypted, payload.length, ChunkDecoder.adler32(payload) + 1);

        ChunkDecoder.decode(chunk, encrypted, KEY);
    }

    @Test(expected = SteamException.class)
    public void rejectsAChunkWithTheWrongSize() throws Exception {
        byte[] payload = "hello world".getBytes("UTF-8");
        byte[] encrypted = encrypt(zip(payload));
        DepotManifest.Chunk chunk = chunk(
                encrypted, payload.length + 1, ChunkDecoder.adler32(payload));

        ChunkDecoder.decode(chunk, encrypted, KEY);
    }

    @Test(expected = SteamException.class)
    public void rejectsCiphertextThatIsTooShort() throws Exception {
        DepotManifest.Chunk chunk = chunk(new byte[16], 1, 0);

        ChunkDecoder.decode(chunk, new byte[16], KEY);
    }

    @Test(expected = SteamException.class)
    public void rejectsCiphertextThatIsNotBlockAligned() throws Exception {
        DepotManifest.Chunk chunk = chunk(new byte[33], 1, 0);

        ChunkDecoder.decode(chunk, new byte[33], KEY);
    }

    @Test(expected = SteamException.class)
    public void rejectsAnUnknownCompression() throws Exception {
        byte[] plain = new byte[16];
        plain[0] = 'X';
        plain[1] = 'X';
        plain[2] = 'X';
        plain[3] = 'X';
        byte[] encrypted = encrypt(plain);
        DepotManifest.Chunk chunk = chunk(encrypted, plain.length, ChunkDecoder.adler32(plain));

        ChunkDecoder.decode(chunk, encrypted, KEY);
    }

    /* ------------------------------------------------------------------ */

    private static DepotManifest.Chunk chunk(byte[] encrypted, int uncompressed, int crc) {
        return new DepotManifest.Chunk(
                new byte[20], crc, 0, encrypted.length, uncompressed);
    }

    private static byte[] zip(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("chunk"));
            zip.write(payload);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** Steam layout: ECB-encrypted IV, then AES-CBC/PKCS7 data. */
    private static byte[] encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[16];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i + 1);
        }
        SecretKeySpec key = new SecretKeySpec(KEY, "AES");

        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, key);
        byte[] head = ecb.doFinal(iv);

        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] body = cbc.doFinal(plain);

        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }
}
