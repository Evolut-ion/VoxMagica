package com.ev0smods.voxmagica.glyph;

import com.ev0smods.voxmagica.VoxMagicaPlugin;
import com.ev0smods.voxmagica.config.VoxMagicaVoiceConfig;
import com.riprod.hexcode.core.common.glyphs.registry.GlyphAsset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches a raw voice transcript to Hexcode {@link GlyphAsset}s by name.
 *
 * <p>Supports spoken-word aliases for glyphs whose asset IDs aren't naturally speakable:
 * <ul>
 *   <li>{@code "one"} through {@code "sixteen"} → {@code Number_1} … {@code Number_16}</li>
 *   <li>Multi-word names like {@code "on primary"} → {@code OnPrimary} (folded to one word)</li>
 * </ul>
 *
 * <p>See {@link #matchAll(String)} — returns <b>all</b> uniquely matched glyphs in transcript
 * order, enabling sequential multi-cast of an entire phrase such as
 * {@code "on primary add one two" → [OnPrimary, Add, Number_1, Number_2]}.
 *
 * <p>See {@link #matchSequence(String)} for the richer variant that also recognizes the spoken
 * keyword "next" (see {@link #DEFAULT_NEXT_KEYWORDS}): a glyph preceded by "next" is flagged so
 * the caller can nest it as a child of the glyph spoken immediately before, e.g.
 * {@code "add next one next two" → Add(one(two))}.
 */
public final class GlyphNameMatcher {

    /** Words that carry no glyph-identifying information of their own. */
    private static final Set<String> FILLER_WORDS = Set.of(
        "please", "could", "would", "can", "you", "the", "a", "an",
        "cast", "draw", "glyph", "now", "that", "this", "it", "me", "hex", "spell",
        "and", "then", "with", "my", "i", "to", "for", "of", "in", "on", "at", "by"
    );

    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Spoken-word → asset id aliases. These convert natural number words and other
     * common speech patterns into the actual Hexcode registry IDs.
     */
    private static final Map<String, String> SPOKEN_ALIASES = aliasesOf();

    /** Per-language spoken aliases, keyed by the ISO-639-1 code used in {@code SttLanguage}. */
    private static final Map<String, Map<String, String>> TRANSLATED_ALIASES =
        Map.of("es", spanishAliases(), "de", germanAliases(), "fr", frenchAliases());

    /** English spoken form(s) of the nesting keyword; always in play regardless of language. */
    private static final Set<String> DEFAULT_NEXT_KEYWORDS = Set.of("next");

    /** Per-language spoken form(s) of the nesting keyword, added on top of {@link #DEFAULT_NEXT_KEYWORDS}. */
    private static final Map<String, Set<String>> TRANSLATED_NEXT_KEYWORDS = Map.of(
        "es", Set.of("siguiente"),
        "de", Set.of("weiter", "nächstes"),
        "fr", Set.of("suivant"));

    private GlyphNameMatcher() {
    }

    /**
     * @return the best-matching single {@link GlyphAsset} for {@code transcript}, or {@code null}
     *         if nothing matched confidently. Maintained for backward compatibility; new code should
     *         prefer {@link #matchAll(String)}.
     */
    @Nullable
    public static GlyphAsset match(@Nonnull String transcript) {
        List<GlyphAsset> all = matchAll(transcript);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Matches <b>all</b> uniquely identified glyphs in the order they appear in the transcript.
     * Uses greedy longest-substring matching so {@code "on primary"} matches as one word
     * ({@code OnPrimary}) rather than as {@code On} + {@code Primary} separately.
     *
     * @return ordered list of distinct matches, or an empty list if nothing matched.
     */
    @Nonnull
    public static List<GlyphAsset> matchAll(@Nonnull String transcript) {
        List<SequencedGlyph> sequence = matchSequence(transcript);
        List<GlyphAsset> assets = new ArrayList<>(sequence.size());
        for (SequencedGlyph glyph : sequence) {
            assets.add(glyph.getAsset());
        }
        return assets;
    }

    /**
     * Same matching as {@link #matchAll(String)}, but each result also carries whether the
     * spoken "next" keyword (see {@link #DEFAULT_NEXT_KEYWORDS}) immediately preceded it in the
     * transcript - meaning {@link VoiceGlyphInjector} should nest it as a child of the glyph
     * matched right before it, rather than casting it alongside as a sibling.
     *
     * @return ordered list of distinct matches, or an empty list if nothing matched.
     */
    @Nonnull
    public static List<SequencedGlyph> matchSequence(@Nonnull String transcript) {
        Map<String, GlyphAsset> idIndex = buildIdIndex();
        if (idIndex.isEmpty()) {
            return List.of();
        }

        String cleaned = clean(transcript);
        if (cleaned.isEmpty()) {
            return List.of();
        }

        String[] words = WHITESPACE.split(cleaned);
        if (words.length == 0) {
            return List.of();
        }

        // Build a reverse index from all known spoken forms → canonical asset id. English
        // aliases are always in play; the configured language adds its own translations.
        Map<String, String> aliases = new HashMap<>(SPOKEN_ALIASES);
        Map<String, String> translated = TRANSLATED_ALIASES.get(configuredLanguage());
        if (translated != null) {
            aliases.putAll(translated);
        }

        Map<String, String> spokenToId = new HashMap<>();
        for (String canonical : idIndex.keySet()) {
            spokenToId.put(canonical, canonical);
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            String spoken = clean(alias.getKey());
            if (!spoken.isEmpty()) {
                spokenToId.put(spoken, alias.getValue().toLowerCase(Locale.ROOT));
            }
        }

        Set<String> nextKeywords = nextKeywordsOf();

        // Greedy longest-phrase scan: start from each word boundary and try
        // progressively longer phrases, taking the longest match that covers the most words.
        List<Match> results = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        int pos = 0;
        boolean nestPending = false;
        while (pos < words.length) {
            if (nextKeywords.contains(words[pos])) {
                nestPending = true;
                pos++;
                continue;
            }

            String bestId = null;
            int bestLen = 0;

            // Try phrases starting at pos, growing up to 5 words for multi-word aliases.
            // Filler words are included in the phrase attempt so "on primary" matches as one
            // phrase even though "on" is a filler word on its own.
            int maxEnd = Math.min(pos + 5, words.length);
            for (int end = pos; end < maxEnd; end++) {
                StringBuilder phrase = new StringBuilder();
                for (int i = pos; i <= end; i++) {
                    if (phrase.length() > 0) phrase.append(" ");
                    phrase.append(words[i]);
                }
                String resolved = spokenToId.get(phrase.toString());
                if (resolved != null && (end - pos + 1) > bestLen) {
                    bestId = resolved;
                    bestLen = end - pos + 1;
                }
            }

            if (bestId != null) {
                GlyphAsset asset = idIndex.get(bestId);
                if (asset != null && !matched.contains(bestId)) {
                    results.add(new Match(asset, pos, nestPending && !results.isEmpty()));
                    matched.add(bestId);
                    nestPending = false;
                }
                pos += bestLen;
            } else {
                // No phrase matched here; skip a single filler word, otherwise advance one word.
                pos++;
            }
        }

        // Sort by position in transcript to preserve utterance order.
        results.sort((a, b) -> Integer.compare(a.position, b.position));
        List<SequencedGlyph> sequence = new ArrayList<>(results.size());
        for (Match m : results) {
            sequence.add(new SequencedGlyph(m.asset, m.nestUnderPrevious));
        }
        return sequence;
    }

    @Nonnull
    private static Set<String> nextKeywordsOf() {
        Set<String> keywords = new HashSet<>();
        for (String word : DEFAULT_NEXT_KEYWORDS) {
            keywords.add(clean(word));
        }
        Set<String> translated = TRANSLATED_NEXT_KEYWORDS.get(configuredLanguage());
        if (translated != null) {
            for (String word : translated) {
                keywords.add(clean(word));
            }
        }
        return keywords;
    }

    private static Map<String, GlyphAsset> buildIdIndex() {
        Map<String, GlyphAsset> index = new HashMap<>();
        for (GlyphAsset asset : GlyphAsset.getAssetMap().getAssetMap().values()) {
            if (!asset.isEnabled()) continue;
            index.put(asset.getId().toLowerCase(Locale.ROOT), asset);
        }
        return index;
    }

    private static String clean(String transcript) {
        String lower = transcript.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        String noMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lettersOnly = NON_LETTERS.matcher(noMarks).replaceAll(" ");
        return WHITESPACE.matcher(lettersOnly.trim()).replaceAll(" ");
    }

    /** The configured {@code SttLanguage} code, or blank when unset / plugin not yet loaded. */
    private static String configuredLanguage() {
        VoxMagicaPlugin plugin = VoxMagicaPlugin.getInstance();
        if (plugin == null) {
            return "";
        }
        VoxMagicaVoiceConfig config = plugin.getVoiceConfig().get();
        String language = config == null ? null : config.getSttLanguage();
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> aliasesOf() {
        Map<String, String> aliases = new HashMap<>();

        // Number glyphs — both spoken words and digit forms ("1", "2", …)
        aliases.put("one", "Number_1");
        aliases.put("two", "Number_2");
        aliases.put("three", "Number_3");
        aliases.put("four", "Number_4");
        aliases.put("five", "Number_5");
        aliases.put("six", "Number_6");
        aliases.put("seven", "Number_7");
        aliases.put("eight", "Number_8");
        aliases.put("nine", "Number_9");
        aliases.put("ten", "Number_10");
        aliases.put("eleven", "Number_11");
        aliases.put("twelve", "Number_12");
        aliases.put("thirteen", "Number_13");
        aliases.put("fourteen", "Number_14");
        aliases.put("fifteen", "Number_15");
        aliases.put("sixteen", "Number_16");
        aliases.put("1", "Number_1");
        aliases.put("2", "Number_2");
        aliases.put("3", "Number_3");
        aliases.put("4", "Number_4");
        aliases.put("5", "Number_5");
        aliases.put("6", "Number_6");
        aliases.put("7", "Number_7");
        aliases.put("8", "Number_8");
        aliases.put("9", "Number_9");
        aliases.put("10", "Number_10");
        aliases.put("11", "Number_11");
        aliases.put("12", "Number_12");
        aliases.put("13", "Number_13");
        aliases.put("14", "Number_14");
        aliases.put("15", "Number_15");
        aliases.put("16", "Number_16");

        // Trigger / event glyphs (multi-word aliases so "on primary" → OnPrimary)
        aliases.put("on primary", "OnPrimary");
        aliases.put("on secondary", "OnSecondary");
        aliases.put("on cast", "OnCast");
        aliases.put("on death", "OnDeath");
        aliases.put("on use", "OnUse");
        aliases.put("on attack", "OnAttack");
        aliases.put("on attacked", "OnAttacked");

        // Common spell-casting verbs
        aliases.put("heal", "HealthSurge");
        aliases.put("stop", "Halt");
        aliases.put("freeze", "Freeze");
        aliases.put("ignite", "Ignite");
        aliases.put("scorch", "Scorch");
        aliases.put("snap", "Snap");
        aliases.put("bolt", "Bolt");
        aliases.put("electrocute", "Electrocute");
        aliases.put("drown", "Drown");
        aliases.put("growth", "Growth");
        aliases.put("fortify", "Fortify");
        aliases.put("drain", "Drain");
        aliases.put("shatter", "Shatter");
        aliases.put("force", "Force");

        return aliases;
    }

    private static Map<String, String> spanishAliases() {
        Map<String, String> a = new HashMap<>();
        putNumbers(a, "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
            "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis");
        a.put("en primario", "OnPrimary");
        a.put("en secundario", "OnSecondary");
        a.put("al lanzar", "OnCast");
        a.put("al morir", "OnDeath");
        a.put("al usar", "OnUse");
        a.put("al atacar", "OnAttack");
        a.put("al ser atacado", "OnAttacked");
        a.put("curar", "HealthSurge");
        a.put("detener", "Halt");
        a.put("congelar", "Freeze");
        a.put("encender", "Ignite");
        a.put("chamuscar", "Scorch");
        a.put("chasquear", "Snap");
        a.put("rayo", "Bolt");
        a.put("electrocutar", "Electrocute");
        a.put("ahogar", "Drown");
        a.put("crecimiento", "Growth");
        a.put("fortificar", "Fortify");
        a.put("drenar", "Drain");
        a.put("destrozar", "Shatter");
        a.put("fuerza", "Force");
        return a;
    }

    private static Map<String, String> germanAliases() {
        Map<String, String> a = new HashMap<>();
        putNumbers(a, "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
            "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn");
        a.put("auf primär", "OnPrimary");
        a.put("auf sekundär", "OnSecondary");
        a.put("beim zaubern", "OnCast");
        a.put("beim tod", "OnDeath");
        a.put("bei benutzung", "OnUse");
        a.put("bei angriff", "OnAttack");
        a.put("angegriffen", "OnAttacked");
        a.put("heilen", "HealthSurge");
        a.put("stoppen", "Halt");
        a.put("einfrieren", "Freeze");
        a.put("entzünden", "Ignite");
        a.put("versengen", "Scorch");
        a.put("schnippen", "Snap");
        a.put("blitz", "Bolt");
        a.put("elektrisieren", "Electrocute");
        a.put("ertränken", "Drown");
        a.put("wachstum", "Growth");
        a.put("verstärken", "Fortify");
        a.put("entziehen", "Drain");
        a.put("zertrümmern", "Shatter");
        a.put("kraft", "Force");
        return a;
    }

    private static Map<String, String> frenchAliases() {
        Map<String, String> a = new HashMap<>();
        putNumbers(a, "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize");
        a.put("sur primaire", "OnPrimary");
        a.put("sur secondaire", "OnSecondary");
        a.put("au lancement", "OnCast");
        a.put("à la mort", "OnDeath");
        a.put("à l utilisation", "OnUse");
        a.put("en attaque", "OnAttack");
        a.put("attaqué", "OnAttacked");
        a.put("soigner", "HealthSurge");
        a.put("stopper", "Halt");
        a.put("geler", "Freeze");
        a.put("enflammer", "Ignite");
        a.put("brûler", "Scorch");
        a.put("claquer", "Snap");
        a.put("éclair", "Bolt");
        a.put("électrocuter", "Electrocute");
        a.put("noyer", "Drown");
        a.put("croissance", "Growth");
        a.put("fortifier", "Fortify");
        a.put("drainer", "Drain");
        a.put("briser", "Shatter");
        a.put("force", "Force");
        return a;
    }

    private static void putNumbers(Map<String, String> a, String... words) {
        for (int i = 0; i < words.length && i < 16; i++) {
            a.put(words[i], "Number_" + (i + 1));
        }
    }

    /** Bookkeeping: an asset, its word-position in the transcript, and its nesting flag. */
    private static final class Match {
        final GlyphAsset asset;
        final int position;
        final boolean nestUnderPrevious;

        Match(GlyphAsset asset, int position, boolean nestUnderPrevious) {
            this.asset = asset;
            this.position = position;
            this.nestUnderPrevious = nestUnderPrevious;
        }
    }

    /**
     * A glyph matched from a transcript, plus whether the spoken "next" keyword immediately
     * preceded it - see {@link #matchSequence(String)}.
     */
    public static final class SequencedGlyph {
        private final GlyphAsset asset;
        private final boolean nestUnderPrevious;

        SequencedGlyph(GlyphAsset asset, boolean nestUnderPrevious) {
            this.asset = asset;
            this.nestUnderPrevious = nestUnderPrevious;
        }

        @Nonnull
        public GlyphAsset getAsset() {
            return asset;
        }

        /**
         * True when "next" was spoken right before this glyph, meaning it should be nested as a
         * child of the glyph matched immediately before it rather than cast alongside it.
         */
        public boolean isNestUnderPrevious() {
            return nestUnderPrevious;
        }
    }
}