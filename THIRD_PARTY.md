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

## JAAD 0.8.7 (AAC decoding for .m4a and .m4b)

- Project: <https://github.com/DV8FromTheWorld/JAADec> (original by in-somnia, <https://jaadec.sourceforge.net/>)
- Maven: `de.sfuhrm:jaad:0.8.7`
- Licence: public domain
- Included unmodified inside the iCraft jar (`META-INF/jarjar/`, loaded through NeoForge jar-in-jar). Only its AAC
  decoder is used; the MP4 container and chapter reading is done by iCraft's own `Mp4File`.

## Apple Lossless (ALAC) decoder (for .m4a and .m4b files in Apple Lossless format)

- Source: the Java Apple Lossless decoder by Peter McQuillan (<https://github.com/soiaf/Java-Apple-Lossless-decoder>,
  also published as `com.tianscar.javasound:javasound-alac`), itself a port of David Hammerton's ALAC decoder.
- Licence: BSD 3-Clause. The source files say "Copyright (c) 2011 Peter McQuillan, all rights reserved, distributed
  under the BSD Software License"; the standard BSD 3-Clause text applies:

  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that
  > the following conditions are met: (1) Redistributions of source code must retain the above copyright notice,
  > this list of conditions and the following disclaimer. (2) Redistributions in binary form must reproduce the
  > above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other
  > materials provided with the distribution. (3) Neither the name of the copyright holder nor the names of its
  > contributors may be used to endorse or promote products derived from this software without specific prior
  > written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
  > WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
  > PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
  > DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  > PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
  > CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
  > OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- Three of its classes (the decoder, its state and two small helpers) are copied into iCraft's source
  (`net.foxglove.icraft.client.audio.decode.alac`) with their original headers, changed only to live in iCraft's
  package and to read the codec configuration straight from the MP4 file. The library's own MP4 reader is not used.
  (The licence text above is the standard BSD 3-Clause text; the original `license.txt` was not available.)

## Not bundled, only used

- **stb_vorbis** (Ogg Vorbis decoding) comes with Minecraft through LWJGL and is not part of this jar.
- **javax.sound.sampled** (WAV reading) is part of the Java runtime.

## Written for this mod

The FLAC decoder (`FlacSource`) and the MP4 reader (`Mp4File`) are original code, under the same licence as the rest of the iCraft source.
