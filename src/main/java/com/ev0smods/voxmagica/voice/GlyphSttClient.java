package com.ev0smods.voxmagica.voice;

import com.ev0smods.voxmagica.config.VoxMagicaVoiceConfig;
import com.ev0smods.voxmagica.voice.local.LocalTranscriber;
import com.ev0smods.voxmagica.voice.local.LocalWhisperModelCatalog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Speech-to-text for captured voice-cast utterances. Ported from VerityHE's {@code SttClient},
 * which is already recognition-only (VerityHE's separate reply/TTS half is not touched or
 * reused here). See {@link VoxMagicaVoiceConfig} for what leaves this machine and when.
 *
 * <h2>Threading</h2>
 * Utterances complete on a Netty voice-stream I/O thread. The HTTP call is blocking and can take
 * seconds, so it runs on a small dedicated pool owned by this class. The completion callback
 * fires on this pool, <b>not</b> a world thread - callers must not touch the ECS from it (see
 * {@code com.ev0smods.voxmagica.glyph.VoiceGlyphInjector} for the world-thread hop).
 */
public final class GlyphSttClient {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";

    /** OpenAI's hosted base URL, used when the provider is {@code openai}. */
    static final String OPENAI_BASE_URL = "https://api.openai.com";

    /** {@code whisper-1} is the long-standing, most permissive OpenAI transcription model. */
    static final String OPENAI_MODEL = "whisper-1";

    /** Provider upload ceiling. We refuse anything larger locally rather than eating a 413. */
    static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;

    /** Utterances shorter than this are almost always a cough or a key-tap; not worth a call. */
    static final long MIN_UTTERANCE_MILLIS = 300L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** Bounded so a stuck provider cannot grow an unbounded backlog of audio in memory. */
    private static final int MAX_QUEUED_UTTERANCES = 4;

    /**
     * Concurrent transcription workers. One slow provider call no longer stalls every other
     * player's voice cast on a busy server. Per-player utterance order is preserved by
     * {@code GlyphUtteranceSession}, which delivers completions to the world in spoken order.
     */
    private static final int STT_POOL_THREADS = 4;

    private static volatile Config<VoxMagicaVoiceConfig> voiceConfig;
    private static volatile ThreadPoolExecutor executor;
    private static volatile HttpClient httpClient;

    private static final AtomicBoolean WARNED_UNKNOWN_PROVIDER = new AtomicBoolean();

    private GlyphSttClient() {
    }

