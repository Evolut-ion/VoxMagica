package com.ev0smods.voxmagica.voice.local;

/**
 * Thrown (not logged as a warning - see {@code GlyphUtteranceSession#handleTranscription}) when a
 * player speaks before the local whisper model has finished downloading/loading. Expected
 * transient state on a fresh install, not a bug.
 */
public final class LocalModelNotReadyException extends RuntimeException {

    public LocalModelNotReadyException(String message) {
        super(message);
    }
}
