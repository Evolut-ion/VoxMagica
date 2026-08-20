package com.ev0smods.voxmagica.commands;

import com.ev0smods.voxmagica.VoxMagicaPlugin;
import com.ev0smods.voxmagica.config.VoxMagicaVoiceConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;

/**
 * {@code /voxmagica sttmodel <name>} - sets the server-wide {@code SttModel}. Admin-only
 * ({@code voxmagica.admin}): unlike the per-player {@code /voxmagica voice} consent toggle, this
 * changes transcription for every player on this save.
 *
 * <p>For {@link VoxMagicaVoiceConfig#PROVIDER_LOCAL}, {@code name} is a whisper.cpp short name
 * (e.g. {@code "base.en"} - see {@code LocalWhisperModelCatalog}); for the other providers it's
 * whatever model id that provider expects. {@code "auto"} clears it back to blank, letting
 * VoxMagica pick a default matching {@link VoxMagicaVoiceConfig#getSttLanguage()}.
 */
public class VoxMagicaSttModelCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> modelArg;

    public VoxMagicaSttModelCommand() {
        super("sttmodel", "Sets the speech-to-text model (admin-only, affects every player).");
        this.requirePermission("voxmagica.admin");
        this.modelArg = withRequiredArg("model",
            "Model name, or \"auto\" to let VoxMagica pick one for the current language",
            ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull World world) {
        VoxMagicaPlugin plugin = VoxMagicaPlugin.getInstance();
        if (plugin == null) {
            commandContext.sendMessage(Message.raw("VoxMagica is not ready yet.").color("#FF5555"));
            return;
        }

        String requested = modelArg.get(commandContext);
        String value = "auto".equalsIgnoreCase(requested) ? "" : requested.trim();

        Config<VoxMagicaVoiceConfig> config = plugin.getVoiceConfig();
        VoxMagicaVoiceConfig cfg = config.get();
        cfg.setSttModel(value);
        config.save();

        // Only the local provider's model is bound to a context loaded once at plugin setup -
        // speaches/openai read SttModel live on every voice-cast, so those apply immediately.
        boolean restartNeeded = VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(cfg.getSttProvider());
        commandContext.sendMessage(Message.raw(
                "VoxMagica: SttModel set to " + (value.isEmpty() ? "auto" : "'" + value + "'") + ". "
                    + (restartNeeded
                        ? "Restart the server for this to take effect (the local model loads once at startup)."
                        : "Takes effect on the next voice-cast."))
            .color("#55FF55"));
    }
}