    /**
     * Wires in the plugin config. Called from {@code GlyphVoiceStreamTap.register}.
     *
     * @param plugin needed only to locate the local whisper model cache directory
     *               ({@code JavaPlugin.getFile()}) when {@code SttProvider=local}; unused for the
     *               {@code speaches}/{@code openai} network providers.
     */
    public static synchronized void init(@Nonnull Config<VoxMagicaVoiceConfig> config,
                                         @Nonnull JavaPlugin plugin) {
        voiceConfig = config;

        if (executor == null) {
            // Multi-threaded so several players' transcriptions run concurrently; a slow STT call
            // for one player no longer delays everyone else. Per-player spoken order is preserved
            // upstream (GlyphUtteranceSession dispatches completions to the world thread in the
            // order each utterance was captured), so the pool never has to be single-threaded.
            // For SttProvider=local this same pool also runs whisper.cpp inference - see
            // LocalTranscriber's javadoc for why that's still safe/useful at this size.
            executor = new ThreadPoolExecutor(
                STT_POOL_THREADS, STT_POOL_THREADS,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_UTTERANCES),
                runnable -> {
                    Thread thread = new Thread(runnable, "VoxMagica-STT");
                    thread.setDaemon(true);
                    return thread;
                });
            executor.allowCoreThreadTimeOut(true);
        }

        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }

        if (!isConfigured()) {
            LOGGER.atInfo().log(
                "[VoxMagica] STT disabled (no SttProvider set); voice-cast capture will not run.");
        } else if (isInProcessProvider()) {
            String modelName = localModelName();
            LOGGER.atInfo().log(
                "[VoxMagica] STT enabled via in-process whisper.cpp (model=%s). Audio never "
                    + "leaves this machine. Downloading/loading the model in the background - "
                    + "voice-casts made before it's ready get a friendly retry message.",
                modelName);
            LocalTranscriber.init(plugin, executor, modelName);
        } else if (isLocalProvider()) {
            LOGGER.atInfo().log(
                "[VoxMagica] STT enabled via self-hosted %s at %s (model=%s). Audio stays on "
                    + "this machine.", providerId(), baseUrl(), model());
        } else {
            LOGGER.atWarning().log(
                "[VoxMagica] STT enabled via %s at %s. Captured speech WILL be uploaded to a "
                    + "third party and billed to your API key. Clear SttProvider to disable.",
                providerId(), baseUrl());
        }
    }

    /** Shuts the HTTP pool (and, if loaded, the local whisper model) down. Called from
     *  {@code GlyphVoiceStreamTap.unregister()}. */
    public static synchronized void shutdown() {
        ThreadPoolExecutor pool = executor;
        if (pool != null) {
            pool.shutdownNow();
            executor = null;
        }
        httpClient = null;
        voiceConfig = null;
        LocalTranscriber.shutdown();
    }

    /**
     * True when a provider is selected and whatever it needs is present - a reachable base URL for
     * Speaches, or an API key for OpenAI (local needs neither). {@code GlyphVoiceStreamTap} keys
     * capture off this.
     */
    public static boolean isConfigured() {
        String provider = providerId();
        if (provider.isEmpty()) {
            return false;
        }
        if (VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(provider)) {
            return true;
        }
        if (VoxMagicaVoiceConfig.PROVIDER_SPEACHES.equalsIgnoreCase(provider)) {
            return !baseUrl().isEmpty();
        }
        if (VoxMagicaVoiceConfig.PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
            return !apiKey().isEmpty();
        }
        return false;
    }

    /** True when the configured provider keeps audio on this machine (self-hosted Speaches or local). */
    public static boolean isLocalProvider() {
        String provider = providerId();
        return VoxMagicaVoiceConfig.PROVIDER_SPEACHES.equalsIgnoreCase(provider)
            || VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(provider);
    }

    /** True only for {@code local} - audio never leaves the JVM at all, not just the machine. */
    public static boolean isInProcessProvider() {
        return VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(providerId());
    }

    private static VoxMagicaVoiceConfig config() {
        Config<VoxMagicaVoiceConfig> holder = voiceConfig;
        return holder == null ? null : holder.get();
    }

    private static String providerId() {
        VoxMagicaVoiceConfig config = config();
        String provider = config == null ? null : config.getSttProvider();
        return provider == null ? "" : provider.trim();
    }

    private static String apiKey() {
        VoxMagicaVoiceConfig config = config();
        String key = config == null ? null : config.getSttApiKey();
        return key == null ? "" : key.trim();
    }

    private static String baseUrl() {
        if (VoxMagicaVoiceConfig.PROVIDER_OPENAI.equalsIgnoreCase(providerId())) {
            return OPENAI_BASE_URL;
        }
        VoxMagicaVoiceConfig config = config();
        String url = config == null ? null : config.getSttBaseUrl();
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.contains("://")) {
            // A bare "host:port" is a common misconfiguration; without a scheme Java's URI parser
            // treats "100.114.109.49" as the scheme name and the request fails with no host.
            trimmed = "http://" + trimmed;
        }
        return trimmed;
    }

    private static String model() {
        if (VoxMagicaVoiceConfig.PROVIDER_OPENAI.equalsIgnoreCase(providerId())) {
            return OPENAI_MODEL;
        }
        VoxMagicaVoiceConfig config = config();
        String model = config == null ? null : config.getSttModel();
        if (model == null || model.isBlank()) {
            // No explicit model: pick the whisper variant that matches the configured language.
            return defaultModelFor(language());
        }
        String trimmed = model.trim();
        warnIfEnglishOnlyModel(trimmed);
        return trimmed;
    }

    /** The active language code for transcription, trimmed/lower-cased, blank = auto-detect. */
    private static String language() {
        VoxMagicaVoiceConfig config = config();
        String language = config == null ? null : config.getSttLanguage();
        if (language == null || language.isBlank()) {
            return "";
        }
        return language.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whisper has English-specific model variants (suffixed {@code .en}) but no per-language
     * variants for other idioms. For {@code "en"} use the English-only model; for any other
     * language (or auto-detect) use the multilingual model. Speaches-specific naming - see
     * {@link LocalWhisperModelCatalog#defaultModelFor} for the local provider's equivalent.
     */
    private static String defaultModelFor(String language) {
        if ("en".equalsIgnoreCase(language)) {
            return "Systran/faster-whisper-base.en";
        }
        return "Systran/faster-whisper-base";
    }

    /** Like {@link #model()}, but whisper.cpp short names for {@code SttProvider=local}. */
    private static String localModelName() {
        VoxMagicaVoiceConfig config = config();
        String model = config == null ? null : config.getSttModel();
        if (model == null || model.isBlank()) {
            return LocalWhisperModelCatalog.defaultModelFor(language());
        }
        return model.trim();
    }

    /** Warns when an explicitly configured English-only model can't transcribe the chosen language. */
    private static void warnIfEnglishOnlyModel(String model) {
        String language = language();
        if (language.isEmpty() || "en".equalsIgnoreCase(language)) {
            return;
        }
        if (model.toLowerCase(java.util.Locale.ROOT).endsWith(".en")) {
            LOGGER.atWarning().log(
                "[VoxMagica] SttModel '%s' is an English-only whisper variant but SttLanguage is "
                    + "'%s'; set SttModel to a multilingual variant (e.g. Systran/faster-whisper-base) "
                    + "to transcribe '%s'.", model, language, language);
        }
    }

    /**
     * Muxes an utterance and transcribes it off-thread. Returns immediately.
     *
     * @param opusFrames raw Opus packets for one utterance, in arrival order
     * @param onResult   invoked on the STT pool with {@code (transcript, error)}; exactly one is
     *                   non-null. Never invoked if the call was skipped.
     */
    public static void transcribeAsync(@Nonnull List<byte[]> opusFrames,
                                       @Nonnull BiConsumer<String, Throwable> onResult) {
        // Guarantee: onResult is invoked EXACTLY once on every path. GlyphUtteranceSession
        // re-sequences completions by a monotonically increasing stamp, so if a call were skipped
        // without a callback, every later utterance for that player would deadlock waiting for the
        // missing sequence. Skipped calls report (null, null), which handleTranscription treats as
        // "no speech" and drops silently.
        if (opusFrames.isEmpty() || !isConfigured()) {
            onResult.accept(null, null);
            return;
        }

        String provider = providerId();
        boolean local = VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(provider);
        boolean speaches = VoxMagicaVoiceConfig.PROVIDER_SPEACHES.equalsIgnoreCase(provider);
        boolean openai = VoxMagicaVoiceConfig.PROVIDER_OPENAI.equalsIgnoreCase(provider);
        if (!local && !speaches && !openai) {
            if (WARNED_UNKNOWN_PROVIDER.compareAndSet(false, true)) {
                LOGGER.atWarning().log(
                    "[VoxMagica] Unsupported SttProvider '%s'; expected '%s', '%s' or '%s'. "
                        + "This warning is logged once.",
                    provider, VoxMagicaVoiceConfig.PROVIDER_LOCAL,
                    VoxMagicaVoiceConfig.PROVIDER_SPEACHES, VoxMagicaVoiceConfig.PROVIDER_OPENAI);
            }
            onResult.accept(null, null);
            return;
        }

        long durationMillis = OggOpusMuxer.durationMillis(opusFrames);
        if (durationMillis < MIN_UTTERANCE_MILLIS) {
            onResult.accept(null, null);
            return;
        }

        ThreadPoolExecutor pool = executor;
        if (pool == null) {
            onResult.accept(null, null); // Shut down mid-flight.
            return;
        }

        if (local) {
            // No Ogg container, no HTTP - LocalTranscriber decodes the raw Opus packets directly.
            String language = language();
            try {
                pool.execute(() -> LocalTranscriber.transcribeAsync(
                    opusFrames, language.isEmpty() ? null : language, onResult));
            } catch (RejectedExecutionException e) {
                LOGGER.atWarning().log(
                    "[VoxMagica] STT backlog full (%d queued); dropping a %d ms utterance",
                    MAX_QUEUED_UTTERANCES, durationMillis);
                onResult.accept(null, null);
            }
            return;
        }

        HttpClient client = httpClient;
        if (client == null) {
            onResult.accept(null, null); // Shut down mid-flight.
            return;
        }

        final byte[] ogg;
        try {
            ogg = OggOpusMuxer.mux(opusFrames, GlyphVoiceStreamTap.VOICE_CHANNELS);
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("[VoxMagica] Failed to mux utterance to Ogg-Opus");
            onResult.accept(null, null);
            return;
        }

        if (ogg.length > MAX_UPLOAD_BYTES) {
            LOGGER.atWarning().log(
                "[VoxMagica] Utterance is %d bytes, over the %d byte provider limit; skipping",
                ogg.length, MAX_UPLOAD_BYTES);
            onResult.accept(null, null);
            return;
        }

        String endpoint = baseUrl() + TRANSCRIPTIONS_PATH;
        String key = openai ? apiKey() : "";

        try {
            pool.execute(() -> runTranscription(client, endpoint, key, ogg, durationMillis, onResult, speaches));
        } catch (RejectedExecutionException e) {
            LOGGER.atWarning().log(
                "[VoxMagica] STT backlog full (%d queued); dropping a %d ms utterance",
                MAX_QUEUED_UTTERANCES, durationMillis);
            onResult.accept(null, null);
        }
    }

    private static void runTranscription(HttpClient client, String endpoint, String apiKey,
                                         byte[] ogg, long durationMillis,
                                         BiConsumer<String, Throwable> onResult,
                                         boolean vadFilter) {
        try {
        String boundary = "VoxMagica" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(boundary, ogg, model(), language(), vadFilter);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            long startedAt = System.currentTimeMillis();
            HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - startedAt;

            if (response.statusCode() != 200) {
                onResult.accept(null, new java.io.IOException(
                    "STT HTTP " + response.statusCode() + ": " + truncate(response.body(), 500)));
                return;
            }

            String transcript = extractTranscript(response.body());
            if (transcript == null) {
                onResult.accept(null, new java.io.IOException(
                    "STT response had no 'text' field: " + truncate(response.body(), 300)));
                return;
            }

            LOGGER.atInfo().log(
                "[VoxMagica] Transcribed %d ms of audio (%d bytes) in %d ms -> %d chars",
                durationMillis, ogg.length, elapsed, transcript.length());

            onResult.accept(transcript, null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            onResult.accept(null, e);
        }
    }

    /**
     * Builds an RFC 7578 multipart/form-data body with the {@code model}, optional {@code language}
     * and {@code file} parts.
     */
    static byte[] buildMultipartBody(String boundary, byte[] ogg, String model, String language,
                                     boolean vadFilter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(ogg.length + 512);
        String dashBoundary = "--" + boundary;

        writeAscii(out, dashBoundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"model\"\r\n\r\n");
        writeAscii(out, model + "\r\n");

        if (vadFilter) {
            // speaches-only: run a voice-activity detector first so silence/quiet clips are
            // rejected as no-speech instead of hallucinated into "I'm sorry, I'm sorry, ...".
            writeAscii(out, dashBoundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"vad_filter\"\r\n\r\n");
            writeAscii(out, "true\r\n");
        }

        if (!language.isEmpty()) {
            writeAscii(out, dashBoundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"language\"\r\n\r\n");
            writeAscii(out, language + "\r\n");
        }

        writeAscii(out, dashBoundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\"utterance.ogg\"\r\n");
        writeAscii(out, "Content-Type: audio/ogg\r\n\r\n");
        out.write(ogg, 0, ogg.length);
        writeAscii(out, "\r\n");

        writeAscii(out, dashBoundary + "--\r\n");
        return out.toByteArray();
    }

    /** Pulls {@code text} out of the provider's JSON response, e.g. {@code {"text": "..."}}. */
    static String extractTranscript(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("text")) {
                return null;
            }
            String text = object.get("text").getAsString();
            return text == null ? null : text.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
