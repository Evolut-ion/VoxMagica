/**
 * Vendored from <a href="https://github.com/lostromb/concentus">lostromb/concentus</a>
 * (pinned tag {@code v1.0-java}, {@code Java/Concentus/src/main/java/org/concentus}), a portable
 * pure-Java port of the libopus reference Opus codec. Used here purely as an Opus <b>decoder</b>
 * (voice-cast utterances arrive as raw Opus packets - see
 * {@code com.ev0smods.voxmagica.voice.local.LocalTranscriber}); nothing in VoxMagica encodes Opus.
 *
 * <p>Not published to Maven Central (only ever existed as source, {@code groupId org.concentus},
 * {@code version 1.0-SNAPSHOT}), so the source is vendored directly rather than pulled as a
 * dependency. The only change made to the upstream source is the package declaration itself
 * ({@code org.concentus} -&gt; this package); every other line is unmodified. {@code
 * SpeexResampler.java} was deliberately NOT vendored - its entire class body is commented out in
 * upstream (dead/unfinished code) and it is not used anywhere in VoxMagica; {@link
 * com.ev0smods.voxmagica.thirdparty.concentus.ConcentusResampler} instead bridges to the live,
 * package-private SILK resampler in {@code Resampler.java} for VoxMagica's 48kHz-&gt;16kHz needs.
 *
 * <p>Licensed under a permissive 3-clause-BSD-style "Opus" license - see the {@code LICENSE} file
 * alongside this package, copied verbatim from upstream. Fully compatible with VoxMagica's own
 * GPLv3 license.
 */
package com.ev0smods.voxmagica.thirdparty.concentus;
