package com.ev0smods.voxmagica.glyph;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.riprod.hexcode.api.event.HexCastEvent;
import com.riprod.hexcode.core.common.execution.cast.HexCast;
import com.riprod.hexcode.core.common.execution.component.HexContext;
import com.riprod.hexcode.core.common.execution.component.PlayerHexRoot;
import com.riprod.hexcode.core.common.hexcaster.utils.CasterInventory;
import com.riprod.hexcode.core.common.hexes.component.Hex;
import com.riprod.hexcode.core.common.hexes.saved.SavedHexAsset;
import com.riprod.hexcode.core.common.imbuement.extract.ItemStatExtractor;
import com.riprod.hexcode.utils.SpellMana;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Casts a user-named saved hex via voice, the same way {@code /hex cast <hexId>} works but
 * found by display name instead of asset id.
 *
 * <p>A saved hex is a pre-built spell the player saved with {@code /hexcode save <name>}.
 * When a transcript matches a saved hex's display name (or an alias for it), this class
 * constructs a {@link HexCastEvent} and dispatches it, bypassing per-glyph injection.
 */
public final class SavedHexCaster {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private SavedHexCaster() {
    }

    /**
     * Attempts to find a saved hex whose display name appears in the transcript.
     * Both the transcript and each saved hex's display name are cleaned the same way
     * {@link GlyphNameMatcher} cleans glyph names, so "Fire Ball" and "fireball" match.
     *
     * @param rawTranscript the raw STT transcript
     * @return the matching {@link SavedHexAsset} or {@code null}
     */
    @Nullable
    public static SavedHexAsset matchSavedHex(@Nonnull String rawTranscript) {
        CastableSpell spell = matchSpell(rawTranscript, null, null);
        return spell == null ? null : spell.savedHex;
    }

