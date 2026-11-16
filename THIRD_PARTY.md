# Third-party software in iCraft

## JLayer 1.0.1 (MP3 decoding)

- Project: <http://www.javazoom.net/javalayer/javalayer.html>
- Maven: `javazoom:jlayer:1.0.1`
- Copyright (C) 1999-2010 JavaZoom
- Licence: GNU Lesser General Public License, version 2.1 or later
  (<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>)

JLayer is included **unmodified** as its own file inside the iCraft jar
(`META-INF/jarjar/jlayer-1.0.1.jar`, loaded through NeoForge jar-in-jar). It is not merged into
iCraft's own classes. To use a modified JLayer, replace that file inside the jar with your own
build of the same library; iCraft only uses its public `javazoom.jl.decoder` classes.

## Not bundled, only used

- **stb_vorbis** (Ogg Vorbis decoding) comes with Minecraft through LWJGL and is not part of this jar.
- **javax.sound.sampled** (WAV reading) is part of the Java runtime.

## Written for this mod

The FLAC decoder (`FlacSource`) is original code, under the same licence as the rest of the iCraft source.
