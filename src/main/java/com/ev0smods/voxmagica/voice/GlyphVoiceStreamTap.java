package com.ev0smods.voxmagica.voice;

import com.ev0smods.voxmagica.VoxMagicaPlugin;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.io.ConnectionHandler;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.VoiceData;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.io.stream.StreamManager;
import com.hypixel.hytale.server.core.modules.voice.VoiceStreamHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound voice capture for voice-cast recognition. Ported from VerityHE's
 * {@code VoicePacketSpike} - see that class's javadoc for the full provenance of why inbound
 * {@code VoiceData} does not flow through {@code PacketAdapters} on this server build (voice
 * travels on a separate auxiliary QUIC stream) and why the {@code StreamManager} tap below is the
 * path that actually observes it. That finding is server-build behavior, not VerityHE-specific,
 * so it applies here unchanged.
 *
 * <p>Trimmed relative to the original: no companion proximity/consent gating (there is no
 * companion here) - capture is gated purely on {@link PlayerVoiceConsent}, toggled with
 * {@code /voxmagica voice <true|false>}. Whether the player is actually mid-cast is checked later,
 * on the world thread, by {@code VoiceGlyphInjector} once a transcript is ready - this class only
 * decides whether to record and transcribe at all.
 */
public final class GlyphVoiceStreamTap {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Confirmed hardcoded in {@code VoiceRouter.sendVoiceConfig}. */
    public static final int VOICE_SAMPLE_RATE_HZ = 48_000;

    /** Confirmed hardcoded in {@code VoiceRouter.sendVoiceConfig} — mono. */
    public static final int VOICE_CHANNELS = 1;

    private static final Map<UUID, GlyphUtteranceSession> SESSIONS = new ConcurrentHashMap<>();

    private static volatile PacketFilter inboundWatcher;
    private static volatile boolean streamTapInstalled;

    private GlyphVoiceStreamTap() {
    }

    /** Installs the tap. Call once from {@code VoxMagicaPlugin.setup()}. */
    public static void register(@Nonnull VoxMagicaPlugin plugin) {
        GlyphSttClient.init(plugin.getVoiceConfig(), plugin);

        // Harmless even though inbound VoiceData never reaches PacketAdapters on this build - kept
        // so capture starts working with no code change if a future server build changes that.
        inboundWatcher = PacketAdapters.registerInbound((PlayerPacketWatcher) GlyphVoiceStreamTap::onInboundPacket);

        boolean tapWanted = GlyphSttClient.isConfigured();
        LOGGER.atInfo().log(
            "[VoxMagica] Registered. Audio: %d Hz, %d channel(s), Opus. Capture tap: %s",
            VOICE_SAMPLE_RATE_HZ, VOICE_CHANNELS, tapWanted ? "ON" : "off (STT unconfigured)");

        if (tapWanted) {
            installVoiceStreamTap();
        }
    }

    /** Removes the inbound watcher and flushes any open sessions. Call from {@code shutdown()}. */
    public static void unregister() {
        PacketFilter watcher = inboundWatcher;
        if (watcher != null) {
            PacketAdapters.deregisterInbound(watcher);
            inboundWatcher = null;
        }
        SESSIONS.values().forEach(GlyphUtteranceSession::close);
        SESSIONS.clear();
        GlyphSttClient.shutdown();
    }

    /**
     * Closes out utterances that ended without a following frame to trigger the boundary check.
     * Safe to call every tick.
     */
    public static void tickSessions() {
        long now = System.currentTimeMillis();
        SESSIONS.values().forEach(session -> session.flushIfIdle(now));
    }

    /** Drops all state for a disconnecting player. */
    public static void onPlayerRemoved(@Nonnull UUID playerUuid) {
        PlayerVoiceConsent.clear(playerUuid);
        GlyphUtteranceSession session = SESSIONS.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    // --- Capture path ---

    private static void onInboundPacket(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet instanceof VoiceData voiceData) {
            handleVoiceData(playerRef, voiceData);
        }
    }

    /**
     * Common capture entry point, reached from the {@code StreamManager} tap. Runs on a Netty I/O
     * thread — must not block and must not touch the ECS.
     */
    static void handleVoiceData(@Nonnull PlayerRef playerRef, @Nonnull VoiceData voiceData) {
        UUID playerUuid = playerRef.getUuid();

        if (!PlayerVoiceConsent.isEnabled(playerUuid)) {
            endSessionIfAny(playerUuid);
            return;
        }

        GlyphUtteranceSession session = SESSIONS.computeIfAbsent(playerUuid,
            uuid -> new GlyphUtteranceSession(playerRef));
        session.acceptFrame(voiceData.opusData, System.currentTimeMillis());
    }

    private static void endSessionIfAny(UUID playerUuid) {
        GlyphUtteranceSession session = SESSIONS.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    // --- StreamManager tap (the only path that observes voice on this build) ---

    private static synchronized void installVoiceStreamTap() {
        if (streamTapInstalled) {
            return;
        }
        StreamManager.getInstance().registerHandler(StreamType.Voice, TappedVoiceStreamHandler::new);
        streamTapInstalled = true;
        LOGGER.atWarning().log(
            "[VoxMagica] Voice stream tap installed: StreamType.Voice now routes through "
                + "VoxMagica's delegating handler. Vanilla voice is forwarded unchanged. If in-game "
                + "proximity chat misbehaves, clear SttProvider in VoxMagicaVoiceConfig to stop "
                + "installing this tap.");
    }

    /**
     * Delegating {@code ConnectionHandler} for the voice stream. Every callback is forwarded to a
     * genuine {@code VoiceStreamHandler} so routing, rate limiting, mute handling and position
     * caching all continue to behave exactly as vanilla; we only observe {@code VoiceData} on the
     * way through.
     */
    private static final class TappedVoiceStreamHandler implements ConnectionHandler {

        private final VoiceStreamHandler delegate;
        private final PacketHandler packetHandler;

        TappedVoiceStreamHandler(PacketHandler packetHandler, ChannelConnection channel) {
            this.packetHandler = packetHandler;
            this.delegate = new VoiceStreamHandler(packetHandler, channel);
        }

        @Override
        public void handle(ToServerPacket packet) {
            if (packet instanceof VoiceData voiceData) {
                try {
                    PlayerRef playerRef = resolvePlayerRef();
                    if (playerRef != null) {
                        handleVoiceData(playerRef, voiceData);
                    }
                } catch (RuntimeException e) {
                    // Never let voice-casting capture break real voice chat.
                    LOGGER.atWarning().withCause(e).log("[VoxMagica] Voice tap threw; ignoring");
                }
            }
            delegate.handle(packet);
        }

        private PlayerRef resolvePlayerRef() {
            return packetHandler instanceof GamePacketHandler gameHandler
                ? gameHandler.getPlayerRef()
                : null;
        }

        @Override
        public void closed(NetworkChannel channel) {
            PlayerRef playerRef = resolvePlayerRef();
            if (playerRef != null) {
                endSessionIfAny(playerRef.getUuid());
            }
            delegate.closed(channel);
        }

        @Override
        public void logCloseMessage() {
            delegate.logCloseMessage();
        }

        @Override
        public void registered(ConnectionHandler handler) {
            delegate.registered(handler);
        }

        @Override
        public void unregistered(ConnectionHandler handler) {
            delegate.unregistered(handler);
        }
    }
}
