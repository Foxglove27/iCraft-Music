# Changelog

All notable changes to iCraft, newest version first, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The top section is the version in `gradle.properties`;
new changes go there until the next version bump.

## [0.2.0]

### Added
- **iCraft Manual**: a book that explains the mod (the click wheel, your music folders, discs, the dock and speakers,
  keys, file sharing, settings). Craft it from a book and a redstone dust, right-click to read it. Every player gets
  one the first time they join a world; the server can switch that off with `giveManualOnFirstJoin` in
  `serverconfig/icraft_music-server.toml`. The pages are in English and German.
- **Bass Boost** in the iPod's Settings: Off, Low, Medium or High. It only changes what you hear (also from a dock),
  takes effect at once and is saved as `bassBoost` in `icraft_music-client.toml`.
- **Stereo speakers**: seen from the front of a dock, speakers on its left play the left channel and speakers on its
  right the right channel (speakers near the middle, a single speaker, or speakers all on one side play both
  channels mixed, as before). Turn it off with `stereoSpeakers` in `icraft_music-client.toml`.

### Fixed
- The time on the playing chapter or episode counts up while the iPod screen is open (it used to change only after
  closing and reopening the screen).
- Several speakers of one dock no longer echo slightly or sound thin and tinny: they now start on exactly the same
  sample of the song, even when one file takes longer to open than another (most noticeable with shared files).

## [0.1.0]

### Added
- **Private file sharing at a dock** (off by default). With `allowFileSharing=true` in the world's
  `serverconfig/icraft_music-server.toml`, the player who docked the iPod can play their own music, audiobooks and
  podcasts for everybody near the dock: the files are copied to the server, the other players download them
  automatically (and can turn that off with `downloadSharedFiles` in `icraft_music-client.toml`), and late joiners hear
  them from the current position. The dock keeps playing when the owner leaves. Resume positions stay with the
  owner. For private use only: sharing is refused for players who are not on a private network address
  (`privateNetworkOnly`, on by default), the cache has size, per-player and age limits, and whoever runs the server is
  responsible for the licences of what is shared. Defaults: files up to 512 MB, 2 GB cache, 512 MB per player, unused
  files are deleted after 7 days (an existing config file keeps its old values).
- Music from the NAS folders can be shared at a dock like local files (audiobooks and podcasts from the NAS already could). The
  checksums of your files are remembered in `icraft_music-hashes.json`, so a big album on a network share is read once;
  while a long list is prepared the action bar shows the progress, and if a file cannot be read it says which one.

### Fixed
- Owners no longer download their own shared files.
- No more crash when you leave a world, or the server stops or restarts, while a shared file is being requested.
- Safer file sharing: the game checks the file names in a shared list before it uses them, and two players uploading the
  same file at the same time no longer break each other's upload.
- A paused song is no longer heard for a split second after closing the Esc menu (the pause is applied again right away).
- The iPod can be taken out of a dock with sneak + right-click even when something is in the off hand.

## [0.0.9]

### Changed
- Take the last disc out of an iPod in your hand with Ctrl + right-click (it was Shift + right-click, which clashed with
  sneaking). Shift + right-click now simply opens the iPod. Taking a disc out in the inventory is unchanged.
- A music disc that plays from your iPod carries on from the same second when you put the iPod into a dock (it used to
  start again from the beginning).

### Fixed
- The game's own background music no longer starts over the iPod or a dock's music while it plays for you (it comes back
  afterwards). Switch: `silenceBackgroundMusic` in `icraft_music-client.toml`.
- Music recovers by itself when the game drops the sound: after the volume was set to 0 and back, after switching the
  audio device, and after walking away from a dock and back (the song carries on at the right second).
- Previous now keeps going back when it lands on a track that cannot be played (it used to jump forward again).
- Holding Backspace in the iPod no longer jumps back through several menus; a held key only goes back one level.
- Stacking speakers on a finished 2x4 wall no longer plays the "wall formed" chime and animation again.
- Skipping tracks with several speakers at a dock no longer leaves the new track silent until the second press: the new
  sounds now wait until the old speakers have given up their audio channels.
