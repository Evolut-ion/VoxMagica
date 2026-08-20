package com.ev0smods.voxmagica.voice;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps already-encoded Opus packets in an Ogg container, producing a file an STT provider will
 * accept as {@code .ogg}. Ported from VerityHE's {@code OggOpusMuxer} - generic Opus/Ogg framing,
 * not specific to any particular mod.
 *
 * <p><b>This does not decode or re-encode anything.</b> The Opus packets captured from
 * {@code VoiceData.opusData} are copied through byte-for-byte; all this class adds is Ogg page
 * framing plus the two mandatory Opus header packets. The Hytale server jar bundles no Opus
 * decoder, so decode-to-PCM/WAV is not an option here - muxing is pure bookkeeping instead.
 *
 * <h2>Format references</h2>
 * Ogg bitstream framing per RFC 3533, Opus-in-Ogg encapsulation per RFC 7845.
 *
 * <pre>
 * Ogg page header (27 bytes + segment table):
 *   0..3   "OggS"
 *   4      stream structure version (0)
 *   5      header type flags: bit0 continued, bit1 BOS, bit2 EOS
 *   6..13  granule position           (int64, little endian)
 *   14..17 bitstream serial number    (uint32, little endian)
 *   18..21 page sequence number       (uint32, little endian)
 *   22..25 CRC32                      (uint32, little endian; computed with this field zeroed)
 *   26     page segment count (1..255)
 *   27..   segment table (lacing values)
 * </pre>
 *
 * <p>Note the Ogg CRC is <b>not</b> the common zlib/PKZIP CRC-32 - it uses polynomial
 * {@code 0x04C11DB7} with no input/output reflection, zero initial value and no final XOR, so
 * {@link java.util.zip.CRC32} cannot be substituted.
 *
 * <h2>Granule positions</h2>
 * Ogg-Opus granule positions are counted in 48 kHz samples regardless of the original signal
 * bandwidth. Rather than assume every frame is 20 ms, {@link #opusPacketSamples(byte[])} reads the
 * duration straight out of each packet's TOC byte (RFC 6716 §3.1).
 */
public final class OggOpusMuxer {

    /** Ogg-Opus granule positions are always in 48 kHz units. */
    public static final int GRANULE_RATE_HZ = 48_000;

    /** Max lacing values in a single Ogg page. */
    private static final int MAX_SEGMENTS_PER_PAGE = 255;

    /**
     * Samples the decoder should discard from the start of the stream (encoder priming delay).
     * We are not the encoder - these frames come from the speaking client's libopus instance - so
     * its true lookahead is unknowable here. 312 samples (6.5 ms at 48 kHz) is libopus's default.
     */
    private static final int PRE_SKIP_SAMPLES = 312;

    private static final byte[] MAGIC_OGG_S = {'O', 'g', 'g', 'S'};
    private static final byte[] MAGIC_OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC_OPUS_TAGS = "OpusTags".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VENDOR = "VoxMagica".getBytes(StandardCharsets.UTF_8);

    /** Ogg CRC: polynomial 0x04C11DB7, MSB-first, init 0, no reflection, no final XOR. */
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = ((r & 0x80000000) != 0) ? ((r << 1) ^ 0x04C11DB7) : (r << 1);
            }
            CRC_TABLE[i] = r;
        }
    }

    private OggOpusMuxer() {
    }

    /**
     * Muxes raw Opus packets into a complete in-memory Ogg-Opus file.
     *
     * @param opusPackets Opus packets in capture order, exactly as received on the wire
     * @param channels    channel count (1 on this Hytale build)
     * @return the bytes of a self-contained {@code .ogg} file
     * @throws IllegalArgumentException if no packets are supplied
     */
    public static byte[] mux(@Nonnull List<byte[]> opusPackets, int channels) {
        if (opusPackets.isEmpty()) {
            throw new IllegalArgumentException("Cannot mux an empty packet list");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(estimateSize(opusPackets));
        int serialNumber = (int) (System.nanoTime() ^ opusPackets.size());
        int pageSequence = 0;

        // --- Page 0: OpusHead, beginning-of-stream, its own page (RFC 7845 requires this). ---
        writePage(out, buildOpusHead(channels), 0L, serialNumber, pageSequence++, true, false);

        // --- Page 1: OpusTags comment header, also alone on its page. ---
        writePage(out, buildOpusTags(), 0L, serialNumber, pageSequence++, false, false);

        // --- Audio pages. ---
        long granulePosition = 0L;
        List<byte[]> pagePackets = new ArrayList<>();
        int pageSegments = 0;

        for (int i = 0; i < opusPackets.size(); i++) {
            byte[] packet = opusPackets.get(i);
            int segmentsNeeded = (packet.length / 255) + 1;

            // A packet's lacing values may not straddle pages in this writer, so flush first.
            if (pageSegments + segmentsNeeded > MAX_SEGMENTS_PER_PAGE && !pagePackets.isEmpty()) {
                writeAudioPage(out, pagePackets, granulePosition, serialNumber, pageSequence++, false);
                pagePackets.clear();
                pageSegments = 0;
            }

            pagePackets.add(packet);
            pageSegments += segmentsNeeded;
            granulePosition += opusPacketSamples(packet);

            boolean isLast = (i == opusPackets.size() - 1);
            if (isLast) {
                writeAudioPage(out, pagePackets, granulePosition, serialNumber, pageSequence, true);
            }
        }

        return out.toByteArray();
    }

    /**
     * Returns the number of 48 kHz samples a single Opus packet decodes to, read from its TOC byte
     * per RFC 6716 §3.1. Returns 0 for a malformed/empty packet.
     *
     * <p>Layout of the TOC byte: {@code config} in bits 3..7, stereo flag in bit 2, and the frame
     * packing {@code code} in bits 0..1. Code 3 packets carry their frame count in the following
     * byte's low 6 bits.
     */
    public static int opusPacketSamples(byte[] packet) {
        if (packet == null || packet.length < 1) {
            return 0;
        }
        int toc = packet[0] & 0xFF;
        int config = (toc >> 3) & 0x1F;
        int code = toc & 0x03;

        int samplesPerFrame = frameSamplesForConfig(config);

        int frames;
        switch (code) {
            case 0 -> frames = 1;
            case 1, 2 -> frames = 2;
            default -> {
                if (packet.length < 2) {
                    return 0;
                }
                frames = packet[1] & 0x3F;
            }
        }
        return samplesPerFrame * frames;
    }

    /** Frame duration in 48 kHz samples for each of the 32 Opus configurations. */
    private static int frameSamplesForConfig(int config) {
        if (config < 12) {
            // SILK-only: NB/MB/WB, each with 10/20/40/60 ms variants.
            return switch (config & 0x03) {
                case 0 -> 480;   // 10 ms
                case 1 -> 960;   // 20 ms
                case 2 -> 1920;  // 40 ms
                default -> 2880; // 60 ms
            };
        }
        if (config < 16) {
            // Hybrid SWB/FB: 10 or 20 ms only.
            return ((config & 0x01) == 0) ? 480 : 960;
        }
        // CELT-only: 2.5/5/10/20 ms.
        return switch (config & 0x03) {
            case 0 -> 120;  // 2.5 ms
            case 1 -> 240;  // 5 ms
            case 2 -> 480;  // 10 ms
            default -> 960; // 20 ms
        };
    }

    /** Total duration of a packet list, in milliseconds. */
    public static long durationMillis(@Nonnull List<byte[]> opusPackets) {
        long samples = 0L;
        for (byte[] packet : opusPackets) {
            samples += opusPacketSamples(packet);
        }
        return (samples * 1000L) / GRANULE_RATE_HZ;
    }

    // --- Header packets ---

    private static byte[] buildOpusHead(int channels) {
        byte[] head = new byte[19];
        System.arraycopy(MAGIC_OPUS_HEAD, 0, head, 0, 8);
        head[8] = 1;                       // version
        head[9] = (byte) channels;         // channel count
        writeShortLE(head, 10, PRE_SKIP_SAMPLES);
        writeIntLE(head, 12, GlyphVoiceStreamTap.VOICE_SAMPLE_RATE_HZ); // informational only
        writeShortLE(head, 16, 0);         // output gain (Q7.8 dB)
        head[18] = 0;                      // channel mapping family 0
        return head;
    }

    private static byte[] buildOpusTags() {
        byte[] tags = new byte[8 + 4 + VENDOR.length + 4];
        System.arraycopy(MAGIC_OPUS_TAGS, 0, tags, 0, 8);
        writeIntLE(tags, 8, VENDOR.length);
        System.arraycopy(VENDOR, 0, tags, 12, VENDOR.length);
        writeIntLE(tags, 12 + VENDOR.length, 0); // zero user comments
        return tags;
    }

    // --- Page writing ---

    private static void writeAudioPage(ByteArrayOutputStream out, List<byte[]> packets,
                                       long granulePosition, int serialNumber, int pageSequence,
                                       boolean endOfStream) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> lacing = new ArrayList<>();

        for (byte[] packet : packets) {
            int remaining = packet.length;
            while (remaining >= 255) {
                lacing.add(255);
                remaining -= 255;
            }
            lacing.add(remaining); // terminating value; 0 when length is an exact multiple of 255
            body.write(packet, 0, packet.length);
        }

        writePageRaw(out, body.toByteArray(), lacing, granulePosition, serialNumber, pageSequence,
            false, endOfStream);
    }

    /** Writes a page holding exactly one packet (used for the two header packets). */
    private static void writePage(ByteArrayOutputStream out, byte[] packet, long granulePosition,
                                  int serialNumber, int pageSequence, boolean beginOfStream,
                                  boolean endOfStream) {
        List<Integer> lacing = new ArrayList<>();
        int remaining = packet.length;
        while (remaining >= 255) {
            lacing.add(255);
            remaining -= 255;
        }
        lacing.add(remaining);

        writePageRaw(out, packet, lacing, granulePosition, serialNumber, pageSequence,
            beginOfStream, endOfStream);
    }

    private static void writePageRaw(ByteArrayOutputStream out, byte[] body, List<Integer> lacing,
                                     long granulePosition, int serialNumber, int pageSequence,
                                     boolean beginOfStream, boolean endOfStream) {
        if (lacing.size() > MAX_SEGMENTS_PER_PAGE) {
            throw new IllegalStateException("Ogg page would need " + lacing.size()
                + " segments, max is " + MAX_SEGMENTS_PER_PAGE);
        }

        int headerLength = 27 + lacing.size();
        byte[] page = new byte[headerLength + body.length];

        System.arraycopy(MAGIC_OGG_S, 0, page, 0, 4);
        page[4] = 0; // stream structure version
        page[5] = (byte) ((beginOfStream ? 0x02 : 0x00) | (endOfStream ? 0x04 : 0x00));
        writeLongLE(page, 6, granulePosition);
        writeIntLE(page, 14, serialNumber);
        writeIntLE(page, 18, pageSequence);
        writeIntLE(page, 22, 0); // CRC placeholder — must be zero while checksumming
        page[26] = (byte) lacing.size();

        for (int i = 0; i < lacing.size(); i++) {
            page[27 + i] = (byte) (int) lacing.get(i);
        }
        System.arraycopy(body, 0, page, headerLength, body.length);

        writeIntLE(page, 22, oggCrc(page));

        out.write(page, 0, page.length);
    }

    static int oggCrc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        }
        return crc;
    }

    // --- Little-endian helpers ---

    private static void writeShortLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeLongLE(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
    }

    private static int estimateSize(List<byte[]> packets) {
        int total = 128;
        for (byte[] packet : packets) {
            total += packet.length + (packet.length / 255) + 28;
        }
        return total;
    }
}
