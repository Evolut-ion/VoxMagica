package com.ev0smods.voxmagica.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player opt-in for voice-cast capture, toggled with {@code /voxmagica voice <true|false>}.
 *
 * <p>Capture is gated on this flag alone, checked from the Netty voice-stream thread where
 * touching the ECS directly is not safe (see {@code GlyphVoiceStreamTap}). Whether the player is
 * actually mid-cast right now is a separate, later check done on the world thread once a
 * transcript is ready (see {@code com.ev0smods.voxmagica.glyph.VoiceGlyphInjector}) - this flag
 * only controls whether their speech is captured and sent to STT at all. Defaults to off.
 */
public final class PlayerVoiceConsent {

    private static final Map<UUID, Boolean> ENABLED = new ConcurrentHashMap<>();

    private PlayerVoiceConsent() {
    }

    public static boolean isEnabled(UUID playerUuid) {
        return ENABLED.getOrDefault(playerUuid, Boolean.FALSE);
    }

    public static void setEnabled(UUID playerUuid, boolean enabled) {
        if (enabled) {
            ENABLED.put(playerUuid, Boolean.TRUE);
        } else {
            ENABLED.remove(playerUuid);
        }
    }

    public static void clear(UUID playerUuid) {
        ENABLED.remove(playerUuid);
    }
}
