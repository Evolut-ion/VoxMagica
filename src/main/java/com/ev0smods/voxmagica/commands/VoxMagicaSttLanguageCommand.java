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
 * {@code /voxmagica sttlanguage <code>} - sets the server-wide {@code SttLanguage}. Admin-only
 * ({@code voxmagica.admin}): unlike the per-player {@code /voxmagica voice} consent toggle, this
 * changes transcription for every player on this save.
 *
 * <p>{@code code} is a language code like {@code "en"}, {@code "es"} or {@code "de"};
 * {@code "auto"} clears it back to blank (Whisper's automatic mixed-language detection).
 */
public class VoxMagicaSttLanguageCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> languageArg;

    public VoxMagicaSttLanguageCommand() {
        super("sttlanguage", "Sets the speech-to-text language (admin-only, affects every player).");
        this.requirePermission("voxmagica.admin");
        this.languageArg = withRequiredArg("language",
            "Language code (e.g. en, es, de), or \"auto\" for automatic detection", ArgTypes.STRING);
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

        String requested = languageArg.get(commandContext);
        String value = "auto".equalsIgnoreCase(requested) ? "" : requested.trim();

        Config<VoxMagicaVoiceConfig> config = plugin.getVoiceConfig();
        VoxMagicaVoiceConfig cfg = config.get();
        cfg.setSttLanguage(value);
        config.save();

        boolean restartNeeded = VoxMagicaVoiceConfig.PROVIDER_LOCAL.equalsIgnoreCase(cfg.getSttProvider())
            && (cfg.getSttModel() == null || cfg.getSttModel().isBlank());
        commandContext.sendMessage(Message.raw(
                "VoxMagica: SttLanguage set to " + (value.isEmpty() ? "auto-detect" : "'" + value + "'") + ". "
                    + (restartNeeded
                        ? "Restart the server for this to take effect (it changes which local model "
                            + "auto-loads at startup)."
                        : "Takes effect on the next voice-cast."))
            .color("#55FF55"));
    }
}