- Breaking an extra speaker on a finished wall no longer plays the "wall broken" cue while a complete wall is still standing.
- Shuffle and repeat are saved the moment you change them, not only when the iPod screen closes.
- Speakers and docks keep facing the right way when a structure is rotated or mirrored.
- Album covers are released again when you scroll past them, so a huge library no longer fills up video memory.
- A big network library is no longer read again every time you open the iPod (at most every 30 seconds).
- The speakers of a dock now start a track at the same moment (less echo at the start).
- A song that is skipped while it is still loading no longer leaves its file open.

## [0.0.8]

### Added
- **Other players hear a dock's speakers.** Music discs that play in a dock are now heard by everybody near its
  speakers, and everybody sees the music notes. Someone who walks up while a song is playing hears it from the
  current position.
- **The dock keeps playing by itself.** It plays through the disc list of the docked iPod (with shuffle and repeat)
  on the server, so the music goes on when the player who docked the iPod walks away or logs out.
- **Only the player who docked the iPod controls it** (play, pause, next, previous, skip, stop, shuffle and repeat,
  also with the keys). Others get a message who it belongs to. Only that player (or an operator) can take the iPod
  out. When the owner plays one of their own files at a dock, only they hear it, but everybody sees the notes.
- Music discs played from a dock can be skipped through by holding Next or Previous.
- Other players can stop a dock's music for themselves: double-click the middle wheel button in the dock's iPod screen,
  or use the new key (default `\`, "iPod: Stop a dock's music for me"). Nothing changes for the owner or anybody else;
  it stays stopped through the next songs and ends when they walk away and come back, or when the dock stops.
- **Big libraries and network shares.** `extraMusicFolders`, `extraAudiobookFolders` and `extraPodcastFolders` in
  `icraft_music-client.toml` add more folders to the library (absolute, or relative to the game folder). A network share has
  to be mounted first and its mount path used (`smb://` addresses do not work). A folder that directly contains audio
  files is an album, book or show, up to three levels deep, so `Artist/Album` shows as "Artist - Album". The mod never
  writes into these folders.
- **NAS** entry in the iPod menu (between Music and Audiobooks, shown only when an extra folder is set) with a submenu
  of Music, Audiobooks and Podcasts for the extra folders; the normal pages only show the local folders.
- **Search** in the iPod menu (between Podcasts and Settings): type on the keyboard and the matching albums, songs,
  audiobooks, chapters, podcasts and episodes (including the NAS) are listed live; accents and capitals do not matter
  ("avici" finds "AVĪCI"). Enter or a click plays or opens the result, Backspace deletes a letter, and the Menu button
  of an opened result goes back to the results.
- Long titles scroll through the row when it is selected, so names that did not fit can be read in full.
- Taking the iPod out of a dock keeps the music going for you: your own song carries on in your ears from the same
  second, and a music disc the dock was playing continues at about the same position.

### Changed
- The library is scanned in the background (when you join a world and when the iPod opens), so a big library or a slow
  share no longer freezes the game; the lists update when the scan is done ("Scanning library..."). Album covers also
  load in the background, and animated dots after the title show that a scan is running.
- Saved audiobook and podcast positions now live in `icraft_music-progress.json` in the game folder instead of inside the
  library (an old `ipod/.icraft-progress.json` is read once as the starting point and left alone).
- Songs no longer remember where you stopped and the song list shows no saved time: they always start from the
  beginning. Only audiobook chapters and podcast episodes resume.
- Ogg files up to 256 MiB are read into memory instead of memory-mapped, so a network share that disappears while a
  song plays ends the song with a message instead of crashing the game.
- Music discs in a dock are played by the mod itself instead of the game's own disc player, so they can start in
  the middle. Discs played from an iPod you hold are unchanged.
- The selected row of the iPod list is a dark grey bar with white text.

### Fixed
- A dock that already held an iPod before this version had no owner, so nobody could control it (and no notes or
  sound followed). It now belongs to the first player who uses it.
- Putting an iPod that is playing into a dock no longer leaves the music stuck and impossible to resume. A song or
  chapter carries on through the dock's speakers; a music disc is handed to the dock and plays from its start.
- A dock without any speaker now plays its discs in its owner's own ears (it was silent).

## [0.0.7]

### Added
- **Docking station.** A new block that holds an iPod and plays it through speakers. Right-click it with an iPod to
  dock it, with an empty hand to open the iPod, sneak + empty hand to take it out. Breaking the dock gives the iPod back.
- **Up to 4 speakers per dock.** Sneak + right-click the dock with an iPod to link that iPod to it, then right-click
  speakers with the iPod to add them (right-click again to remove). Speakers must be within 32 blocks of the dock.
  Every speaker plays the music, and the music keeps playing while you walk around.
- **Big speakers**: 8 speakers built as a block 2 wide and 4 tall, all facing the same way, count as one big
  speaker that carries twice as far as a single one.
- **Forming a big speaker has an animation**: when the last speaker of a 2 x 4 wall is placed, the wall lights up row
  by row from the bottom with a rising chime, note and spark particles, and a small burst on top.
- Breaking a big speaker makes a low, off-key note and a puff of smoke from all its speakers.
- **Music notes** rise from the speakers while they play.
- **Keys** for Play/Pause (`P`), Next (`]`) and Previous (`[`), shown under iCraft in the controls and changeable
  there. They work for any playing iPod, also one in a dock.
- **Apple Lossless (ALAC) files** in `.m4a` and `.m4b` containers play (the format iTunes and Apple Music use for
  lossless music). The decoder is bit-exact with ffmpeg on the files I tested (16 and 24 bit, 44.1 and 48 kHz).
- The licence notice for the bundled Apple Lossless decoder is in `THIRD_PARTY.md`.

### Changed
- An iPod can no longer be linked directly to one speaker; use a docking station. Existing speaker links on iPods are
  removed. An iPod that is not in a dock plays in your own ears.
- Songs now remember their position while they play through a dock's speakers (before: while linked to a speaker).
- Speakers carry further: a single speaker can be heard up to 32 blocks away (was 16), a big speaker up to 64 (was 32).

### Fixed
- Breaking a speaker, or a big speaker, now removes it from the dock (it no longer keeps using one of the 4 slots).
  The people nearby get a message. A broken big speaker no longer keeps playing as a small one.
- The speaker did not drop as an item when mined in survival. It now drops with a stone pickaxe or better.
- `.m4a` music files from iTunes / Apple Music did not play (they are Apple Lossless, which was not supported).
  Unsupported audio codecs inside an MP4 file now say which codec it is in the log.

## [0.0.6]

### Added
- **Audiobooks.** A new Audiobooks entry in the menu lists the books in `ipod/audiobooks`: one folder per
  book, with the chapters as files (or one `.m4b` file). The iPod remembers where you stopped in every chapter
  (also after quitting the game) and carries on from there, a couple of seconds earlier. Chapters play in order
  and stop after the last one; shuffle and repeat do not apply. The book list shows the saved time.
- **Podcasts.** A Podcasts entry in the menu, working like audiobooks: put one folder per show in `ipod/podcasts`
  and the episodes inside it. Shows can have a cover, episodes play in order and stop after the last one, and the
  iPod remembers where you stopped in every episode. Opening a show highlights the next episode you have not
  finished.
- **M4A and M4B files** can be played (AAC audio). Chapter markers inside an `.m4b` show up as chapters in the list,
  and Next/Previous jump between them. Known limit: AAC files that use "noise substitution" (for example files
  made with ffmpeg's built-in encoder at default settings) play with silent gaps.
- **One library folder**: put music in `ipod/music/<album>` and audiobooks in `ipod/audiobooks/<book>` (inside the
  game folder). The old `ipod_music` folder is still read for music.
- **Hold Next or Previous** to skip within the song: after a moment it jumps 10 seconds ahead (or back) about
  every 0.4 seconds until you let go. A quick click still changes the track. Works for songs and audiobook
  chapters, not for music discs. Nothing happens while paused.
- **Songs remember where you stopped while the iPod is linked to a speaker**, like audiobook chapters: pausing,
  stopping, switching songs or quitting the game saves the position, and playing that song again carries on a
  couple of seconds earlier (the list shows the saved time). Without a linked speaker songs still start from the
  beginning. A song that played to the end starts over next time.
- The licence notice for the bundled JAAD decoder (public domain) is in `THIRD_PARTY.md`.

### Changed
- Relinking a speaker while a file plays now carries on at the same second instead of restarting the song.
- Tracks and chapters are sorted naturally (`Track 2` before `Track 10`).

### Fixed
- Songs whose file names contain numbers now play in the right order (`Track 10` no longer comes before `Track 2`).

## [0.0.5]

### Added
- **Unlink a speaker**: right-click the speaker an iPod is linked to once more. Linking another speaker switches the link.
- Changing the link (link, unlink or switch) while a song plays restarts that song from its beginning on the new
  output (the speaker or your own ears). If the iPod is paused, this happens when it plays again.

### Changed
- The speaker link now remembers its dimension. **Existing links must be set again** (right-click the speaker).

### Fixed
- The highlighted row in the track list now follows the song when you press Next/Previous, and when the next
  song starts by itself.
- A speaker at the same coordinates in another dimension no longer counts as the linked speaker.

## [0.0.4]

### Added
- **Shuffle and repeat.** New Settings page with `Shuffle: On/Off` and `Repeat: Off/List/One`. Both are
  global (stored in `config/icraft_music-client.toml`, not per iPod) and take effect immediately, also while a
  song is playing. Next/Previous follow the shuffled order.
- **Top-level menu** (Music, Settings). The iPod now opens on it; the Menu button goes up one level.

## [0.0.3]

### Added
- **Autoplay**: when a song ends, the next track of the same list starts. Without repeat it stops after the last one.
- A track that cannot be played shows the "Could not play" message and is skipped. An album of only
  broken files stops instead of looping.
- MP3, FLAC, OGG and WAV are decoded inside the mod, streamed from disk, with seek support in the decoders.
  ffmpeg is **no longer needed**.
- The iPod holds up to 32 discs (was 8). A dyed iPod keeps its discs, speaker link and name. A white iPod recipe was added.
- A cyan iPod, and a larger iPod GUI.
- `Singles` / `Einzeltitel` name for loose files in `ipod_music`.
- `THIRD_PARTY.md` (licence notice for the bundled JLayer, LGPL) is included in the jar.
- Log warnings for cover images that fail to load, for decoding errors during playback, and for file
  names the game cannot read (a launcher without a UTF-8 locale breaks symbols like `Ī`; set
  `LC_ALL=C.UTF-8` for the launcher).

### Changed
- Discs are stored in the mod's own component and every iPod has an ID, so a playing song belongs to one specific iPod.
- Ogg files are memory-mapped instead of loaded whole; the 256 MiB file size limit is gone (2 GiB per file).

### Fixed
- After a track failed to load, the sound engine ran out of streaming channels ("Maximum sound pool size 8
  reached") and no file could play any more, most visible with a linked speaker.
- Missing lang key for the Singles album.
- MP3 decoding failed in development runs because the library was not on the game's classpath.
- Per-tick disc check no longer copies every disc.

## [0.0.2]

### Added
- Maximum file size limit and an error message when a track fails to load.
- iPod colours (each with its own item, texture, recipe and GUI skin).
- The bottom-right "now playing" panel shows the cover, title and album, and follows song changes.

### Fixed
- The HUD did not update when the song changed.

## [0.0.1]

### Added
- iPod item and speaker block, click-wheel GUI inside the iPod graphic, play/pause, next/previous and a
  volume bar that is saved to the config file.
- Plays vanilla music discs and your own files from `ipod_music`, with album folders and cover art.
- English and German language files.
