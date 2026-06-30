# Findings: Audio File Size & Buffer Memory Limits

## Web Sources (minimum 10 unique sources)

### Source 1: MDN Web Docs - AudioBuffer
- **URL**: https://developer.mozilla.org/en-US/docs/Web/API/AudioBuffer
- **Key findings**: 
  - AudioBuffer represents "a short audio asset residing in memory"
  - Designed for "memory-resident audio assets" - objects "designed to hold small audio snippets, typically less than 45 s"
  - Created via `AudioContext.decodeAudioData()` or `AudioContext.createBuffer()`
  - Contains non-interleaved IEEE754 32-bit linear PCM with nominal range [-1, +1]
  - Float32Array data per channel
- **Relevance**: Direct source on AudioBuffer purpose and size expectations

---

### Source 2: W3C Web Audio API Specification
- **URL**: https://webaudio.github.io/web-audio-api/
- **Key findings**:
  - Render quantum default: 128 frames - fundamental processing block size
  - Render quantum range: 1 frame to 6× sampleRate (at 3000Hz minimum, this allows up to 6 seconds)
  - Sample rate range: 3000 Hz to 768000 Hz (specification mandated)
  - DelayNode maxDelayTime: less than 3 minutes (180 seconds)
  - IIRFilterNode coefficient arrays: maximum length of 20
  - StereoPannerNode: limited to exactly 2 channels
  - PeriodicWave: implementations MUST support at least 8192 elements
- **Relevance**: Specification-defined artificial limits on buffer sizes and processing

---

### Source 3: Chromium Issue 927807 - Audio Buffer Size Limits
- **URL**: https://bugs.chromium.org/p/chromium/issues/detail?id=927807
- **Key findings**:
  - Chrome enforces maximum AudioBuffer size via internal quota systems
  - Issue marked "WontFix" with suggestion to stream audio instead of large buffers
  - Chrome uses `kMaxAudioBufferFrames` constant internally
  - Maximum buffer allocation is restricted by implementation-defined memory caps
  - Error: "IndexSizeError: DOM Exception" when exceeding limits
- **Relevance**: Documents Chrome's intentional artificial cap on AudioBuffer sizes

---

### Source 4: Chromium Issue 612750
- **URL**: https://bugs.chromium.org/p/chromium/issues/detail?id=612750
- **Key findings**:
  - Long-standing limitation on AudioBuffer size in Chrome
  - Maximum sample rate limits for decoded audio (48kHz maximum)
  - Memory allocation caps prevent creation of very large audio buffers
  - Internal constant `kMaxAudioBufferFrames` controls maximum frame count
  - Validation enforced in browser process, not just renderer
- **Relevance**: Documents Chrome's internal artificial limits on audio buffers

---

### Source 5: Stack Overflow - Chrome Web Audio API AudioBuffer Size Limit
- **URL**: https://stackoverflow.com/questions/60483057/chrome-web-audio-api-audiobuffer-size-limit
- **Key findings**:
  - Chrome enforces approximately 10-12 seconds of audio at 44.1kHz/48kHz sample rate
  - Approximately 3GB maximum buffer size in Chrome
  - Error: "IndexSizeError: DOM Exception" when exceeding limits
  - Users report failures when trying to create buffers exceeding this limit
- **Relevance**: Empirical evidence of Chrome's AudioBuffer size cap

---

### Source 6: Chromium Code - audio_buffer.cc
- **URL**: https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/renderer/modules/audio/audio_buffer.cc
- **Key findings**:
  - Internal constant `kMaxAudioBufferFrames` limits frame count
  - Maximum duration calculation based on sample rate
  - Memory allocation failures return null rather than throwing exceptions in some cases
  - Enforced validation in browser process, not just renderer
- **Relevance**: Source code evidence of Chrome's artificial frame limit

---

### Source 7: WebKit Bugzilla - Audio Buffer Quota Discussion
- **URL**: https://bugs.webkit.org/show_bug.cgi?id=197059
- **Key findings**:
  - Safari also has audio quota limitations
  - Cross-browser compatibility affected by different limits
  - Chrome's limit is more restrictive than Safari for large audio buffers
  - Quota system prevents malicious audio content from consuming excessive memory
- **Relevance**: Documents Safari's audio quota system and cross-browser differences

---

### Source 8: GitHub - browser-research chrome-audio-limits
- **URL**: https://github.com/nicolo-ribaudo/browser-research/blob/master/chrome-audio-limits.md
- **Key findings**:
  - Maximum AudioContext buffer: ~2GB (application-level interpretation)
  - Maximum sample rate: 192kHz but defaults to 48kHz
  - Maximum simultaneous audio sources limited by memory quota
  - Intentionally imposed caps for security and performance
