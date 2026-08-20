package com.ev0smods.voxmagica.glyph;

import com.ev0smods.voxmagica.VoxMagicaPlugin;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.riprod.hexcode.api.event.GlyphDrawnEvent;
import com.riprod.hexcode.builtin.hexCore.contexts.crafting.component.CraftingState;
import com.riprod.hexcode.builtin.hexCore.contexts.flycasting.component.FlycastingState;
import com.riprod.hexcode.core.common.drawing.component.DrawCaptureComponent;
import com.riprod.hexcode.core.common.drawing.component.DrawnShapeComponent;
import com.riprod.hexcode.core.common.glyphs.component.Glyph;
import com.riprod.hexcode.core.common.glyphs.component.GlyphComponent;
import com.riprod.hexcode.core.common.glyphs.component.Slot;
import com.riprod.hexcode.core.common.glyphs.registry.GlyphAsset;
import com.riprod.hexcode.core.common.glyphs.registry.SlotConfig;
import com.riprod.hexcode.core.common.hexes.component.Hex;
import com.riprod.hexcode.core.common.hexes.component.HexComponent;
import com.riprod.hexcode.utils.GlyphMath;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Turns recognized glyph names into the same effect Hexcode produces for a hand-drawn glyph.
 *
 * <p>Supports <b>multi-casting</b>: an entire utterance like {@code "on primary add one two"}
 * is split into its individual glyphs by {@link GlyphNameMatcher#matchSequence(String)} and each
 * is injected one after another, separated by a small configurable delay
 * ({@link com.ev0smods.voxmagica.config.VoxMagicaVoiceConfig#getMultiCastDelayMs()}), so a
 * player can chain an entire hex in a single spoken sentence.
 *
 * <p>Also supports <b>nesting</b>: saying "next" right after a glyph's name nests the glyph
 * spoken after it as a child of that glyph, filling one of its argument slots - the same outcome
 * as drawing (or dragging) a glyph onto another glyph's slot marker in Hexcode's own crafting UI.
 * {@code "add next one next two"} nests {@code one} into {@code add}, then {@code two} into
 * {@code one} (each "next" nests into the glyph spoken immediately before it - see
 * {@link GlyphNameMatcher.SequencedGlyph#isNestUnderPrevious()}).
 *
 * <p>Nesting works in both drawing modes:
 * <ul>
 *   <li><b>Pedestal crafting</b> - relies on Hexcode's {@code GlyphDrawnEvent} (captured by
 *       {@link #onGlyphDrawn}) to learn each spoken glyph's resulting {@link Glyph} instance,
 *       then mutates its slot links directly with
 *       {@link Glyph#addSlotLink(String, String)} on the pedestal's persistent hex graph.</li>
 *   <li><b>Flycasting</b> - each spoken glyph spawns its own single-glyph in-air hex, so nesting
 *       merges the two hexes the same way Hexcode's drag-to-nest does
 *       ({@code HexSpawner.MergeGlyphs}): the parent hex absorbs the child's hex data, the child
 *       glyph entity is reparented and remounted onto the parent glyph, and the child hex entity
 *       is removed. See {@link #mergeInAirHexes}.</li>
 * </ul>
 */
public final class VoiceGlyphInjector {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Finalize window opened for a voice-cast glyph. Kept short (a voice command has no
     * draw-stroke duration to debounce, unlike a real stroke) but non-zero so it lands on a
     * following tick rather than racing same-tick ordering.
     */
    private static final float FINALIZE_DELAY_SECONDS = 0.1f;

    /**
     * Extra buffer added after the last glyph's own multi-cast delay before
     * {@link #applyNesting} runs, giving its {@link #FINALIZE_DELAY_SECONDS} finalize window and
     * a tick or two of processing time to actually create the entity and fire
     * {@link GlyphDrawnEvent} before nesting is attempted.
     */
    private static final long NEST_SETTLE_BUFFER_MS = 500L;

    private VoiceGlyphInjector() {
    }

    /**
     * Entry point from {@code GlyphUtteranceSession}. Runs on the STT pool thread - everything
     * that touches Hexcode's components (reading the speaker's castable hexes, injecting drawn
     * strokes) is hopped onto the world thread.
     */
    public static void handleTranscript(@Nonnull PlayerRef speaker, @Nonnull String transcript) {
        if (!speaker.isValid()) {
            return;
        }
        Ref<EntityStore> playerEntityRef = speaker.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }
        Store<EntityStore> store = playerEntityRef.getStore();
        World world = store.getExternalData().getWorld();

        world.execute(() -> {
            try {
                handleOnWorldThread(speaker, playerEntityRef, store, world, transcript);
            } catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log(
                    "[VoxMagica] Voice transcript handling failed for %s", speaker.getUsername());
            }
        });
    }

    /**
     * Runs on the world thread. A transcript that names a full spell (a server-side saved hex,
     * or a hex the player named at the Seeker obelisk - re-read live from their hexbook on every
     * call) is cast as a whole and always wins. Individual glyph names are only recognised while
     * the player is actively casting/drawing; outside drawing mode glyph names are never matched.
     */
    private static void handleOnWorldThread(PlayerRef speaker, Ref<EntityStore> ref,
                                            Store<EntityStore> store, World world,
                                            String transcript) {
        // Named spells can be cast in any mode and always take priority over glyphs.
        SavedHexCaster.CastableSpell spell = SavedHexCaster.matchSpell(transcript, store, ref);
        if (spell != null) {
            LOGGER.atInfo().log("[VoxMagica] %s voice-cast spell '%s'",
                speaker.getUsername(), spell.getDisplayName());
            try {
                SavedHexCaster.castSpell(speaker, ref, store, spell);
            } catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log(
                    "[VoxMagica] Spell cast failed for %s", speaker.getUsername());
            }
            return;
        }

        // Outside drawing mode only full spell names are recognised - never individual glyphs.
        DrawCaptureComponent capture = store.getComponent(ref, DrawCaptureComponent.getComponentType());
        if (capture == null) {
            LOGGER.atInfo().log("[VoxMagica] No spell recognized in \"%s\" (not drawing)", transcript);
            if (speaker.isValid()) {
                speaker.sendMessage(Message.raw("VoxMagica: didn't recognize a spell name in \"" + transcript + "\"")
                    .color("#FFAA55"));
            }
            return;
        }

        List<GlyphNameMatcher.SequencedGlyph> sequence = GlyphNameMatcher.matchSequence(transcript);
        if (sequence.isEmpty()) {
            LOGGER.atInfo().log("[VoxMagica] No glyph recognized in \"%s\"", transcript);
            if (speaker.isValid()) {
                speaker.sendMessage(Message.raw("VoxMagica: didn't recognize a glyph name in \"" + transcript + "\"")
                    .color("#FFAA55"));
            }
            return;
        }

        // A glyph preceded by "next" nests as a child of the glyph matched right before it -
        // resolved here as a parent index per entry (-1 = top-level, cast as its own glyph).
        int[] parentIndexOf = new int[sequence.size()];
        boolean hasNesting = false;
        for (int i = 0; i < sequence.size(); i++) {
            boolean nest = i > 0 && sequence.get(i).isNestUnderPrevious();
            parentIndexOf[i] = nest ? i - 1 : -1;
            hasNesting |= nest;
        }

        // Nesting works on the pedestal's persistent hex graph, and - while flycasting - by
        // merging the in-air hexes each spoken glyph spawned (see {@link #mergeInAirHexes}).
        boolean pedestalContext = store.getComponent(ref, CraftingState.getComponentType()) != null;
        boolean flycastingContext = store.getComponent(ref, FlycastingState.getComponentType()) != null;
        if (hasNesting && !pedestalContext && !flycastingContext) {
            feedback(speaker, "Nesting glyphs with \"next\" only works while crafting at a pedestal or flycasting.");
            hasNesting = false;
            Arrays.fill(parentIndexOf, -1);
        }

        int delayMs = VoxMagicaPlugin.getInstance() != null
            ? VoxMagicaPlugin.getInstance().getVoiceConfig().get().getMultiCastDelayMs()
            : 250;

        if (!hasNesting) {
            for (int i = 0; i < sequence.size(); i++) {
                final GlyphAsset asset = sequence.get(i).getAsset();
                final int index = i;
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                    world.execute(() -> {
                        try {
                            inject(speaker, ref, store, asset, null);
                        } catch (Throwable t) {
                            LOGGER.atWarning().withCause(t).log(
                                "[VoxMagica] Voice-glyph injection failed for %s", speaker.getUsername());
                        }
                    });
                }, (long) delayMs * index, TimeUnit.MILLISECONDS);
            }
            return;
        }

        // Nesting requested: capture each spoken glyph's resulting Glyph object (via
        // GlyphDrawnEvent - see onGlyphDrawn) so the slot links can be wired once every glyph in
        // the utterance has actually been drawn.
        final Glyph[] created = new Glyph[sequence.size()];
        for (int i = 0; i < sequence.size(); i++) {
            final GlyphAsset asset = sequence.get(i).getAsset();
            final int index = i;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                world.execute(() -> {
                    try {
                        inject(speaker, ref, store, asset, glyph -> created[index] = glyph);
                    } catch (Throwable t) {
                        LOGGER.atWarning().withCause(t).log(
                            "[VoxMagica] Voice-glyph injection failed for %s", speaker.getUsername());
                    }
                });
            }, (long) delayMs * index, TimeUnit.MILLISECONDS);
        }

        long settleDelayMs = (long) delayMs * sequence.size() + NEST_SETTLE_BUFFER_MS;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            world.execute(() -> {
                try {
                    applyNesting(speaker, ref, store, sequence, parentIndexOf, created);
                } catch (Throwable t) {
                    LOGGER.atWarning().withCause(t).log(
                        "[VoxMagica] Voice-glyph nesting failed for %s", speaker.getUsername());
                }
            });
        }, settleDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Wires up the nesting once every glyph in a nesting utterance has (hopefully) been drawn.
     * Runs on the world thread.
     *
     * <p>In flycasting each spoken glyph spawned its own in-air hex, so nesting merges the hexes
     * via {@link #mergeInAirHexes}; on the pedestal the spoken glyphs are nodes of the persistent
     * hex graph and nesting just wires their slot links.
     */
    private static void applyNesting(PlayerRef speaker, Ref<EntityStore> ref, Store<EntityStore> store,
                                      List<GlyphNameMatcher.SequencedGlyph> sequence,
                                      int[] parentIndexOf, Glyph[] created) {
        if (store.getComponent(ref, FlycastingState.getComponentType()) != null) {
            applyNestingFlycasting(speaker, ref, store, sequence, parentIndexOf);
            return;
        }

        for (int i = 0; i < sequence.size(); i++) {
            int parentIndex = parentIndexOf[i];
            if (parentIndex < 0) {
                continue;
            }
            GlyphAsset childAsset = sequence.get(i).getAsset();
            GlyphAsset parentAsset = sequence.get(parentIndex).getAsset();
            Glyph child = created[i];
            Glyph parent = created[parentIndex];
            if (child == null || parent == null) {
                feedback(speaker, "Couldn't nest '" + childAsset.getId() + "' into '" + parentAsset.getId()
                    + "' - one of them wasn't created in time.");
                continue;
            }
            String slotKey = pickOpenSlotKey(parentAsset, parent);
            if (slotKey == null) {
                feedback(speaker, "'" + parentAsset.getId() + "' has no open slot to nest '"
                    + childAsset.getId() + "' into.");
                continue;
            }
            parent.addSlotLink(slotKey, child.getId());
            feedback(speaker, "Nested '" + childAsset.getId() + "' into '" + parentAsset.getId() + "'.");
        }
    }

    /**
     * Flycasting nesting: each spoken glyph spawned its own single-glyph in-air hex (tracked in
     * {@link FlycastingState#getActiveHexes()}), so "next" merges the child's hex into the
     * parent's hex the same way Hexcode's own drag-to-nest does ({@code HexSpawner.MergeGlyphs}):
     * the parent hex absorbs the child's hex data, the child glyph entities are reparented and
     * remounted onto the parent glyph, and the child hex entity is removed. Runs on the world
     * thread - {@link Store} writes are immediate and safe here because we are not inside a
     * system tick.
     */
    private static void applyNestingFlycasting(PlayerRef speaker, Ref<EntityStore> ref,
                                               Store<EntityStore> store,
                                               List<GlyphNameMatcher.SequencedGlyph> sequence,
                                               int[] parentIndexOf) {
        FlycastingState state = store.getComponent(ref, FlycastingState.getComponentType());
        if (state == null) {
            return;
        }
        List<Ref<EntityStore>> activeHexes = state.getActiveHexes();
        if (activeHexes == null || activeHexes.isEmpty()) {
            feedback(speaker, "Couldn't nest - no in-air hexes are active.");
            return;
        }

        // Each spoken glyph spawned exactly one single-glyph hex, appended to activeHexes in
        // speak order. Match them back by asset id so hand-drawn hexes (or hexes left over from
        // an earlier cast) are skipped.
        GlyphComponent[] rootGlyphs = new GlyphComponent[sequence.size()];
        Set<Ref<EntityStore>> claimed = new HashSet<>();
        for (int i = 0; i < sequence.size(); i++) {
            String assetId = sequence.get(i).getAsset().getId();
            for (int h = 0; h < activeHexes.size(); h++) {
                Ref<EntityStore> hexRef = activeHexes.get(h);
                if (hexRef == null || !hexRef.isValid() || claimed.contains(hexRef)) {
                    continue;
                }
                HexComponent hexComp = store.getComponent(hexRef, HexComponent.getComponentType());
                if (hexComp == null || hexComp.getHex() == null) {
                    continue;
                }
                String firstGlyphId = hexComp.getHex().getFirstGlyphId();
                Glyph firstGlyph = firstGlyphId == null ? null : hexComp.getHex().get(firstGlyphId);
                if (firstGlyph == null || !assetId.equals(firstGlyph.getGlyphId())) {
                    continue;
                }
                claimed.add(hexRef);
                Ref<EntityStore> rootGlyphRef = hexComp.getChildGlyphRef(firstGlyphId);
                if (rootGlyphRef != null && rootGlyphRef.isValid()) {
                    rootGlyphs[i] = store.getComponent(rootGlyphRef, GlyphComponent.getComponentType());
                }
                break;
            }
            if (rootGlyphs[i] == null) {
                feedback(speaker, "Couldn't nest '" + assetId + "' - its in-air glyph wasn't found.");
                return;
            }
        }

        for (int i = 0; i < sequence.size(); i++) {
            int parentIndex = parentIndexOf[i];
            if (parentIndex < 0) {
                continue;
            }
            GlyphAsset childAsset = sequence.get(i).getAsset();
            GlyphAsset parentAsset = sequence.get(parentIndex).getAsset();
            mergeInAirHexes(speaker, store, state, rootGlyphs[parentIndex], rootGlyphs[i],
                parentAsset.getId(), childAsset.getId());
        }
    }

    /**
     * Merges the child glyph's in-air hex into the parent glyph's hex, mirroring Hexcode's
     * {@code HexSpawner.MergeGlyphs}: absorbs the child's hex data into the parent's, reparents
     * the child's glyph entities under the parent glyph, remounts them onto it, removes the
     * child hex entity, and restyles the merged tree. Runs on the world thread.
     */
    private static void mergeInAirHexes(PlayerRef speaker, Store<EntityStore> store,
                                        FlycastingState state,
                                        GlyphComponent parentGlyph, GlyphComponent childGlyph,
                                        String parentName, String childName) {
        if (parentGlyph == null || childGlyph == null) {
            feedback(speaker, "Couldn't nest '" + childName + "' into '" + parentName + "'.");
            return;
        }
        HexComponent parentHex = store.getComponent(parentGlyph.getHexRef(), HexComponent.getComponentType());
        HexComponent childHex = store.getComponent(childGlyph.getHexRef(), HexComponent.getComponentType());
        if (parentHex == null || childHex == null
            || parentHex.getHex() == null || childHex.getHex() == null) {
            feedback(speaker, "Couldn't nest '" + childName + "' into '" + parentName + "'.");
            return;
        }
        Ref<EntityStore> childHexRef = childHex.getSelfRef();

        // 1. Data merge (Hex.absorb): the parent glyph gets a "Next" slot link to the child's
        //    first glyph and the child's glyph graph is folded into the parent's.
        parentHex.getHex().absorb(childHex.getHex(), parentGlyph.getId());

        // 2. Parent hex now tracks the child's glyph entities too.
        Map<String, Ref<EntityStore>> childGlyphRefs = childHex.getChildGlyphRefs();
        if (childGlyphRefs != null && !childGlyphRefs.isEmpty()) {
            parentHex.addChildGlyphRefs(childGlyphRefs);
        }

        // 3. Reparent and re-point every child glyph at the parent hex. The child's root glyph
        //    nests under the parent glyph; deeper children keep their existing parent (the
        //    child's root glyph). Then remount the child's root glyph onto the parent glyph.
        String childFirstGlyphId = childHex.getHex().getFirstGlyphId();
        if (childGlyphRefs != null) {
            for (Map.Entry<String, Ref<EntityStore>> entry : childGlyphRefs.entrySet()) {
                Ref<EntityStore> glyphRef = entry.getValue();
                if (glyphRef == null || !glyphRef.isValid()) {
                    continue;
                }
                GlyphComponent glyphComp = store.getComponent(glyphRef, GlyphComponent.getComponentType());
                if (glyphComp == null) {
                    continue;
                }
                glyphComp.setHexRef(parentHex.getSelfRef());
                if (entry.getKey().equals(childFirstGlyphId)) {
                    glyphComp.setParentRef(parentGlyph.getSelfRef());
                    store.tryRemoveComponent(glyphRef, MountedComponent.getComponentType());
                    store.putComponent(glyphRef, MountedComponent.getComponentType(),
                        new MountedComponent(parentGlyph.getSelfRef(), new Rotation3f(),
                            MountController.Minecart));
                }
            }
        }

        // 4. Remove the child hex entity, unmounting its remaining passengers first (same as
        //    Hexcode's CleanupUtils.safeRemoveMountParent).
        MountedByComponent mountedBy = store.getComponent(childHexRef, MountedByComponent.getComponentType());
        if (mountedBy != null) {
            for (Ref<EntityStore> passenger : mountedBy.getPassengers()) {
                if (passenger != null && passenger.isValid()) {
                    store.tryRemoveComponent(passenger, MountedComponent.getComponentType());
                }
            }
        }
        store.removeEntity(childHexRef, RemoveReason.REMOVE);

        // 4b. Drop the child hex from the active list so it isn't treated as a separate
        //     castable hex anymore - same as Hexcode's FlycastingDragHandler.endDrag does after
        //     MergeGlyphs.
        if (state != null && state.getActiveHexes() != null) {
            state.getActiveHexes().remove(childHexRef);
        }

        // 5. Restyle the merged tree from the parent hex's root glyph, like MergeGlyphs does,
        //    so nested glyphs scale and position correctly.
        String parentFirstGlyphId = parentHex.getHex().getFirstGlyphId();
        Ref<EntityStore> topGlyphRef = parentHex.getChildGlyphRef(parentFirstGlyphId);
        GlyphComponent topGlyph = topGlyphRef == null ? null
            : store.getComponent(topGlyphRef, GlyphComponent.getComponentType());
        if (topGlyph != null) {
            updateHexTree(store, parentHex, topGlyph);
        }
        feedback(speaker, "Nested '" + childName + "' into '" + parentName + "'.");
    }

    /**
     * Mirrors {@code GlyphStyler.UpdateHexTree}: re-derives the scale of the merged hex tree
     * from its glyph count and recursively restyles every child glyph (scale, rotation, visual
     * offset, entity scale and mount position). Runs on the world thread.
     */
    private static void updateHexTree(Store<EntityStore> store, HexComponent hexComp,
                                      GlyphComponent rootGlyph) {
        if (hexComp == null || hexComp.getHex() == null || rootGlyph == null) {
            return;
        }
        int count = 0;
        for (Glyph glyph : hexComp.getHex().getGlyphs()) {
            if (glyph != null) {
                count++;
            }
        }
        float scale = 1f + count * 0.05f;
        rootGlyph.setScale(scale);
        hexComp.setScale(scale);
        updateGlyphTree(store, hexComp, rootGlyph, new HashSet<>());
    }

    /**
     * Mirrors Hexcode's private {@code GlyphStyler.UpdateGlyphTree}: recursively positions and
     * scales each child glyph around its parent glyph.
     */
    private static void updateGlyphTree(Store<EntityStore> store, HexComponent hexComp,
                                        GlyphComponent glyphComp, Set<String> visited) {
        List<Ref<EntityStore>> children = hexComp.getChildGlyphRefs(glyphComp.getFlowLinks());
        if (children == null || children.isEmpty()) {
            return;
        }
        List<Rotation3f> childRotations = GlyphMath.getChildRotations(children.size(),
            glyphComp.getScale(), glyphComp.getRotation() == null ? 0f : glyphComp.getRotation().z());
        float childScale = glyphComp.getScale()
            * (children.size() == 1 ? 0.45f : 0.2f);
        for (int i = 0; i < children.size(); i++) {
            Ref<EntityStore> childRef = children.get(i);
            if (childRef == null || !childRef.isValid()) {
                continue;
            }
            GlyphComponent child = store.getComponent(childRef, GlyphComponent.getComponentType());
            if (child == null || !visited.add(child.getId())) {
                continue;
            }
            Rotation3f childRotation = childRotations.get(i);
            child.setScale(childScale);
            child.setRotation(childRotation);
            child.setVisualOffset(GlyphMath.toMountOffset(childRotation, glyphComp.getRotation()));
            updateEntityScale(store, childRef, child.getScale());
            updateMountPosition(store, child);
            updateGlyphTree(store, hexComp, child, visited);
        }
    }

    /** Mirrors {@code GlyphStyler.updateScale}: sets (or creates) the entity's scale component. */
    private static void updateEntityScale(Store<EntityStore> store, Ref<EntityStore> ref, float scale) {
        EntityScaleComponent scaleComp = store.getComponent(ref, EntityScaleComponent.getComponentType());
        if (scaleComp != null) {
            scaleComp.setScale(scale);
        } else {
            store.putComponent(ref, EntityScaleComponent.getComponentType(),
                new EntityScaleComponent(scale));
        }
    }

    /**
     * Mirrors {@code GlyphStyler.updateMountPosition}: re-mounts the glyph onto its parent with
     * the glyph's visual offset as the attachment rotation.
     */
    private static void updateMountPosition(Store<EntityStore> store, GlyphComponent glyphComp) {
        Ref<EntityStore> parentRef = glyphComp.getParentRef();
        if (parentRef == null || !parentRef.isValid()) {
            return;
        }
        Vector3f offset = glyphComp.getOffset();
        store.putComponent(glyphComp.getSelfRef(), MountedComponent.getComponentType(),
            new MountedComponent(parentRef,
                new Rotation3f(offset == null ? 0f : offset.x,
                    offset == null ? 0f : offset.y, offset == null ? 0f : offset.z),
                MountController.Minecart));
    }

    /**
     * First slot key on {@code parentAsset} that isn't already full - i.e. not a
     * {@link SlotConfig#isUnique()} slot that already carries a link. Mirrors the check Hexcode's
     * own {@code BaseSlotHandler.exit()} makes when a dragged glyph is dropped onto a slot marker.
     */
    @Nullable
    private static String pickOpenSlotKey(GlyphAsset parentAsset, Glyph parent) {
        for (String key : parentAsset.getSlots().keySet()) {
            SlotConfig config = parentAsset.getSlot(key);
            Slot existing = parent.getSlot(key);
            boolean full = config != null && config.isUnique()
                && existing != null && existing.getLinks().length > 0;
            if (!full) {
                return key;
            }
        }
        return null;
    }

    /**
     * FIFO of "glyph created" callbacks per player, used only for utterances that requested
     * nesting. {@link #onGlyphDrawn} pops the head entry for whichever player's glyph was just
     * created. Relies on Hexcode processing one player's drawn shapes strictly in submission
     * order (enforced by the {@code capture.isFinalizePending()} guard below), and on the player
     * not also hand-drawing a real glyph mid-utterance, which would steal an entry out of turn.
     */
    private static final Map<Ref<EntityStore>, Deque<Consumer<Glyph>>> PENDING_CREATIONS =
        new ConcurrentHashMap<>();

    /** Registered globally in {@code VoxMagicaPlugin.setup()}. */
    public static void onGlyphDrawn(@Nonnull GlyphDrawnEvent event) {
        Deque<Consumer<Glyph>> queue = PENDING_CREATIONS.get(event.getPlayerRef());
        if (queue == null) {
            return;
        }
        Consumer<Glyph> onCreated = queue.pollFirst();
        if (queue.isEmpty()) {
            PENDING_CREATIONS.remove(event.getPlayerRef(), queue);
        }
        if (onCreated != null) {
            onCreated.accept(event.getGlyph());
        }
    }

    /** Runs on the world thread - safe to touch Hexcode's components directly. */
    private static void inject(PlayerRef speaker, Ref<EntityStore> ref, Store<EntityStore> store,
                                GlyphAsset asset, @Nullable Consumer<Glyph> onCreated) {
        DrawCaptureComponent capture = store.getComponent(ref, DrawCaptureComponent.getComponentType());
        if (capture == null) {
            feedback(speaker, "You need to be actively casting or drawing to voice-cast a glyph.");
            return;
        }
        if (capture.isFinalizePending()) {
            feedback(speaker, "Already mid-draw - wait for that glyph before speaking the next one.");
            return;
        }

        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        List<DrawnShapeComponent> shapes = GlyphShapeSynthesizer.synthesizeAtGaze(asset, store, ref, head);
        if (shapes.isEmpty()) {
            feedback(speaker, "Glyph '" + asset.getId() + "' has no shape data; cannot voice-cast it.");
            return;
        }
        if (head == null) {
            feedback(speaker, "Could not read your head rotation; cannot place the glyph. Try again.");
            return;
        }

        if (onCreated != null) {
            PENDING_CREATIONS.computeIfAbsent(ref, k -> new ConcurrentLinkedDeque<>()).addLast(onCreated);
        }

        capture.getPendingShapes().addAll(shapes);
        capture.setFinalizeTimer(0.0f);
        capture.setFinalizeDelaySeconds(FINALIZE_DELAY_SECONDS);
        capture.setFinalizePending(true);

        LOGGER.atInfo().log("[VoxMagica] %s voice-cast '%s' (%s)",
            speaker.getUsername(), asset.getId(), describeContext(store, ref));
        feedback(speaker, "Casting: " + asset.getId());
    }

    /** Purely descriptive, for logging/feedback - Hexcode itself routes off these components. */
    private static String describeContext(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (store.getComponent(ref, CraftingState.getComponentType()) != null) {
            return "pedestal";
        }
        if (store.getComponent(ref, FlycastingState.getComponentType()) != null) {
            return "in-air";
        }
        return "unknown context";
    }

    private static void feedback(PlayerRef speaker, String text) {
        if (speaker.isValid()) {
            speaker.sendMessage(Message.raw("VoxMagica: " + text).color("#88CCFF"));
        }
    }
}