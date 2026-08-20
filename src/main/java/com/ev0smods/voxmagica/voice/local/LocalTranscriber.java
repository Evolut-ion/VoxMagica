package com.ev0smods.voxmagica.voice.local;

import com.ev0smods.voxmagica.thirdparty.concentus.ConcentusResampler;
import com.ev0smods.voxmagica.thirdparty.concentus.OpusDecoder;
import com.ev0smods.voxmagica.thirdparty.concentus.OpusException;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * In-process (no external server) speech-to-text via whisper.cpp, through
 * {@code io.github.givimad:whisper-jni} JNI bindings. See {@code GlyphSttClient}'s javadoc for
 * how this fits into the overall provider picture ({@code SttProvider=local}).
 *
 * <h2>Pipeline</h2>
 * Raw 48kHz mono Opus voice-chat packets (same input {@code GlyphSttClient} gets for the
 * {@code speaches}/{@code openai} providers) are decoded with a vendored pure-Java Opus decoder
 * ({@link OpusDecoder}, from {@code com.ev0smods.voxmagica.thirdparty.concentus}), resampled
 * 48kHz-&gt;16kHz ({@link ConcentusResampler}), normalized to float32 in [-1,1], and fed directly
 * to whisper.cpp. No Ogg container step (that only ever existed for HTTP upload) and no network
 * call at all.
 *
 * <h2>Model lifecycle</h2>
 * One {@link WhisperContext} (the loaded model weights) is shared for the plugin's lifetime,
 * built once by {@link #init}. Each calling thread gets its own {@link WhisperState} (via
 * {@code ThreadLocal}, lazily created) - <b>but actual native calls into whisper.cpp are still
 * serialized</b> via {@link #INFERENCE_LOCK}, regardless of which state/thread they're from.
 *
 * <p>The original design here tried true concurrent inference (multiple threads calling
 * {@code fullWithState} against separate states sharing one context simultaneously), reasoning
 * that whisper-jni's separate {@code initState}/{@code fullWithState} API surface existed
 * specifically to support that. <b>That reasoning was wrong in practice</b>: verified against a
 * real server, two overlapping utterances (a short one arriving while a slightly earlier one was
 * still transcribing) triggered a hang that froze the entire JVM, not just voice capture - almost
 * certainly a native-side lock held across a JVM safepoint inside whisper.cpp/ggml, since nothing
 * in this class's own Java code holds a lock across a blocking call. Per-thread states are kept
 * anyway (harmless, and other threads can still decode/resample the *next* utterance while one
 * holds {@link #INFERENCE_LOCK}), but only one thread is ever inside whisper.cpp at a time now.
 *
 * <h2>Threading</h2>
 * {@link #init} and {@link #transcribeAsync} both run entirely off the calling thread's own work
 * (an STT-pool thread) - no ECS/{@code Store} access anywhere in this class, same as the network
 * providers.
 */
public final class LocalTranscriber {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static volatile WhisperJNI whisper;
    private static volatile WhisperContext context;

    private static final ThreadLocal<WhisperState> STATES = new ThreadLocal<>();
    /** Every WhisperState handed out via {@link #STATES}, so {@link #shutdown} can free them all. */
    private static final ConcurrentLinkedQueue<WhisperState> ALL_STATES = new ConcurrentLinkedQueue<>();

    /**
     * Serializes every native call into whisper.cpp (state creation included) - see this class's
     * javadoc for why concurrent access across states isn't actually safe in practice, despite
     * what whisper-jni's API surface suggests.
     */
    private static final Semaphore INFERENCE_LOCK = new Semaphore(1);

    private LocalTranscriber() {
    }

    public static boolean isReady() {
        return READY.get();
    }

    /**
     * Kicks off model download-verify-load on {@code pool}, non-blocking. Safe to call even if
     * the model file still needs downloading - the load task itself downloads it. Call once, from
     * {@code GlyphVoiceStreamTap.register()} (mirrors {@code GlyphSttClient.init()}).
     */
    public static void init(@Nonnull JavaPlugin plugin, @Nonnull ExecutorService pool, @Nonnull String modelName) {
        pool.execute(() -> {
            try {
                loadModel(plugin, modelName);
                READY.set(true);
                LOGGER.atInfo().log("[VoxMagica] Local whisper model '%s' ready.", modelName);
            } catch (Throwable t) {
                LOGGER.atSevere().withCause(t).log(
                    "[VoxMagica] Failed to load local whisper model '%s'.", modelName);
            }
        });
    }

    public static void shutdown() {
        READY.set(false);
        WhisperState state;
        while ((state = ALL_STATES.poll()) != null) {
            state.close();
        }
        WhisperContext ctx = context;
        context = null;
        whisper = null;
        if (ctx != null) {
            ctx.close();
        }
    }

    /**
     * Transcribes one utterance. Runs entirely on the calling thread (an STT-pool thread) -
     * blocking is expected and fine here, same contract as {@code GlyphSttClient.runTranscription}.
     *
     * @param opusFrames raw Opus packets for one utterance, in arrival order
     * @param language   configured language code, or {@code null}/blank for auto-detect
     * @param onResult   invoked exactly once with {@code (transcript, error)}
     */
    public static void transcribeAsync(@Nonnull List<byte[]> opusFrames, @Nullable String language,
                                        @Nonnull BiConsumer<String, Throwable> onResult) {
        WhisperJNI w = whisper;
        WhisperContext ctx = context;
        if (!READY.get() || w == null || ctx == null) {
            onResult.accept(null, new LocalModelNotReadyException(
                "Local whisper model is still downloading/loading."));
            return;
        }

        try {
            // Decode/resample happen outside the lock - only cheap, thread-safe pure-Java work
            // (Concentus), so multiple utterances can prepare their PCM concurrently while at
            // most one holds INFERENCE_LOCK.
            short[] pcm48k = decodeOpusToPcm(opusFrames);
            short[] pcm16k = ConcentusResampler.resample48to16(pcm48k);
            float[] samples = toFloatNormalized(pcm16k);

            WhisperFullParams params = new WhisperFullParams();
            params.language = (language == null || language.isBlank()) ? "auto" : language;
            params.printProgress = false;
            params.printRealtime = false;
            params.printTimestamps = false;
            params.printSpecial = false;

            // onResult is deliberately called only AFTER the lock is released below, on both the
            // success and rc!=0 paths - the callback can trigger real downstream work (glyph
            // injection etc.) and must never run while another utterance is waiting on this lock.
            String transcript = null;
            Throwable error = null;
            INFERENCE_LOCK.acquire();
            try {
                WhisperState state = STATES.get();
                if (state == null) {
                    state = w.initState(ctx);
                    STATES.set(state);
                    ALL_STATES.add(state);
                }

                int rc = w.fullWithState(ctx, state, params, samples, samples.length);
                if (rc != 0) {
                    error = new IOException("whisper.fullWithState returned " + rc);
                } else {
                    StringBuilder text = new StringBuilder();
                    int segments = w.fullNSegmentsFromState(state);
                    for (int i = 0; i < segments; i++) {
                        text.append(w.fullGetSegmentTextFromState(state, i));
                    }
                    transcript = text.toString().trim();
                }
            } finally {
                INFERENCE_LOCK.release();
            }
            onResult.accept(transcript, error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            onResult.accept(null, t);
        }
    }

    /** Runs on the world thread only via {@code GlyphSttClient} - this method itself touches no ECS. */
    @Nonnull
    public static Path modelsDir(@Nonnull JavaPlugin plugin) {
        // getFile() = <UserData>/Mods/VoxMagica-<version>.jar - confirmed live against a real
        // server (not just inferred from bytecode). getDataDirectory() is per-save-scoped
        // (confirmed: resolves to "mods/Ev0sMods_VoxMagica" relative to the current save), wrong
        // for a multi-hundred-MB cache shared across every save.
        Path userData = plugin.getFile().getParent().getParent();
        return userData.resolve("VoxMagicaData").resolve("whisper-models");
    }

    private static void loadModel(JavaPlugin plugin, String modelName) throws Exception {
        LocalWhisperModelCatalog.Entry entry = LocalWhisperModelCatalog.get(modelName);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown local whisper model: '" + modelName + "'");
        }

        Path modelsDir = modelsDir(plugin);
        Files.createDirectories(modelsDir);
        Path modelFile = modelsDir.resolve(entry.getFileName());
        ensureDownloaded(entry, modelFile);

        WhisperJNI.loadLibrary();
        WhisperJNI w = new WhisperJNI();
        WhisperContext ctx = w.init(modelFile);
        whisper = w;
        context = ctx;
    }

    private static void ensureDownloaded(LocalWhisperModelCatalog.Entry entry, Path target) throws IOException {
        if (Files.exists(target) && sha256Matches(target, entry.getSha256())) {
            LOGGER.atInfo().log("[VoxMagica] Local whisper model '%s' already present and verified.",
                entry.getName());
            return;
        }

        LOGGER.atInfo().log("[VoxMagica] Downloading local whisper model '%s' (%d MB) ...",
            entry.getName(), entry.getSizeBytes() / (1024 * 1024));

        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.getDownloadUrl()))
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", "VoxMagica-Plugin")
            .GET()
            .build();
        HttpResponse<Path> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Model download interrupted", e);
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("Model download for '" + entry.getName() + "' failed: HTTP "
                + response.statusCode());
        }
        if (!sha256Matches(tmp, entry.getSha256())) {
            Files.deleteIfExists(tmp);
            throw new IOException("Downloaded model '" + entry.getName() + "' failed sha256 verification");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.atInfo().log("[VoxMagica] Downloaded and verified local whisper model '%s'.", entry.getName());
    }

    private static boolean sha256Matches(Path file, String expectedHex) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = java.util.HexFormat.of().formatHex(digest.digest());
        return actual.equalsIgnoreCase(expectedHex);
    }

    /**
     * Decodes a whole utterance's Opus packets to one contiguous 48kHz mono PCM buffer.
     * {@link OpusDecoder#decode} returns the true decoded sample count per call, so a
     * fixed-size scratch buffer (the max possible Opus frame at 48kHz, per its own javadoc) is
     * reused across packets rather than pre-computing exact sizes per packet.
     */
    private static short[] decodeOpusToPcm(List<byte[]> opusFrames) throws OpusException {
        final int maxFrameSamples = 5760; // 120ms at 48kHz - whisper.cpp/Concentus max packet duration
        OpusDecoder decoder = new OpusDecoder(48_000, 1);
        short[] scratch = new short[maxFrameSamples];
        List<short[]> chunks = new ArrayList<>(opusFrames.size());
        int total = 0;
        for (byte[] packet : opusFrames) {
            int decoded = decoder.decode(packet, 0, packet.length, scratch, 0, scratch.length, false);
            short[] chunk = new short[decoded];
            System.arraycopy(scratch, 0, chunk, 0, decoded);
            chunks.add(chunk);
            total += decoded;
        }
        short[] pcm = new short[total];
        int offset = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.length);
            offset += chunk.length;
        }
        return pcm;
    }

    private static float[] toFloatNormalized(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = pcm[i] / 32768f;
        }
        return out;
    }
}
