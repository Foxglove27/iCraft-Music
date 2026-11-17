# AI disclosure

iCraft - Music Player was developed with the help of AI. This page says what that means.

## Purpose
This is a learning project. The author builds it to learn how a Minecraft (NeoForge) mod works and
wants to understand the code, not just to have a finished mod. The AI is used as a tutor and pair
programmer: it writes the code step by step and explains what it does, and the author asks
questions, reads, tests and decides what happens next.

## Tools
- **Claude Code** (Anthropic's coding assistant, Claude models) wrote most of the Java code, the
  JSON data files (recipes, tags, loot tables, models), the language files, and drafts of the
  README, the changelog and the in-repo documentation.

## What the human author did
- Chose the idea, the features and their order, and decided every behaviour and design question.
- Works through the code in small steps, asks the AI to explain what it does and why, and
  keeps learning from it.
- Made all artwork by hand: the item textures, the GUI skins, the block models and the logo.
- Built and ran the mod after every step and tested each feature in game: singleplayer,
  a dedicated server with two players, several launchers, and together with other mods
  (a written test scenario was run before the release).
- Reports and fixes the bugs found in testing; takes responsibility for what is published.

## What the AI wrote, and how it was checked
- The audio code (OGG, MP3, FLAC, WAV and M4A/M4B playback, seeking, chapter markers) is AI-written
  and mostly original code. The decoders were compared with ffmpeg's output on test files, and
  everything was tried by ear in game.
- The multiplayer parts (docks, speakers, the server-driven playback) are AI-written and were tested
  by the author with two clients.
- AI-written code can contain mistakes that tests did not catch. If you find one, please report it.

## Not AI-written
- The ALAC decoder (`src/main/java/net/foxglove/icraft/client/audio/decode/alac`) is a copy of Peter McQuillan's BSD-licensed Java
  decoder. JLayer (MP3) and JAAD (AAC) are third-party libraries. See `THIRD_PARTY.md`.
- The artwork (see above), which is all rights reserved (`LICENSE.md`).

## Licence note
The code is released under the MIT licence (`LICENSE.md`) by the human author, who directed and
tested the work. Whether and how copyright applies to AI-generated code differs between countries
and is not settled; the author releases whatever rights they have.

## Not used
No AI-generated images, sounds or music are in the mod.
