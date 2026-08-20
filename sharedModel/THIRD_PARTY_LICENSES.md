# Third-Party Licenses

This module (`sharedModel`) declares runtime dependencies that ship
as JARs at build time. Each dependency retains its own license; this
file preserves the license texts that must travel with any
distribution per those licenses' terms.

This module is a Kotlin Multiplatform library whose `commonMain`
defines the public API surface; the `jvmMain` Android-target
implementation is what links against the third-party JARs listed
below. The actual shipping artifacts are JARs consumed by
upstream modules (notably `server` and `server-extend`); the
attribution travels with each upstream consumer, not with this
module itself.

---

## TwelveMonkeys ImageIO 3.12.0

- **Project**: <https://github.com/haraldk/TwelveMonkeys>
- **Maven coordinates**:
  - `com.twelvemonkeys.imageio:imageio-core:3.12.0`
  - `com.twelvemonkeys.imageio:imageio-metadata:3.12.0`
  - `com.twelvemonkeys.imageio:imageio-webp:3.12.0`
  - `com.twelvemonkeys.imageio:imageio-jpeg:3.12.0`
  - `com.twelvemonkeys.imageio:imageio-tiff:3.12.0`
- **License**: BSD 3-Clause ("New BSD" / "Modified BSD")
- **Copyright holder**: Harald Kuhr
- **License file source**: <https://github.com/haraldk/TwelveMonkeys/blob/master/LICENSE.txt>
- **Use**: Added to the JVM-only source set of this multiplatform
  module. The `structs.image.ImageDecoder` utility requires WebP,
  CMYK JPEG, indexed-color PNG, and TIFF-codec support beyond
  the JDK's bundled ImageIO set; TwelveMonkeys provides all
  four. Introduced alongside the Sand Martello map upload fix
  (2026-08-15).

### License text

```
BSD 3-Clause License

Copyright (c) 2008-2020, Harald Kuhr
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Compliance notes

- The Maven JARs embed their own `META-INF/LICENSE.txt`; the
  Gradle build produces them on the consumer classpath.
- The full upstream license text is copied verbatim into the
  shipping Docker images by the consuming modules' `Dockerfile`
  (see `server-extend/Dockerfile` `COPY` of the per-dependency
  license files into `/opt/server-extend/legal/third-party/`).
- This module does NOT modify and redistribute TwelveMonkeys
  source.
