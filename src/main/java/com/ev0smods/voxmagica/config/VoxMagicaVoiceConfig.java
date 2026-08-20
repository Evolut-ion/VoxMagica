package com.ev0smods.voxmagica.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Speech-to-text configuration for voice-casting Hexcode glyphs.
 *
 * <p>Recognition only - there is no reply/TTS half here (unlike VerityHE's voice config, which
 * this is adapted from). A blank {@link #getSttProvider()} means the voice tap is never installed
 * and no audio is captured at all - see {@code GlyphVoiceStreamTap}. The default is
 * {@link #PROVIDER_LOCAL} (voice capture on by default, out of the box, no setup step) - server
 * owners who don't want this at all can still clear {@code SttProvider} to {@code ""}. Note
 * captured audio is only ever sent anywhere for a given player once they've also opted in with
 * {@code /voxmagica voice true} (see {@code PlayerVoiceConsent}) - the tap being installed by
 * default does not itself capture anyone's microphone.
 *
 * <ul>
 *   <li><b>{@code "local"} (recommended, new-install default).</b> In-process whisper.cpp - no
 *       separate server, Docker, or Python needed. Audio never leaves the machine, or even the
 *       JVM. {@link #getSttModel()} is a whisper.cpp short model name (e.g. {@code "base.en"});
 *       see {@code com.ev0smods.voxmagica.voice.local.LocalTranscriber}.</li>
 *   <li><b>{@code "speaches"}.</b> A self-hosted, free
 *       <a href="https://speaches.ai/">Speaches</a> container, normally on
 *       {@code http://localhost:8000}. No API key, no cost, nothing leaves the machine - useful
 *       for GPU acceleration or an existing self-hosted setup, but needs Docker or a native
 *       Python/uv install (unlike {@code "local"}).</li>
 *   <li><b>{@code "openai"}.</b> Player microphone audio is uploaded to OpenAI and billed to
 *       {@link #getSttApiKey()}. Only choose this deliberately.</li>
 * </ul>
 *
 * <p><b>Multilingual.</b> {@link #getSttLanguage()} controls which language speech is transcribed
 * as. Leave it blank for Whisper's automatic multi-language detection (mixed-language
 * conversation); pin it to e.g. {@code "en"}, {@code "es"} or {@code "de"} to transcribe that
 * language specifically. When {@link #getSttModel()} is blank, VoxMagica picks the matching
 * whisper variant for {@link #PROVIDER_SPEACHES} - the English-specific {@code .en} model for
 * {@code "en"} and the multilingual model for any other language.
 */
public class VoxMagicaVoiceConfig {

    /** In-process whisper.cpp: no separate server, no API key, nothing leaves the JVM. */
    public static final String PROVIDER_LOCAL = "local";

    /** Self-hosted Speaches: OpenAI-compatible, no API key required. */
    public static final String PROVIDER_SPEACHES = "speaches";

    /** OpenAI's hosted API: requires an API key, sends audio off-machine, costs money. */
    public static final String PROVIDER_OPENAI = "openai";

    private static final String DEFAULT_LOCAL_BASE_URL = "http://localhost:8000";

    // Keys MUST be capitalized (e.g. "SttProvider", not "sttProvider")
    public static final BuilderCodec<VoxMagicaVoiceConfig> CODEC =
        BuilderCodec.builder(VoxMagicaVoiceConfig.class, VoxMagicaVoiceConfig::new)
            .append(new KeyedCodec<String>("SttProvider", Codec.STRING),
                    (config, value) -> config.sttProvider = value,
                    (config) -> config.sttProvider)
            .add()
            .append(new KeyedCodec<String>("SttApiKey", Codec.STRING),
                    (config, value) -> config.sttApiKey = value,
                    (config) -> config.sttApiKey)
            .add()
            .append(new KeyedCodec<String>("SttBaseUrl", Codec.STRING),
                    (config, value) -> config.sttBaseUrl = value,
                    (config) -> config.sttBaseUrl)
            .add()
            .append(new KeyedCodec<String>("SttModel", Codec.STRING),
                    (config, value) -> config.sttModel = value,
                    (config) -> config.sttModel)
            .add()
            .append(new KeyedCodec<Integer>("MultiCastDelayMs", Codec.INTEGER),
                    (config, value) -> config.multiCastDelayMs = value,
                    (config) -> config.multiCastDelayMs)
            .add()
            .append(new KeyedCodec<String>("SttLanguage", Codec.STRING),
                    (config, value) -> config.sttLanguage = value,
                    (config) -> config.sttLanguage)
            .add()
            .build();

    // Defaults to local (in-process, no setup step) - blank disables capture entirely, the
    // voice tap is never installed. Existing saves that already have a VoxMagicaVoiceConfig.json
    // are unaffected either way; this default only applies when the file is freshly created.
    private String sttProvider = PROVIDER_LOCAL;
    /** Only needed for {@link #PROVIDER_OPENAI}; Speaches requires no key. */
    private String sttApiKey = "";
    private String sttBaseUrl = DEFAULT_LOCAL_BASE_URL;
    /**
     * Speaches model id. Blank lets VoxMagica choose one matching {@link #getSttLanguage()}
     * (English-specific {@code .en} variant for {@code "en"}, multilingual model otherwise);
     * otherwise the configured id is used as-is.
     */
    private String sttModel = "";
    /**
     * Spoken-language code for transcription, e.g. {@code ""} (auto-detect, multilingual),
     * {@code "en"}, {@code "es"} or {@code "de"}. Sent to the STT endpoint and used to pick
     * whisper model variants for {@link #PROVIDER_SPEACHES}.
     */
    private String sttLanguage = "";
    /** Delay in milliseconds between successive voice-cast glyphs in a multi-cast. */
    private int multiCastDelayMs = 250;

    public VoxMagicaVoiceConfig() {
    }

    public String getSttProvider() {
        return sttProvider;
    }

    public void setSttProvider(String sttProvider) {
        this.sttProvider = sttProvider;
    }

    public String getSttApiKey() {
        return sttApiKey;
    }

    public void setSttApiKey(String sttApiKey) {
        this.sttApiKey = sttApiKey;
    }

    public String getSttBaseUrl() {
        return sttBaseUrl;
    }

    public void setSttBaseUrl(String sttBaseUrl) {
        this.sttBaseUrl = sttBaseUrl;
    }

    public String getSttModel() {
        return sttModel;
    }

    public void setSttModel(String sttModel) {
        this.sttModel = sttModel;
    }

    public int getMultiCastDelayMs() {
        return multiCastDelayMs;
    }

    public void setMultiCastDelayMs(int multiCastDelayMs) {
        this.multiCastDelayMs = multiCastDelayMs;
    }

    public String getSttLanguage() {
        return sttLanguage;
    }

    public void setSttLanguage(String sttLanguage) {
        this.sttLanguage = sttLanguage;
    }
}
