package com.ev0smods.voxmagica.voice;

import com.ev0smods.voxmagica.glyph.VoiceGlyphInjector;
import com.ev0smods.voxmagica.voice.local.LocalModelNotReadyException;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player capture state for one voice-casting session. Ported from VerityHE's
 * {@code VoiceCaptureSession}, trimmed to recognition-only: no TTS talk-back, no companion
 * awareness, no debug-dump-to-disk plumbing. A finished utterance is transcribed and handed
 * straight to {@link VoiceGlyphInjector}.
 *
 * <p>An instance accumulates inbound {@code VoiceData} frames into "utterances": a run of frames
 * with no large silence gap between them. When the gap since the previous frame exceeds
 * {@link #SILENCE_GAP_MS}, the buffered run is closed out and a new run begins.
 *
 * <h2>Threading</h2>
 * Every frame arrives on a Netty I/O thread belonging to that player's voice QUIC stream, so all
 * calls into a single session instance are naturally serialized. Do <b>not</b> share a session
 * between players, and do not call {@link #acceptFrame} from the world thread.
 *
 * <p>STT completions arrive on {@code GlyphSttClient}'s multi-threaded pool, potentially out of
 * order; {@link #deliverInOrder} re-sequences them so this player's casts stay in spoken order.
 */
public final class GlyphUtteranceSession {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Gap between frames that ends an utterance. */
    public static final long SILENCE_GAP_MS = 500L;

    /**
     * Hard cap on buffered frames per utterance, so a stuck open mic cannot grow the heap without
     * bound. At the real 20 ms frame cadence this is roughly 60 seconds of speech.
     */
    public static final int MAX_FRAMES_PER_UTTERANCE = 3000;

    private final PlayerRef speaker;
    private final UUID playerUuid;
    private final String playerName;

    private final List<byte[]> currentFrames = new ArrayList<>();
    private long currentTotalBytes;
    private long lastFrameMillis;
    private int droppedFrames;
    private int utteranceCount;

    /**
     * Re-orders completed transcriptions back into spoken order. STT now runs on a multi-threaded
     * pool, so two utterances from the same player can finish out of order; each is stamped with a
     * monotonically increasing sequence and only handed to the injector when all earlier ones have
     * been delivered.
     */
    private final Object deliveryLock = new Object();
    private final java.util.TreeMap<Integer, Runnable> pendingDeliveries = new java.util.TreeMap<>();
    private int nextDeliverySeq = 0;
    private int nextStampSeq = 0;

    public GlyphUtteranceSession(PlayerRef speaker) {
        this.speaker = speaker;
        this.playerUuid = speaker.getUuid();
        String name = speaker.getUsername();
        this.playerName = (name == null || name.isEmpty()) ? "unknown" : name;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * Feeds one captured voice frame into the session, closing out the previous utterance first if
     * enough silence has elapsed.
     *
     * @param opusData  the raw Opus packet; copied defensively, never retained by reference
     * @param nowMillis server wall-clock time for this frame
     */
    public void acceptFrame(byte[] opusData, long nowMillis) {
        if (opusData == null || opusData.length == 0) {
            return;
        }

        if (!currentFrames.isEmpty() && (nowMillis - lastFrameMillis) > SILENCE_GAP_MS) {
            endUtterance();
        }

        if (currentFrames.isEmpty()) {
            currentTotalBytes = 0L;
            droppedFrames = 0;
        }

        if (currentFrames.size() >= MAX_FRAMES_PER_UTTERANCE) {
            // Refuse to grow further, but keep the session alive; the next silence gap flushes it.
            droppedFrames++;
            lastFrameMillis = nowMillis;
            return;
        }

        // The netty buffer backing this array is recycled after the handler returns, so copy.
        byte[] copy = new byte[opusData.length];
        System.arraycopy(opusData, 0, copy, 0, opusData.length);

        currentFrames.add(copy);
        currentTotalBytes += copy.length;
        lastFrameMillis = nowMillis;
    }

    /**
     * Closes out the in-progress utterance if one exists and enough silence has passed. Call this
     * periodically so the final utterance of a session is flushed even though no further frames
     * arrive to trigger the boundary check in {@link #acceptFrame}.
     */
    public boolean flushIfIdle(long nowMillis) {
        if (currentFrames.isEmpty() || (nowMillis - lastFrameMillis) <= SILENCE_GAP_MS) {
            return false;
        }
        endUtterance();
        return true;
    }

    /** Flushes any buffered audio unconditionally. Call when the player disconnects or moves away. */
    public void close() {
        if (!currentFrames.isEmpty()) {
            endUtterance();
        }
    }

    private void endUtterance() {
        List<byte[]> frames = new ArrayList<>(currentFrames);
        long totalBytes = currentTotalBytes;
        int dropped = droppedFrames;

        currentFrames.clear();
        currentTotalBytes = 0L;
        droppedFrames = 0;
        utteranceCount++;

        LOGGER.atInfo().log(
            "[VoxMagica] Utterance #%d from %s: %d frames, %d bytes%s",
            utteranceCount, playerName, frames.size(), totalBytes,
            dropped > 0 ? (" (" + dropped + " frames dropped: utterance cap hit)") : "");

        onUtteranceComplete(frames);
    }

    /**
     * Invoked once per completed utterance, with the raw Opus packets in arrival order. Runs on
     * the voice stream's Netty I/O thread, so this must not block -
     * {@link GlyphSttClient#transcribeAsync} returns immediately.
     */
    private void onUtteranceComplete(List<byte[]> opusFrames) {
        // TranscribeAsync callbacks fire on the STT pool, which is multi-threaded; stamp this
        // utterance with the next sequence so completions can be re-ordered into spoken order.
        final int seq = nextStampSeq++;

        GlyphSttClient.transcribeAsync(opusFrames, (transcript, error) ->
            deliverInOrder(seq, () -> handleTranscription(transcript, error)));
    }

    /** The per-utterance completion work; runs in spoken order on whichever STT pool thread drains it. */
    private void handleTranscription(String transcript, Throwable error) {
        if (error instanceof LocalModelNotReadyException) {
            // Expected transient state on a fresh local-provider install, not a bug - a friendly
            // retry nudge instead of a warning log.
            if (speaker.isValid()) {
                speaker.sendMessage(Message.raw(
                        "VoxMagica: still downloading the local speech model (one-time only) - "
                            + "try again in a minute.")
                    .color("#FFAA55"));
            }
            return;
        }
        if (error != null) {
            LOGGER.atWarning().withCause(error).log(
                "[VoxMagica] Transcription failed for %s", playerName);
            return;
        }
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        if (isJunkTranscript(transcript)) {
            LOGGER.atInfo().log(
                "[VoxMagica] Discarding likely STT junk transcript from %s: \"%s\"",
                playerName, transcript.length() > 80 ? transcript.substring(0, 80) + "..." : transcript);
            return;
        }
        LOGGER.atInfo().log("[VoxMagica] %s said: \"%s\"", playerName, transcript);
        VoiceGlyphInjector.handleTranscript(speaker, transcript);
    }

    /**
     * Hands {@code runnable} to the injector only once every earlier utterance for this player has
     * been delivered. Safe to call from any STT pool thread.
     */
    private void deliverInOrder(int seq, Runnable runnable) {
        synchronized (deliveryLock) {
            pendingDeliveries.put(seq, runnable);
            Runnable ready;
            while ((ready = pendingDeliveries.get(nextDeliverySeq)) != null) {
                pendingDeliveries.remove(nextDeliverySeq);
                nextDeliverySeq++;
                ready.run();
            }
        }
    }

    /**
     * True when {@code transcript} is Whisper junk rather than real speech: repetition-loop
     * hallucinations ("Okay. Okay. Okay..." or comma-separated "I'm sorry, I'm sorry, ..."),
     * pure-punctuation noise (". . . .", "???!"), or all-digits chants ("1-2-3-4-4-4...").
     * Ported and hardened from VerityHE's {@code VoiceCaptureSession}; the original split only on
     * sentence punctuation, so comma-separated loops collapsed into one giant fragment and slipped
     * through.
     */
    static boolean isJunkTranscript(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return true;
        }
        String trimmed = transcript.trim();

        // Pure punctuation / ellipsis / digits-with-separators noise: ". . . .", "???!", "1-2-3-4".
        String lettersOnly = trimmed.replaceAll("[^a-zA-Z]", "");
        if (lettersOnly.isEmpty()) {
            return true;
        }
        // "1-2-3-4-4-4-4..." - at least two real words are required before we trust a transcript.
        String[] words = trimmed.split("\\s+");
        if (words.length >= 3 && lettersOnly.length() <= 2) {
            return true;
        }

        // Repetition loop: split on any clause punctuation (sentence end AND commas), then check the
        // unique-clause ratio. "I'm sorry, I'm sorry, ..." now yields many short identical clauses.
        String[] rawFragments = trimmed.split("[,.;!?]+");
        List<String> fragments = new ArrayList<>();
        for (String fragment : rawFragments) {
            String cleaned = fragment.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) {
                fragments.add(cleaned);
            }
        }
        if (fragments.size() < 4) {
            return false;
        }
        Set<String> unique = new HashSet<>(fragments);
        // Fewer than a third distinct clauses means the model is stuck in a loop.
        return unique.size() <= Math.max(1, fragments.size() / 3);
    }
}
