# Changelog

All notable changes to iCraft are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Changes that are not released yet are under
**Unreleased**; the version heading is added when a version is cut.

## [Unreleased]

### Added
- **Unlink a speaker**: right-click the speaker an iPod is linked to once more. Linking another speaker switches the link.
- Changing the link (link, unlink or switch) while a song plays restarts that song from its beginning on the new
  output (the speaker or your own ears). If the iPod is paused, this happens when it plays again.
- **Shuffle and repeat.** New Settings page with `Shuffle: On/Off` and `Repeat: Off/List/One`. Both are
  global (stored in `config/icraft-client.toml`, not per iPod) and take effect immediately, also while a
  song is playing. Next/Previous follow the shuffled order.
- **Top-level menu** (Music, Settings). The iPod now opens on it; the Menu button goes up one level.
- **Autoplay**: when a song ends, the next track of the same list starts. Without repeat it stops after the last one.
- A track that cannot be played shows the "Could not play" message and is skipped. An album of only
  broken files stops instead of looping.
- MP3, FLAC, OGG and WAV are decoded inside the mod, streamed from disk, with seek support in the decoders
  (not used by the player yet). ffmpeg is **no longer needed**.
- The iPod holds up to 32 discs (was 8). A dyed iPod keeps its discs, speaker link and name. A white iPod recipe was added.
- `Singles` / `Einzeltitel` name for loose files in `ipod_music`.
- `THIRD_PARTY.md` (licence notice for the bundled JLayer, LGPL) is included in the jar.
- Log warnings for cover images that fail to load, for decoding errors during playback, and for file
  names the game cannot read (a launcher without a UTF-8 locale breaks symbols like `Ī`; set
  `LC_ALL=C.UTF-8` for the launcher).

### Changed
- The speaker link now remembers its dimension. **Existing links must be set again** (right-click the speaker).
- Discs are stored in the mod's own component and every iPod has an ID, so a playing song belongs to one specific iPod.
- Ogg files are memory-mapped instead of loaded whole; the 256 MiB file size limit is gone (2 GiB per file).
- `.m4a` is no longer listed in the library (it was only playable through ffmpeg).

### Fixed
- The highlighted row in the track list now follows the song when you press Next/Previous, and when the next
  song starts by itself.
- A speaker at the same coordinates in another dimension no longer counts as the linked speaker.
- After a track failed to load, the sound engine ran out of streaming channels ("Maximum sound pool size 8
  reached") and no file could play any more, most visible with a linked speaker.
- Missing lang key for the Singles album.
- MP3 decoding failed in development runs because the library was not on the game's classpath.
- Per-tick disc check no longer copies every disc.

## [0.0.2-alpha] - 2026-11-15

### Added
- Maximum file size limit and an error message when a track fails to load.
- iPod colours (each with its own item, texture, recipe and GUI skin).
- The bottom-right "now playing" panel shows the cover, title and album, and follows song changes.

### Fixed
- The HUD did not update when the song changed.

## [0.0.1] - 2026-11-12 to 2026-11-14

### Added
- iPod item and speaker block, click-wheel GUI inside the iPod graphic, play/pause, next/previous and a
  volume bar that is saved to the config file.
- Plays vanilla music discs and your own files from `ipod_music`, with album folders and cover art.
- English and German language files.
