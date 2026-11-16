# Changelog

All notable changes to iCraft are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Changes that are not released yet are under
**Unreleased**; the version heading is added when a version is cut.

## [Unreleased]

### Added
- **Apple Lossless (ALAC) files** in `.m4a` and `.m4b` containers play (the format iTunes and Apple Music use for
  lossless music). The decoder is bit-exact with ffmpeg on the files I tested (16 and 24 bit, 44.1 and 48 kHz).
- **Podcasts.** A Podcasts entry in the menu, working like audiobooks: put one folder per show in `ipod/podcasts`
  and the episodes inside it. Shows can have a cover, episodes play in order and stop after the last one, and the
  iPod remembers where you stopped in every episode. Opening a show highlights the next episode you have not
  finished.
- **Hold Next or Previous** to skip within the song: after a moment it jumps 10 seconds ahead (or back) about
  every 0.4 seconds until you let go. A quick click still changes the track. Works for songs and audiobook
  chapters, not for music discs. Nothing happens while paused.
- **Songs remember where you stopped while the iPod is linked to a speaker**, like audiobook chapters: pausing,
  stopping, switching songs or quitting the game saves the position, and playing that song again carries on a
  couple of seconds earlier (the list shows the saved time). Without a linked speaker songs still start from the
  beginning. A song that played to the end starts over next time.
- **Audiobooks.** A new Audiobooks entry in the menu lists the books in `ipod/audiobooks`: one folder per
  book, with the chapters as files (or one `.m4b` file). The iPod remembers where you stopped in every chapter
  (also after quitting the game) and carries on from there, a couple of seconds earlier. Chapters play in order
  and stop after the last one; shuffle and repeat do not apply. The book list shows the saved time.
- **M4A and M4B files** can be played (AAC audio). Chapter markers inside an `.m4b` show up as chapters in the list,
  and Next/Previous jump between them. Known limit: AAC files that use "noise substitution" (for example files
  made with ffmpeg's built-in encoder at default settings) play with silent gaps.
- **One library folder**: put music in `ipod/music/<album>` and audiobooks in `ipod/audiobooks/<book>` (inside the
  game folder). The old `ipod_music` folder is still read for music.
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
- `THIRD_PARTY.md` (licence notices for the bundled JLayer, LGPL, and JAAD, public domain) is included in the jar.
- Log warnings for cover images that fail to load, for decoding errors during playback, and for file
  names the game cannot read (a launcher without a UTF-8 locale breaks symbols like `Ī`; set
  `LC_ALL=C.UTF-8` for the launcher).

### Changed
- Relinking a speaker while a file plays now carries on at the same second instead of restarting the song.
- Tracks and chapters are sorted naturally (`Track 2` before `Track 10`).
- The speaker link now remembers its dimension. **Existing links must be set again** (right-click the speaker).
- Discs are stored in the mod's own component and every iPod has an ID, so a playing song belongs to one specific iPod.
- Ogg files are memory-mapped instead of loaded whole; the 256 MiB file size limit is gone (2 GiB per file).

### Fixed
- `.m4a` music files from iTunes / Apple Music did not play (they are Apple Lossless, which was not supported).
  Unsupported audio codecs inside an MP4 file now say which codec it is in the log.
- Songs whose file names contain numbers now play in the right order (`Track 10` no longer comes before `Track 2`).
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