    /**
     * Matches a transcript against the <b>cast list</b>: every named spell this server can cast.
     *
     * <p>The list always reflects the current state of the world - it is re-resolved from live data
     * on every call, so a hex that a player just named at the Seeker obelisk (which Hexcode writes
     * into their hexbook) is recognised immediately with no restart or refresh step:
     * <ul>
     *   <li>Every server-side {@link SavedHexAsset} (e.g. the "Fireball" preset).</li>
     *   <li>Every named hex in the speaker's own castable inventory, via
     *       {@link CasterInventory#getHexesForCasting} - this is exactly the "full spells we can
     *       cast" set Hexcode itself uses when not drawing.</li>
     * </ul>
     *
     * @param rawTranscript the raw STT transcript
     * @param store         the player's entity store (only needed to read their own hexes; may be
     *                      {@code null} to match server-side saved hexes only)
     * @param ref           the player's entity reference (may be {@code null} to match
     *                      server-side saved hexes only)
     * @return the matching {@link CastableSpell}, or {@code null}
     */
    @Nullable
    public static CastableSpell matchSpell(@Nonnull String rawTranscript,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable Ref<EntityStore> ref) {
        String cleanedTranscript = clean(rawTranscript);
        if (cleanedTranscript.isEmpty()) {
            return null;
        }

        List<CastableSpell> candidates = new ArrayList<>();

        // Server-side saved hexes first, so a preset name keeps priority (existing behaviour).
        for (SavedHexAsset saved : SavedHexAsset.getAssetMap().getAssetMap().values()) {
            String name = saved.getDisplayName();
            if (name == null || name.isEmpty()) continue;
            String cleanName = clean(name);
            if (cleanName.length() < 2) continue;  // avoid over-matching single letters/fillers
            candidates.add(new CastableSpell(saved, saved.getHex(), name));
        }

        // The speaker's own named hexes (from the hexbook / items they cast with). These are the
        // spells added by naming a hex at the Seeker obelisk and are always read fresh.
        if (store != null && ref != null) {
            List<Hex> playerHexes;
            try {
                playerHexes = CasterInventory.getHexesForCasting(store, ref);
            } catch (RuntimeException e) {
                LOGGER.atWarning().withCause(e).log(
                    "[VoxMagica] Could not read %s's castable hexes", ref);
                playerHexes = List.of();
            }
            if (playerHexes != null) {
                for (Hex hex : playerHexes) {
                    if (hex == null) continue;
                    String name = hex.getDisplayName();
                    if (name == null || name.isEmpty()) continue;
                    String cleanName = clean(name);
                    if (cleanName.length() < 2) continue;
                    candidates.add(new CastableSpell(null, hex, name));
                }
            }
        }

        for (CastableSpell candidate : candidates) {
            if (cleanedTranscript.contains(candidate.cleanName)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * One entry in the cast list: either a server-side {@link SavedHexAsset} or a player-owned
     * {@link Hex} that carries a spoken display name.
     */
    public static final class CastableSpell {

        @Nullable
        private final SavedHexAsset savedHex;
        @Nullable
        private final Hex hex;
        private final String id;
        private final String displayName;
        private final String cleanName;

        CastableSpell(@Nullable SavedHexAsset savedHex, @Nullable Hex hex, String displayName) {
            this.savedHex = savedHex;
            this.hex = hex;
            this.id = savedHex != null ? savedHex.getId() : (hex != null ? hex.getHexId() : displayName);
            this.displayName = displayName;
            this.cleanName = clean(displayName);
        }

        @Nullable
        public Hex getHex() {
            return hex != null ? hex : (savedHex != null ? savedHex.getHex() : null);
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static String clean(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        String noMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lettersOnly = NON_LETTERS.matcher(noMarks).replaceAll(" ");
        return WHITESPACE.matcher(lettersOnly.trim()).replaceAll(" ");
    }

    /**
     * Casts a {@link CastableSpell} (either a server-side saved hex or a hex the player named
     * at the Seeker obelisk) for the given player, mirroring Hexcode's staff/book cast pipeline:
     * volatility is initialised from the speaker's held item, then the {@link HexCastEvent.Pre}
     * event is fired so Hexcode's book/charges/decay/spell-power systems run, before the main
     * {@link HexCastEvent} (unless the pre-cast was cancelled).
     */
    public static void castSpell(@Nonnull PlayerRef speaker,
                                 @Nonnull Ref<EntityStore> entityRef,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CastableSpell spell) {
        Hex hex = spell.getHex();
        if (hex == null) {
            feedback(speaker, "That spell has no hex data to cast.");
            return;
        }
        Hex cloned = hex.clone();
        if (cloned == null) {
            feedback(speaker, "Could not clone hex data.");
            return;
        }

        try {
            PlayerHexRoot playerRoot = new PlayerHexRoot(entityRef, store);
            HexCast cast = new HexCast();

            // Same volatility/power setup as ItemHeldCastDispatcher: the pool comes from the
            // casting item and the power modifier from its stats. An empty hand is a neutral
            // pool (volatility 0, power 1).
            ItemStack item = InventoryComponent.getItemInHand(store, entityRef);
            float volatility = ItemStatExtractor.extractVolatility(item);
            float power = 1f + ItemStatExtractor.extractPower(item);
            float manaCost = SpellMana.computeTotalMana(cloned);
            cast.volatility().init(volatility, 1f, power);

            HexContext context = new HexContext(cloned, manaCost, playerRoot, null, cast);

            // Fire Pre so Hexcode's book/charges/decay/spell-power systems run. The cast slot
            // key is left null, matching a book cast, so decay applies. If Pre is cancelled
            // (e.g. insufficient magic charges), the actual cast is skipped.
            HexCastEvent.Pre pre = new HexCastEvent.Pre(context);
            store.invoke(pre);
            if (pre.isCancelled()) {
                feedback(speaker, "That spell could not be cast right now.");
                return;
            }

            HexCastEvent castEvent = new HexCastEvent(context);
            store.invoke(castEvent);

            feedback(speaker, spell.savedHex != null
                ? "Casting saved hex: " + spell.getDisplayName()
                : "Casting: " + spell.getDisplayName());
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log(
                "[VoxMagica] Failed to cast spell '%s' for %s",
                spell.getId(), speaker.getUsername());
            feedback(speaker, "Failed to cast that spell.");
        }
    }

    /**
     * Casts the given server-side saved hex for the player.
     */
    public static void castSavedHex(@Nonnull PlayerRef speaker,
                                     @Nonnull Ref<EntityStore> entityRef,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull SavedHexAsset savedHex) {
        castSpell(speaker, entityRef, store,
            new CastableSpell(savedHex, savedHex.getHex(), savedHex.getDisplayName()));
    }

    private static void feedback(PlayerRef speaker, String text) {
        if (speaker.isValid()) {
            speaker.sendMessage(Message.raw("VoxMagica: " + text).color("#88CCFF"));
        }
    }
}