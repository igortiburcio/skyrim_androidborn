package com.tj.skyrim.steam;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import steam.proto.ContentManifest.ContentManifestMetadata;
import steam.proto.ContentManifest.ContentManifestPayload;
import steam.proto.ContentManifest.ContentManifestPayload.FileMapping;
import steam.proto.ContentManifest.ContentManifestPayload.FileMapping.ChunkData;

/** The v5 depot manifest: section stream, ZIP wrapper and file entries. */
public class DepotManifestTest {

    private static final int MAGIC_PAYLOAD = 0x71F617D0;
    private static final int MAGIC_METADATA = 0x1F4812BE;
    private static final int MAGIC_SIGNATURE = 0x1B81B817;
    private static final int MAGIC_END = 0x32C415AB;

    @Test
    public void parsesPayloadAndMetadata() throws Exception {
        byte[] payload = payloadWithOneFile().toByteArray();
        byte[] metadata = metadata().toByteArray();

        DepotManifest manifest = DepotManifest.parse(sections(payload, metadata));

        assertEquals(72850, manifest.depotId);
        assertEquals(42L, manifest.manifestId);
        assertEquals(200L, manifest.totalOriginal);
        assertEquals(100L, manifest.totalCompressed);
        assertFalse(manifest.filenamesEncrypted);
        assertEquals(1, manifest.files.size());

        DepotManifest.FileEntry file = manifest.files.get(0);
        assertEquals("meshes/test.nif", file.name);
        assertEquals(1234L, file.size);
        assertArrayEquals(new byte[] {1, 2, 3}, file.shaContent);
        assertEquals(1, file.chunks.size());

        DepotManifest.Chunk chunk = file.chunks.get(0);
        assertArrayEquals(new byte[] {9, 9}, chunk.sha);
        assertEquals(0x12345678, chunk.crc);
        assertEquals(0L, chunk.offset);
        assertEquals(100, chunk.compressedLength);
        assertEquals(200, chunk.uncompressedLength);
    }

    @Test
    public void directoriesAndSymlinksAreFlagged() throws Exception {
        ContentManifestPayload payload = ContentManifestPayload.newBuilder()
                .addMappings(FileMapping.newBuilder().setFilename("Data\\").setFlags(64))
                .addMappings(FileMapping.newBuilder().setFilename("link").setFlags(512)
                        .setLinktarget("target"))
                .build();

        DepotManifest manifest = DepotManifest.parse(sections(payload.toByteArray(), null));

        assertTrue(manifest.files.get(0).isDirectory());
        assertFalse(manifest.files.get(0).isSymlink());
        assertTrue(manifest.files.get(1).isSymlink());
        assertEquals("target", manifest.files.get(1).linkTarget);
        assertEquals(0, manifest.depotId);
    }

    @Test
    public void signatureSectionsAreSkipped() throws Exception {
        byte[] payload = payloadWithOneFile().toByteArray();
        int size = 4 + 4 + payload.length + 4 + 4 + 8 + 4;
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC_PAYLOAD).putInt(payload.length).put(payload);
        buffer.putInt(MAGIC_SIGNATURE).putInt(8).put(new byte[8]);
        buffer.putInt(MAGIC_END);

        DepotManifest manifest = DepotManifest.parse(buffer.array());

        assertEquals(1, manifest.files.size());
    }

    @Test
    public void parsesTheZipWrapper() throws Exception {
        byte[] stream = sections(payloadWithOneFile().toByteArray(), metadata().toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.bin"));
            zip.write(stream);
            zip.closeEntry();
        }

        DepotManifest manifest = DepotManifest.parseZip(out.toByteArray());

        assertEquals(72850, manifest.depotId);
        assertEquals("meshes/test.nif", manifest.files.get(0).name);
    }

    @Test
    public void plainNamesAreLeftAlone() throws Exception {
        DepotManifest manifest = DepotManifest.parse(
                sections(payloadWithOneFile().toByteArray(), metadata().toByteArray()));

        assertSame(manifest, manifest.withDecryptedNames(new byte[32]));
    }

    @Test(expected = SteamException.class)
    public void aManifestWithoutPayloadIsRejected() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC_END);
        DepotManifest.parse(buffer.array());
    }

    @Test(expected = SteamException.class)
    public void anUnknownMagicIsRejected() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0xDEADBEEF).putInt(0).putInt(MAGIC_END);
        DepotManifest.parse(buffer.array());
    }

    @Test(expected = SteamException.class)
    public void aTruncatedSectionIsRejected() throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC_PAYLOAD).putShort((short) 0);
        DepotManifest.parse(buffer.array());
    }

    /* ------------------------------------------------------------------ */

    private static ContentManifestPayload payloadWithOneFile() {
        return ContentManifestPayload.newBuilder()
                .addMappings(FileMapping.newBuilder()
                        .setFilename("meshes\\test.nif")
                        .setSize(1234)
                        .setFlags(0)
                        .setShaContent(ByteString.copyFrom(new byte[] {1, 2, 3}))
                        .addChunks(ChunkData.newBuilder()
                                .setSha(ByteString.copyFrom(new byte[] {9, 9}))
                                .setCrc(0x12345678)
                                .setOffset(0)
                                .setCbCompressed(100)
                                .setCbOriginal(200)))
                .build();
    }

    private static ContentManifestMetadata metadata() {
        return ContentManifestMetadata.newBuilder()
                .setDepotId(72850)
                .setGidManifest(42)
                .setCbDiskOriginal(200)
                .setCbDiskCompressed(100)
                .build();
    }

    private static byte[] sections(byte[] payload, byte[] metadata) {
        int size = 4 + 4 + payload.length + 4;
        if (metadata != null) {
            size += 4 + 4 + metadata.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC_PAYLOAD).putInt(payload.length).put(payload);
        if (metadata != null) {
            buffer.putInt(MAGIC_METADATA).putInt(metadata.length).put(metadata);
        }
        buffer.putInt(MAGIC_END);
        return buffer.array();
    }
}
