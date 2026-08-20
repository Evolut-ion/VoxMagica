package com.ev0smods.voxmagica.glyph;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.riprod.hexcode.core.common.drawing.component.DrawnShapeComponent;
import com.riprod.hexcode.core.common.drawing.registry.ShapeAsset;
import com.riprod.hexcode.core.common.drawing.system.InterfaceManager;
import com.riprod.hexcode.core.common.glyphs.registry.GlyphAsset;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a synthetic {@code List<DrawnShapeComponent>} that reproduces a {@link GlyphAsset}'s own
 * shape signature exactly, so {@code GlyphCreationManager.MatchGlyph} scores it 1.0 against that
 * asset - the same match Hexcode would compute from a real hand-drawn stroke sequence.
 *
 * <p>As of Hexcode 0.9.0, {@code GlyphAsset.getShapes()} returns {@code List<ShapeAsset>} (each
 * shape is now its own registered asset, resolved by id), not {@code List<DrawnShapeComponent>}
 * as in 0.8.x. {@code ShapeAsset.getBaseShapeId()} is the equivalent of the old
 * {@code DrawnShapeComponent.getShapeId()} - it resolves to the shape's own id, or its parent's id
 * for a shape that inherits from a base shape. {@code GlyphCreationManager.NormalizeShapeSizes}
 * (which Hexcode always runs before matching) recomputes each drawn shape's {@code relativeSize}
 * as {@code size / maxSize} across the drawn list - so copying each asset shape's stored
 * {@code relativeSize} into both the synthetic {@code size} and {@code relativeSize} constructor
 * arguments reproduces the asset's own proportions after that normalization runs.
 *
 * <h2>World-space points</h2>
 * A real drawn shape also carries {@code points}: actual world positions
 * {@code StrokeCapture.recognizeStroke} computes via
 * {@code InterfaceManager.getPositionsFromAngles(accessor, angles, playerRef, 4.0f)} - a raycast
 * 4 blocks out from the player's eyes along the angles they drew. The pedestal/crafting spawn path
 * ({@code CraftingGlyphSpawner.calculateDrawCenter}, unchanged in 0.9.0 aside from its new home)
 * averages every shape's points to find where to spawn the glyph, and fails silently ("missing
 * draw position") if none of the shapes have any - the in-air/flycasting path does not need points
 * at all (it spawns relative to a casting-root anchor instead), so setting them unconditionally is
 * harmless there. Since a spoken glyph has no real stroke, {@link #synthesizeAtGaze} fills this in
 * itself, using two copies of the player's <b>current</b> head angle so
 * {@code getPositionsFromAngles} (which needs at least two angle samples to produce any output at
 * all) resolves to a single point straight ahead - the same 4-block distance a real draw uses, so
 * it lands inside a pedestal's normal crafting radius.
 */
public final class GlyphShapeSynthesizer {

    /** Matches the distance {@code StrokeCapture.recognizeStroke} raycasts real drawn points at. */
    private static final float DRAW_DISTANCE_BLOCKS = 4.0f;

    private GlyphShapeSynthesizer() {
    }

    @Nonnull
    public static List<DrawnShapeComponent> synthesize(@Nonnull GlyphAsset asset) {
        List<DrawnShapeComponent> synthesized = new ArrayList<>();
        for (ShapeAsset shape : asset.getShapes()) {
            float relativeSize = shape.getRelativeSize();
            synthesized.add(new DrawnShapeComponent(shape.getBaseShapeId(), relativeSize, relativeSize, 1.0f));
        }
        return synthesized;
    }

    /**
     * Same as {@link #synthesize}, but also sets each shape's {@code points} to a single position
     * {@link #DRAW_DISTANCE_BLOCKS} blocks in front of {@code playerRef}'s current head rotation -
     * without this, Hexcode's own spawn logic has no position to work with and silently no-ops.
     */
    @Nonnull
    public static List<DrawnShapeComponent> synthesizeAtGaze(@Nonnull GlyphAsset asset,
                                                               @Nonnull ComponentAccessor<EntityStore> accessor,
                                                               @Nonnull Ref<EntityStore> playerRef,
                                                               @Nullable HeadRotation head) {
        List<DrawnShapeComponent> shapes = synthesize(asset);
        if (shapes.isEmpty() || head == null) {
            return shapes;
        }

        float yaw = head.getRotation().y;
        float pitch = head.getRotation().x;
        FloatArrayList angles = new FloatArrayList();
        angles.add(yaw);
        angles.add(pitch);
        angles.add(yaw);
        angles.add(pitch);

        List<Vector3d> gazePoints =
            InterfaceManager.getPositionsFromAngles(accessor, angles, playerRef, DRAW_DISTANCE_BLOCKS);
        for (DrawnShapeComponent shape : shapes) {
            shape.setPoints(gazePoints);
        }
        return shapes;
    }
}
