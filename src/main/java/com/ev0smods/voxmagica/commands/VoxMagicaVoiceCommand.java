package com.ev0smods.voxmagica.commands;

import com.ev0smods.voxmagica.voice.PlayerVoiceConsent;
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

import javax.annotation.Nonnull;

/**
 * {@code /voxmagica voice <true|false>} - opt in or out of voice-cast capture. Defaults to off;
 * see {@link PlayerVoiceConsent}. Whether a spoken glyph actually does anything still depends on
 * being mid-cast or mid-draw at speech time - this command only controls whether speech is
 * captured and sent to STT at all.
 */
public class VoxMagicaVoiceCommand extends AbstractPlayerCommand {

    private final RequiredArg<Boolean> enabledArg;

    public VoxMagicaVoiceCommand() {
        super("voice", "Enable or disable voice-cast glyph recognition for yourself.");
        this.enabledArg = withRequiredArg("enabled",
            "true to let VoxMagica listen while you're casting or drawing, false to revoke", ArgTypes.BOOLEAN);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull World world) {
        boolean enabled = enabledArg.get(commandContext);
        PlayerVoiceConsent.setEnabled(playerRef.getUuid(), enabled);
        commandContext.sendMessage(Message.raw(enabled
                ? "VoxMagica will now listen for glyph names while you cast or draw (make sure voice chat is enabled in your client settings)."
                : "VoxMagica will no longer listen to your voice.")
            .color(enabled ? "#55FF55" : "#FFAA55"));
    }
}
