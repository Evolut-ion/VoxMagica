# VoxMagica

Voice-cast [Hexcode](https://github.com/Riprod) glyphs: speak a glyph's name while drawing or
casting and it spawns exactly as if you'd drawn it by hand. Built for [Hytale](https://hytale.com)
server `0.5.x`.

## Requirements

- A Hytale server running **Hexcode** (`Riprod:Hexcode`) - VoxMagica is a voice front-end for
  Hexcode's glyph-casting system and does nothing without it.
- Nothing else. Speech-to-text runs fully in-process (whisper.cpp) - no external server, Docker,
  or Python install needed for the default setup.

## Quick setup

1. Make sure Hexcode is already installed on your server.
2. Drop `VoxMagica-0.1.0.jar` into your server's `Mods` folder, alongside Hexcode.
3. Start the server (or load your singleplayer world).
4. In-game, run `/voxmagica voice true` so VoxMagica listens while *you* speak. This is a
   per-player opt-in - nothing is captured until you run it.
5. While drawing a glyph or actively casting, say a glyph's name out loud (e.g. "Ignite",
   "On Primary", "Add").

That's it. The first time anyone speaks, VoxMagica downloads a small (~150 MB) speech model
in the background and caches it for every save on that server - you'll get a friendly "still
downloading" message if you speak before it's ready, and it only happens once.

### Optional: GUI installer

`setup/VoxMagicaVoiceSetup` (built from `setup/launcher.py`) is a small desktop app that copies
the mod jar into your `Mods` folder for you and lets you pick a language and whisper model ahead
of time (so the download happens before your first voice-cast instead of during it). It's
entirely optional - steps 1-5 above work without it.

## How voice-casting works

- **Single glyph:** say its name while drawing (at a pedestal) or actively casting (in-air).
  Works for any enabled glyph in Hexcode's registry, plus number words ("one" through "sixteen")
  and trigger names ("on primary", "on cast", etc.).
- **Multi-cast:** say several glyph names in one sentence - e.g. *"on primary add one two"* -
  and they're cast one after another automatically.
- **Nesting:** say **"next"** right after a glyph's name to nest the glyph spoken after it as a
  child of that glyph - the same result as drawing (or dragging) one glyph onto another's slot in
  Hexcode's own crafting UI. *"Add next one next two"* nests `one` into `add`, then `two` into
  `one`.
- **Saved spells:** if you've named a hex at a Seeker obelisk or saved one server-side, just say
  its name and the whole spell casts at once, in any mode - this always takes priority over
  individual glyph names.

## Configuration

Server-wide settings live in `VoxMagicaVoiceConfig.json`, written per-save at
`Saves/<save>/mods/Ev0sMods_VoxMagica/VoxMagicaVoiceConfig.json`. You normally don't need to touch
this directly - use the in-game commands below (admin-only, since they affect every player):

| Command | Effect |
|---|---|
| `/voxmagica voice <true\|false>` | Per-player: opt in/out of having your own voice captured. |
| `/voxmagica sttprovider <local\|speaches\|openai\|off>` | Which speech-to-text backend to use. |
| `/voxmagica sttmodel <name\|auto>` | Which model to use for the current provider. |
| `/voxmagica sttlanguage <code\|auto>` | Pin transcription to a language, or auto-detect. |

Changing `sttmodel`/`sttlanguage` while using `local` requires a restart to take effect (the
model loads once at startup); changes to `speaches`/`openai` apply on the next voice-cast.
Changing `sttprovider` always requires a restart.

**Providers:**

| Provider | What it is |
|---|---|
| `local` (default) | In-process [whisper.cpp](https://github.com/ggerganov/whisper.cpp). No server, no API key, nothing leaves the machine (or even the JVM). Models: `tiny(.en)`, `base(.en)`, `small(.en)`, `medium(.en)`, `large-v3`. |
| `speaches` | A self-hosted, free [Speaches](https://speaches.ai/) container - useful for GPU acceleration, but needs Docker or a native Python/`uv` install. |
| `openai` | OpenAI's hosted Whisper API. Requires `SttApiKey`; audio is uploaded to OpenAI and billed to your key. |
| `off` (blank) | Disables voice capture entirely - the tap is never installed. |

## Building from source

```
./gradlew build          # small plugin-only jar (build/libs)
./gradlew deployMod       # builds + copies the shaded jar (whisper-jni bundled) into your local Mods folder
./gradlew test            # unit tests (resampler/Opus-decode correctness, etc.)
```

You'll need to supply your own copy of `Hexcode-<version>.jar` under `libs/` - it's a third-party
dependency (see [Third-party components](#third-party-components) below) and isn't included in
this repo. Set `-Phytale_home=/path/to/Hytale` (or the `HYTALE_HOME` env var) if it isn't
auto-detected.

## License

VoxMagica is licensed under **GPLv3** (see [`LICENSE`](LICENSE)) - required because it depends on
and directly extends Hexcode's own GPLv3-licensed ECS component model.

### Third-party components

- **[Hexcode](https://github.com/Riprod)** (GPLv3) - the glyph-casting system VoxMagica is a
  voice front-end for. Not bundled or distributed; you supply your own copy.
- **[whisper.cpp](https://github.com/ggerganov/whisper.cpp)** (MIT), via
  **[whisper-jni](https://github.com/GiViMAD/whisper-jni)** (Apache-2.0) - in-process speech
  recognition for the `local` provider.
- **[Concentus](https://github.com/lostromb/concentus)** (permissive Opus/BSD-style license) -
  a portable pure-Java Opus decoder, vendored under
  `com.ev0smods.voxmagica.thirdparty.concentus` (see that package's `package-info.java` and
  `LICENSE` for details).
