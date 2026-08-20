package com.ev0smods.voxmagica.voice.local;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whisper.cpp GGML models VoxMagica knows how to fetch and verify for local (in-process)
 * transcription. Names match whisper.cpp's own short model names exactly (not
 * {@code Systran/faster-whisper-*} Hugging Face IDs, which are Speaches/CTranslate2-specific and
 * don't apply here) - a configured {@code SttModel} value for {@code SttProvider=local} <i>is</i>
 * one of these names directly, with no separate ID-mapping table needed.
 *
 * <p>Files and sha256 hashes are from {@code ggerganov/whisper.cpp} on Hugging Face (MIT
 * licensed - see {@code com.ev0smods.voxmagica.voice.local}'s package docs), fetched from the
 * Hugging Face API at implementation time, not fabricated. {@code setup/launcher.py}'s
 * {@code LOCAL_MODEL_SHA256} dict must be kept in sync with these - there is no compiler to catch
 * drift between the two.
 */
public final class LocalWhisperModelCatalog {

    private static final String DOWNLOAD_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    public static final class Entry {
        private final String name;
        private final long sizeBytes;
        private final String sha256;

        private Entry(String name, long sizeBytes, String sha256) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        /** The GGML file's expected size in bytes - informational (progress/logging), not load-bearing. */
        public long getSizeBytes() {
            return sizeBytes;
        }

        @Nonnull
        public String getSha256() {
            return sha256;
        }

        @Nonnull
        public String getFileName() {
            return "ggml-" + name + ".bin";
        }

        @Nonnull
        public String getDownloadUrl() {
            return DOWNLOAD_BASE + getFileName();
        }
    }

    private static final Map<String, Entry> MODELS = buildCatalog();

    private LocalWhisperModelCatalog() {
    }

    @Nullable
    public static Entry get(@Nonnull String name) {
        return MODELS.get(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isKnown(@Nonnull String name) {
        return get(name) != null;
    }

    /** Mirrors {@code GlyphSttClient}'s existing per-language default-model convention. */
    @Nonnull
    public static String defaultModelFor(@Nullable String language) {
        return "en".equalsIgnoreCase(language) ? "base.en" : "base";
    }

    private static Map<String, Entry> buildCatalog() {
        Map<String, Entry> models = new LinkedHashMap<>();
        add(models, "tiny.en", 77_704_715L, "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f");
        add(models, "tiny", 77_691_713L, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21");
        add(models, "base.en", 147_964_211L, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002");
        add(models, "base", 147_951_465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe");
        add(models, "small.en", 487_614_201L, "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d");
        add(models, "small", 487_601_967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b");
        add(models, "medium.en", 1_533_774_781L, "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356");
        add(models, "medium", 1_533_763_059L, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208");
        add(models, "large-v3", 3_095_033_483L, "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2");
        return java.util.Collections.unmodifiableMap(models);
    }

    private static void add(Map<String, Entry> models, String name, long sizeBytes, String sha256) {
        models.put(name, new Entry(name, sizeBytes, sha256));
    }
}
