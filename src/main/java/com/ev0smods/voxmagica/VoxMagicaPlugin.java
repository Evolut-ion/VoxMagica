package com.ev0smods.voxmagica;

import com.ev0smods.voxmagica.commands.VoxMagicaCommand;
import com.ev0smods.voxmagica.config.VoxMagicaVoiceConfig;
import com.ev0smods.voxmagica.glyph.VoiceGlyphInjector;
import com.ev0smods.voxmagica.voice.GlyphVoiceStreamTap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import com.riprod.hexcode.api.event.GlyphDrawnEvent;

import javax.annotation.Nonnull;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Entrypoint for VoxMagica: voice-cast Hexcode glyphs by speaking their name while casting or
 * drawing. See {@code com.ev0smods.voxmagica.voice} for capture/STT and
 * {@code com.ev0smods.voxmagica.glyph} for recognition/injection into Hexcode.
 */
public class VoxMagicaPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static volatile VoxMagicaPlugin INSTANCE;

    /** How often idle utterance sessions are flushed - see {@link GlyphVoiceStreamTap#tickSessions()}. */
    private static final long SESSION_TICK_INTERVAL_MILLIS = 250L;

    // Config<T> registration MUST happen as a field initializer (or in the constructor), never
    // inside setup() - see the hytale-plugin-config skill.
    private final Config<VoxMagicaVoiceConfig> voiceConfig =
        this.withConfig("VoxMagicaVoiceConfig", VoxMagicaVoiceConfig.CODEC);

    private ScheduledFuture<?> sessionTickTask;

    public VoxMagicaPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        INSTANCE = this;
        LOGGER.atInfo().log("Hello from " + this.getName() + " version " + this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up plugin " + this.getName());

        voiceConfig.save();

        this.getCommandRegistry().registerCommand(new VoxMagicaCommand());

        GlyphVoiceStreamTap.register(this);

        // Drops any lingering capture session/consent flag for a player who disconnects mid-utterance.
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
            event -> GlyphVoiceStreamTap.onPlayerRemoved(event.getPlayerRef().getUuid()));

        // Lets VoiceGlyphInjector learn each voice-cast glyph's resulting Glyph instance so it
        // can wire up slot links for "next"-nested glyphs - see VoiceGlyphInjector's javadoc.
        this.getEventRegistry().registerGlobal(GlyphDrawnEvent.class, VoiceGlyphInjector::onGlyphDrawn);
    }

    @Override
    protected void start() {
        // Utterances only close out on a following frame's silence-gap check (see
        // GlyphUtteranceSession.acceptFrame) or on disconnect - a player who simply stops talking
        // mid-session needs this periodic flush so their last utterance still gets transcribed.
        sessionTickTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
            GlyphVoiceStreamTap::tickSessions,
            SESSION_TICK_INTERVAL_MILLIS, SESSION_TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static VoxMagicaPlugin getInstance() {
        return INSTANCE;
    }

    @Override
    protected void shutdown() {
        if (sessionTickTask != null) {
            sessionTickTask.cancel(false);
            sessionTickTask = null;
        }
        GlyphVoiceStreamTap.unregister();
        INSTANCE = null;
    }

    public Config<VoxMagicaVoiceConfig> getVoiceConfig() {
        return voiceConfig;
    }
}
