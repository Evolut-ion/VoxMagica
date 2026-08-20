package com.ev0smods.voxmagica.commands;

import com.ev0smods.voxmagica.glyph.GlyphShapeSynthesizer;
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
import com.riprod.hexcode.core.common.drawing.component.DrawnShapeComponent;
import com.riprod.hexcode.core.common.drawing.system.GlyphCreationManager;
import com.riprod.hexcode.core.common.glyphs.registry.GlyphAsset;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * {@code /voxmagica testmatch <glyphId>} - offline sanity check for
 * {@link GlyphShapeSynthesizer}, with no voice/STT/casting session required.
 *
 * <p>Synthesizes the shape list for the named asset, runs it through the exact same
 * {@code GlyphCreationManager.NormalizeShapeSizes}/{@code MatchGlyph} pair Hexcode itself calls
 * on a real drawn glyph, and reports whether it matched the same asset. This is what proves the
 * shape-synthesis math (see {@link GlyphShapeSynthesizer}'s javadoc) before any voice capture is
 * involved at all.
 */
public class VoxMagicaTestMatchCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> glyphIdArg;

    public VoxMagicaTestMatchCommand() {
        super("testmatch", "Verifies voice-cast shape synthesis against Hexcode's own glyph matcher.");
        this.glyphIdArg = withRequiredArg("glyphId",
            "The Hexcode glyph asset id to test, e.g. Add", ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull World world) {
        String glyphId = glyphIdArg.get(commandContext);

        GlyphAsset asset = GlyphAsset.getAssetMap().getAsset(glyphId);
        if (asset == null) {
            commandContext.sendMessage(Message.raw("No such glyph: '" + glyphId + "'").color("#FF5555"));
            return;
        }

        List<DrawnShapeComponent> synthesized = GlyphShapeSynthesizer.synthesize(asset);
        GlyphCreationManager.NormalizeShapeSizes(synthesized);
        GlyphAsset matched = GlyphCreationManager.MatchGlyph(synthesized);

        boolean success = matched != null && matched.getId().equals(asset.getId());
        commandContext.sendMessage(Message.raw(success
                ? "PASS: synthesized shapes for '" + glyphId + "' matched back to themselves."
                : "FAIL: synthesized shapes for '" + glyphId + "' matched "
                    + (matched == null ? "nothing" : "'" + matched.getId() + "'") + " instead.")
            .color(success ? "#55FF55" : "#FF5555"));
    }
}
