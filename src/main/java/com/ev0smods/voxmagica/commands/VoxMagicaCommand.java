package com.ev0smods.voxmagica.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /voxmagica} - parent command.
 * <ul>
 *     <li>{@code /voxmagica voice <true|false>} - {@link VoxMagicaVoiceCommand}</li>
 *     <li>{@code /voxmagica sttprovider <local|speaches|openai|off>} -
 *         {@link VoxMagicaSttProviderCommand} (admin-only)</li>
 *     <li>{@code /voxmagica sttmodel <name>} - {@link VoxMagicaSttModelCommand} (admin-only)</li>
 *     <li>{@code /voxmagica sttlanguage <code>} - {@link VoxMagicaSttLanguageCommand} (admin-only)</li>
 *     <li>{@code /voxmagica testmatch <glyphId>} - {@link VoxMagicaTestMatchCommand}</li>
 * </ul>
 */
public class VoxMagicaCommand extends AbstractPlayerCommand {

    public VoxMagicaCommand() {
        super("voxmagica", "Voice-cast Hexcode glyphs.");
        addSubCommand(new VoxMagicaVoiceCommand());
        addSubCommand(new VoxMagicaSttProviderCommand());
        addSubCommand(new VoxMagicaSttModelCommand());
        addSubCommand(new VoxMagicaSttLanguageCommand());
        addSubCommand(new VoxMagicaTestMatchCommand());
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull World world) {
        commandContext.sendMessage(Message.raw("VoxMagica - voice-cast Hexcode glyphs. Use /voxmagica help for sub-commands."));
    }
}