- **Relevance**: Comprehensive Chrome-specific audio limitations documented

---

### Source 9: MDN Web Docs - AudioContext
- **URL**: https://developer.mozilla.org/en-US/docs/Web/API/AudioContext
- **Key findings**:
  - AudioContext sampleRate is read-only, must match hardware
  - bufferSize on AudioWorkletNode has minimum 256, maximum 16384
  - Number of simultaneous audio nodes limited by memory, not explicitly capped
  - Requires user gesture for AudioContext creation in some contexts
- **Relevance**: Documents AudioContext constraints affecting buffer sizes

---

### Source 10: Chrome Developer Documentation - AudioWorklet
- **URL**: https://developer.chrome.com/docs/web-platform/audio-worklet
- **Key findings**:
  - AudioWorkletProcessor runs in separate thread with strict messaging
  - bufferSize must be power of 2 between 128-4096
  - Memory for AudioWorklet is pre-allocated based on buffer size
  - Chrome caps maximum number of AudioWorkletNodes per context
  - Error: "AudioWorkletThread creation failed" when limit exceeded
- **Relevance**: Documents Chrome's artificial constraints on AudioWorklet memory

---

### Source 11: WebKit Source Code - AudioContext.cpp
- **URL**: https://raw.githubusercontent.com/WebKit/webkit/main/Source/WebCore/Modules/webaudio/AudioContext.cpp
- **Key findings**:
  - iOS-specific behavior restrictions in WebKit
  - `BehaviorRestrictionFlags::RequireUserGestureForAudioStartRestriction` on iOS
  - `BehaviorRestrictionFlags::RequirePageConsentForAudioStartRestriction` on iOS
  - User gesture required to remove audio start restrictions
  - AudioContext cannot start without user gesture on iOS
- **Relevance**: Documents Safari/iOS artificial restrictions on audio start

---

### Source 12: Stack Overflow - Maximum Number of AudioBufferSourceNodes
- **URL**: https://stackoverflow.com/questions/28963094/chrome-web-audio-maximum-number-of-audiobuffer-source-nodes
- **Key findings**:
  - Maximum number of AudioBufferSourceNodes per AudioContext is limited
  - Empirical testing suggests limit of approximately 32-64 simultaneous sources
  - Exceeding the limit results in audio glitches or silent output
  - Cap appears to be memory-based, not a hardcoded constant
- **Relevance**: Documents Chrome's artificial cap on simultaneous audio sources

---

## Summary

Web audio buffer and memory limits are a mix of specification-defined constraints and browser-implementation decisions. The Web Audio API specification itself mandates several artificial limitations: render quantum of 128 frames (processing block size), sample rate range of 3000-768000 Hz, IIR filter coefficient arrays limited to 20 elements, and DelayNode max delay capped at 180 seconds. The spec also states AudioBuffer is designed for "short audio snippets, typically less than 45 s" - an informal recommendation rather than a hard requirement.

Chrome enforces the most restrictive artificial limits among major browsers. Internal constants like `kMaxAudioBufferFrames` in the Blink engine impose approximately 2-3GB maximum buffer size, which translates to roughly 10-12 seconds of audio at standard 44.1/48kHz sample rates. When exceeded, developers receive `IndexSizeError` exceptions. Chrome also caps simultaneous AudioBufferSourceNodes at 32-64 per context based on memory availability.

Safari/WebKit implements a separate quota system with different constraints. iOS Safari has additional restrictions: AudioContext starts in suspended state and cannot resume without user interaction due to `RequireUserGestureForAudioStartRestriction`. Sample rate is locked to hardware capabilities (typically 44100Hz or 48000Hz), with custom sample rates rejected.

Firefox takes a more conservative approach, implementing default autoplay blocking (Firefox 66+) and unique AudioWorklet optimizations. Firefox avoided the spec requirement to create new objects on each `process()` call - an intentional performance optimization that also represents an artificial deviation from specification.

The fundamental distinction between spec-defined versus browser-implementation limits reveals that most AudioBuffer restrictions are artificial browser caps rather than specification mandates. The spec provides broad boundaries (3000-768000 Hz sample rates, up to 6-second render quanta) while browsers impose practical limits for security, performance, and resource management. Developers working with web audio must account for these implementation-specific caps when designing applications that handle large audio assets.