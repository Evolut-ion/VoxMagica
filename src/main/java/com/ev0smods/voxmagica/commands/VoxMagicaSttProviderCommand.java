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
 * {@code /voxmagica sttprovider <local|speaches|openai|off>} - sets the server-wide
 * {@code SttProvider}. Admin-only ({@code voxmagica.admin}): unlike the per-player
 * {@code /voxmagica voice} consent toggle, this changes transcription for every player on this
 * save, and always requires a restart - {@code GlyphVoiceStreamTap} decides once at plugin setup
 * whether to install the capture tap at all (see {@code GlyphSttClient#isConfigured}).
 */
public class VoxMagicaSttProviderCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> providerArg;

    public VoxMagicaSttProviderCommand() {
        super("sttprovider", "Sets the speech-to-text provider (admin-only, affects every player).");
        this.requirePermission("voxmagica.admin");
        this.providerArg = withRequiredArg("provider",
            "\"local\" (in-process, recommended), \"speaches\", \"openai\", or \"off\" to disable "
                + "voice capture entirely", ArgTypes.STRING);
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

        String requested = providerArg.get(commandContext).trim();
        String value = "off".equalsIgnoreCase(requested) ? "" : requested.toLowerCase(java.util.Locale.ROOT);
        if (!value.isEmpty()
            && !VoxMagicaVoiceConfig.PROVIDER_LOCAL.equals(value)
            && !VoxMagicaVoiceConfig.PROVIDER_SPEACHES.equals(value)
            && !VoxMagicaVoiceConfig.PROVIDER_OPENAI.equals(value)) {
            commandContext.sendMessage(Message.raw(
                    "VoxMagica: unknown provider '" + requested + "'; expected local, speaches, openai, or off.")
                .color("#FF5555"));
            return;
        }

        Config<VoxMagicaVoiceConfig> config = plugin.getVoiceConfig();
        config.get().setSttProvider(value);
        config.save();

        commandContext.sendMessage(Message.raw(
                "VoxMagica: SttProvider set to " + (value.isEmpty() ? "off" : "'" + value + "'")
                    + ". Restart the server for this to take effect.")
            .color("#55FF55"));
    }
}
