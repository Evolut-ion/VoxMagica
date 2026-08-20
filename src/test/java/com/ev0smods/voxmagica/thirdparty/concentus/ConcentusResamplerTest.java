package com.ev0smods.voxmagica.thirdparty.concentus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity-checks {@link ConcentusResampler#resample48to16} against a synthetic tone, since there's
 * no real voice-chat audio to test against in this dev environment. Not a substitute for the
 * real-server verification in the local-transcription plan's Phase 6 - just catches gross
 * correctness bugs (wrong rate direction, garbage output, wrong output length) cheaply and
 * repeatably.
 */
class ConcentusResamplerTest {

    private static final int INPUT_HZ = 48_000;
    private static final int OUTPUT_HZ = 16_000;

    @Test
    void outputLengthIsExactlyOneThirdOfInput() {
        short[] input = new short[INPUT_HZ]; // 1 second
        short[] output = ConcentusResampler.resample48to16(input);
        assertEquals(input.length / 3, output.length);
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertEquals(0, ConcentusResampler.resample48to16(new short[0]).length);
    }

    @Test
    void a1kHzToneSurvivesResamplingRecognizably() {
        double toneHz = 1000.0;
        double durationSeconds = 0.5;
        short[] input = sineWave(toneHz, durationSeconds, INPUT_HZ);

        short[] output = ConcentusResampler.resample48to16(input);

        double measuredHz = estimateFrequencyByZeroCrossings(output, OUTPUT_HZ);
        // Generous tolerance (+-10%) - this is a correctness sanity check (right rate direction,
        // not garbage/silence/aliased-to-noise), not a precision audio-quality test.
        assertTrue(Math.abs(measuredHz - toneHz) < toneHz * 0.10,
            "Expected ~" + toneHz + " Hz after resampling, measured " + measuredHz + " Hz");
    }

    @Test
    void a4kHzToneAlsoSurvivesResampling() {
        // A second frequency away from 1kHz, still comfortably below the 8kHz Nyquist limit of
        // the 16kHz output, to catch a bug that only manifests at one specific frequency.
        double toneHz = 4000.0;
        double durationSeconds = 0.5;
        short[] input = sineWave(toneHz, durationSeconds, INPUT_HZ);

        short[] output = ConcentusResampler.resample48to16(input);

        double measuredHz = estimateFrequencyByZeroCrossings(output, OUTPUT_HZ);
        assertTrue(Math.abs(measuredHz - toneHz) < toneHz * 0.10,
            "Expected ~" + toneHz + " Hz after resampling, measured " + measuredHz + " Hz");
    }

    private static short[] sineWave(double frequencyHz, double durationSeconds, int sampleRateHz) {
        int n = (int) Math.round(durationSeconds * sampleRateHz);
        short[] samples = new short[n];
        double amplitude = 0.5 * Short.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRateHz;
            samples[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * frequencyHz * t));
        }
        return samples;
    }

    /** Counts sign changes and derives frequency from the average half-period. */
    private static double estimateFrequencyByZeroCrossings(short[] samples, int sampleRateHz) {
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) {
                crossings++;
            }
        }
        double durationSeconds = samples.length / (double) sampleRateHz;
        return (crossings / 2.0) / durationSeconds;
    }
}
