package com.ev0smods.voxmagica.thirdparty.concentus;

/**
 * Thin bridge over Concentus's package-private SILK resampler ({@link Resampler}) - everything
 * else in this package is vendored, unmodified upstream source (see {@code package-info.java});
 * this is the one file we author ourselves, added specifically for VoxMagica's local-transcription
 * pipeline (see {@code com.ev0smods.voxmagica.voice.local.LocalTranscriber}), which needs to turn
 * 48kHz mono voice-chat PCM (after Opus decode) into the 16kHz mono float32 PCM whisper.cpp expects.
 */
public final class ConcentusResampler {

    private static final int INPUT_HZ = 48_000;
    private static final int OUTPUT_HZ = 16_000;

    private ConcentusResampler() {
    }

    /**
     * 48kHz -&gt; 16kHz is an exact 3:1 ratio.
     *
     * <p>{@code forEnc=1} (not 0) is required here even though nothing is being SILK-encoded:
     * {@link Resampler#silk_resampler_init} validates rates differently per direction -
     * {@code forEnc=0} (decoder-direction) only accepts an <b>input</b> rate up to 16kHz (it's
     * built for upsampling SILK's internal rate back out to a playback device rate), while
     * {@code forEnc=1} (encoder-direction) accepts the 48kHz input this method needs and requires
     * the output rate to be one of SILK's internal rates (8/12/16kHz) - exactly this use case.
     * Confirmed by reading {@code Resampler.java}'s validation branches directly, not assumed.
     */
    public static short[] resample48to16(short[] pcm48kMono) {
        if (pcm48kMono.length == 0) {
            return new short[0];
        }
        SilkResamplerState state = new SilkResamplerState();
        int initResult = Resampler.silk_resampler_init(state, INPUT_HZ, OUTPUT_HZ, 1);
        if (initResult != SilkError.SILK_NO_ERROR) {
            throw new IllegalStateException("silk_resampler_init failed: " + initResult);
        }

        short[] out = new short[pcm48kMono.length / 3];
        // silk_resampler returns a status code (SilkError.SILK_NO_ERROR on success), NOT a
        // sample count - confirmed by reading the method body. Output length is the caller's
        // responsibility, computed above from the fixed 3:1 ratio.
        int resampleResult = Resampler.silk_resampler(state, out, 0, pcm48kMono, 0, pcm48kMono.length);
        if (resampleResult != SilkError.SILK_NO_ERROR) {
            throw new IllegalStateException("silk_resampler failed: " + resampleResult);
        }
        return out;
    }
}
