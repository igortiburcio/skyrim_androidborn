/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Parser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import steam.proto.ContentManifest.ContentManifestMetadata;
import steam.proto.ContentManifest.ContentManifestPayload;
import steam.proto.ContentManifest.ContentManifestPayload.FileMapping;
import steam.proto.ContentManifest.ContentManifestPayload.FileMapping.ChunkData;

/**
 * The v5 depot manifest. The CDN serves a ZIP whose single entry is a stream
 * of [magic][length][protobuf] sections (payload, metadata, signature), ending
 * with the end magic. This is SteamKit2's DepotManifest.InternalDeserialize.
 */
final class DepotManifest {
    private static final int MAGIC_PAYLOAD = 0x71F617D0;
    private static final int MAGIC_METADATA = 0x1F4812BE;
    private static final int MAGIC_SIGNATURE = 0x1B81B817;
    private static final int MAGIC_END = 0x32C415AB;

    static final int FLAG_DIRECTORY = 64;
    static final int FLAG_SYMLINK = 512;
    static final int FLAG_INSTALL_SCRIPT = 256;

    static final class Chunk {
        final byte[] sha;
        final int crc;
        final long offset;
        final int compressedLength;
        final int uncompressedLength;

        Chunk(byte[] sha, int crc, long offset, int compressedLength, int uncompressedLength) {
            this.sha = sha;
            this.crc = crc;
            this.offset = offset;
            this.compressedLength = compressedLength;
            this.uncompressedLength = uncompressedLength;
        }
    }

    static final class FileEntry {
        final String name;
        final long size;
        final int flags;
        final byte[] shaContent;
        final String linkTarget;
        final List<Chunk> chunks;

        FileEntry(String name, long size, int flags, byte[] shaContent, String linkTarget,
                  List<Chunk> chunks) {
            this.name = name;
            this.size = size;
            this.flags = flags;
            this.shaContent = shaContent;
            this.linkTarget = linkTarget;
            this.chunks = chunks;
        }

        boolean isDirectory() {
            return (flags & FLAG_DIRECTORY) != 0;
        }

        boolean isSymlink() {
            return (flags & FLAG_SYMLINK) != 0;
        }
    }

    final int depotId;
    final long manifestId;
    final boolean filenamesEncrypted;
    final long totalOriginal;
    final long totalCompressed;
    final List<FileEntry> files;

    private DepotManifest(int depotId, long manifestId, boolean filenamesEncrypted,
                          long totalOriginal, long totalCompressed, List<FileEntry> files) {
        this.depotId = depotId;
        this.manifestId = manifestId;
        this.filenamesEncrypted = filenamesEncrypted;
        this.totalOriginal = totalOriginal;
        this.totalCompressed = totalCompressed;
        this.files = files;
    }

    /** Parses the ZIP response body from the manifest URL. */
    static DepotManifest parseZip(byte[] zipBytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                return parse(readAll(zip));
            }
        }
        throw new SteamException("manifest ZIP has no entry");
    }

    /** Parses the uncompressed manifest section stream. */
    static DepotManifest parse(byte[] data) throws Exception {
        ContentManifestPayload payload = null;
        ContentManifestMetadata metadata = null;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 4) {
            int magic = buffer.getInt();
            if (magic == MAGIC_END) {
                break;
            }
            if (buffer.remaining() < 4) {
                throw new SteamException("truncated manifest section");
            }
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                throw new SteamException("bad manifest section length " + length);
            }
            int offset = buffer.position();
            switch (magic) {
                case MAGIC_PAYLOAD:
                    payload = parseSection(data, offset, length, ContentManifestPayload.parser());
                    break;
                case MAGIC_METADATA:
                    metadata = parseSection(data, offset, length, ContentManifestMetadata.parser());
                    break;
                case MAGIC_SIGNATURE:
                    break;  // not needed to install the files
                default:
                    throw new SteamException("unknown manifest magic 0x"
                            + Integer.toHexString(magic));
            }
            buffer.position(offset + length);
        }
        if (payload == null) {
            throw new SteamException("manifest has no payload");
        }
        boolean encrypted = metadata != null && metadata.getFilenamesEncrypted();
        List<FileEntry> files = new ArrayList<>();
        for (FileMapping mapping : payload.getMappingsList()) {
            List<Chunk> chunks = new ArrayList<>(mapping.getChunksCount());
            for (ChunkData chunk : mapping.getChunksList()) {
                chunks.add(new Chunk(chunk.getSha().toByteArray(), chunk.getCrc(),
                        chunk.getOffset(), chunk.getCbCompressed(), chunk.getCbOriginal()));
            }
            files.add(new FileEntry(
                    mapping.getFilename().replace('\\', '/'),
                    mapping.getSize(),
                    mapping.getFlags(),
                    mapping.getShaContent().toByteArray(),
                    mapping.getLinktarget(),
                    chunks));
        }
        int depotId = metadata != null ? metadata.getDepotId() : 0;
        long gid = metadata != null ? metadata.getGidManifest() : 0;
        long original = metadata != null ? metadata.getCbDiskOriginal() : 0;
        long compressed = metadata != null ? metadata.getCbDiskCompressed() : 0;
        return new DepotManifest(depotId, gid, encrypted, original, compressed, files);
    }

    private static <T> T parseSection(byte[] data, int offset, int length, Parser<T> parser)
            throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data, offset, length);
        return parser.parseFrom(in);
    }

    private static byte[] readAll(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = zip.read(buffer)) > 0) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    /**
     * Decrypts base64 filenames with the depot key (AES-256: ECB IV + CBC).
     * Skyrim's 2011 depots do not use it, but the flag is part of the format.
     */
    DepotManifest withDecryptedNames(byte[] depotKey) {
        if (!filenamesEncrypted) {
            return this;
        }
        List<FileEntry> out = new ArrayList<>(files.size());
        for (FileEntry file : files) {
            String name = file.name;
            try {
                name = decryptName(file.name, depotKey);
            } catch (Exception ignored) {
                // Some depots set the flag but ship plain names; keep them.
            }
            out.add(new FileEntry(name, file.size, file.flags,
                    file.shaContent, file.linkTarget, file.chunks));
        }
        return new DepotManifest(depotId, manifestId, false, totalOriginal, totalCompressed, out);
    }

    private static String decryptName(String encoded, byte[] key) throws Exception {
        // Steam wraps long encrypted names at ~76 columns (MIME base64);
        // Java's basic decoder rejects the line breaks.
        byte[] data = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
        if (data.length < 32) {
            throw new SteamException("encrypted filename is too short");
        }
        SecretKeySpec secret = new SecretKeySpec(key, "AES");
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.DECRYPT_MODE, secret);
        byte[] iv = ecb.doFinal(data, 0, 16);
        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        byte[] plain = cbc.doFinal(data, 16, data.length - 16);
        int length = plain.length;
        while (length > 0 && plain[length - 1] == 0) {
            length--;
        }
        return new String(plain, 0, length, StandardCharsets.UTF_8).replace('\\', '/');
    }
}
