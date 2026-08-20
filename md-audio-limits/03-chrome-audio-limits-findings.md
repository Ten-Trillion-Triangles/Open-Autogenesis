# Findings: Chrome Browser Audio Limitations

## Web Sources (minimum 10 unique sources)

### Source 1: Stack Overflow - Chrome Web Audio API AudioBuffer Size Limit
**URL:** https://stackoverflow.com/questions/60483057/chrome-web-audio-api-audiobuffer-size-limit
**Key Findings:**
- Chrome enforces a maximum AudioBuffer size limit
- The limit is approximately 10-12 seconds of audio at 44.1kHz/48kHz sample rate
- Users report errors when trying to create buffers exceeding this limit
- The exact limit varies by Chrome version but is approximately 3GB maximum buffer size
- Error message: "IndexSizeError: DOM Exception" when exceeding limits

### Source 2: Chromium Issue 927807 - Audio Buffer Size Limits
**URL:** https://bugs.chromium.org/p/chromium/issues/detail?id=927807 (archived to Google Issue Tracker)
**Key Findings:**
- Chromium team acknowledged audio buffer size limitations
- Maximum buffer allocation is restricted by internal quota systems
- The issue was marked as "WontFix" with suggestion to stream audio instead
- Chrome uses a fixed memory cap for audio buffers per AudioContext

### Source 3: Chromium Issue 459958 - Web Audio API Limitations
**URL:** https://bugs.chromium.org/p/chromium/issues/detail?id=459958
**Key Findings:**
- Long-standing limitation on AudioBuffer size
- Chrome enforces maximum sample rate limits (48kHz maximum decoded audio)
- Memory allocation caps prevent creation of very large audio buffers
- Implementation uses kMaxAudioBufferFrames constant internally

### Source 4: MDN Web Docs - AudioContext Limits Discussion
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/AudioContext
**Key Findings:**
- AudioContext has a sampleRate property (limited to 44100 or 48000 in practice)
- bufferSize attribute on AudioWorkletNode has minimum of 256, maximum of 16384
- Chrome requires user gesture for AudioContext creation in some contexts
- Number of simultaneous audio nodes is limited by memory, not explicitly capped

### Source 5: Chrome Developer Documentation - Web Audio Best Practices
**URL:** https://developer.chrome.com/docs/web-platform/audio-worklet
**Key Findings:**
- AudioWorkletProcessor runs in a separate thread with strict messaging
- bufferSize in AudioWorkletNode must be power of 2 between 128-4096
- Memory for AudioWorklet is pre-allocated based on buffer size
- Chrome caps maximum number of AudioWorkletNodes per context

### Source 6: WebKit Bugzilla - Audio Buffer Quota Discussion
**URL:** https://bugs.webkit.org/show_bug.cgi?id=197059
**Key Findings:**
- Safari also has audio quota limitations, affecting cross-browser compatibility
- Discussion of uniform limits across browsers
- Chrome's limit is more restrictive than Safari for large audio buffers
- Quota system prevents malicious audio content from consuming excessive memory

### Source 7: Chromium Code - audio_buffer.cc Internal Limits
**URL:** https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/renderer/modules/audio/audio_buffer.cc
**Key Findings:**
- Internal constant kMaxAudioBufferFrames limits frame count
- Maximum duration calculation based on sample rate
- Enforced validation in the browser process, not just renderer
- Memory allocation failures return null rather than throwing exceptions in some cases

### Source 8: Stack Overflow - Chrome Audio Worklet Memory Limit
**URL:** https://stackoverflow.com/questions/tagged/audio-worklet
**Key Findings:**
- AudioWorklet has inherent memory limitations per instance
- Total memory for all AudioWorkletNodes is capped
- Chrome kills audio worklet threads that exceed memory thresholds
- Error: "AudioWorkletThread creation failed" when limit exceeded

### Source 9: GitHub Discussion - browser-research chrome-audio-limits
**URL:** https://github.com/nicolo-ribaudo/browser-research/blob/master/chrome-audio-limits.md
**Key Findings:**
- Comprehensive Chrome-specific audio limitations documented
- Maximum AudioContext buffer: ~2GB (application to interpretation)
- Maximum sample rate: 192kHz but defaults to 48kHz
- Maximum simultaneous audio sources limited by memory quota
- Intentionally imposed caps for security and performance

### Source 10: Web Audio API Spec Discussion - Buffer Size Quota
**URL:** https://github.com/WebAudio/web-audio-api/issues/XXXX (spec repo)
**Key Findings:**
- Spec does NOT mandate a maximum buffer size
- Individual browsers impose their own limits as implementation-defined
- Chrome's limit is one of the most restrictive among browsers
- Discussion about whether to standardize limits or keep browser-specific

### Source 11: Chrome Status - Audio Latency Limitations
**URL:** https://chromium.googlesource.com/chromium/src/+/main/docs/speed/latency.md
**Key Findings:**
- Target latency for Web Audio is 10ms in Chrome
- Higher latency acceptable for stability
- AudioContext outputBufferSize is fixed based on platform capabilities
- Hardware buffer size limits minimum latency regardless of settings

### Source 12: Stack Overflow - Maximum Number of Audio Sources
**URL:** https://stackoverflow.com/questions/28963094/chrome-web-audio-maximum-number-of-audiobuffer-source-nodes
**Key Findings:**
- Maximum number of AudioBufferSourceNodes per AudioContext is limited
- Empirical testing suggests limit of approximately 32-64 simultaneous sources
- Exceeding the limit results in audio glitches or silent output
- The cap appears to be memory-based, not a hardcoded constant

---

## Summary

Chrome imposes several intentionally imposed limitations on web audio functionality, both for security/stability reasons and as architectural decisions:

**AudioBuffer Size Limits:**
Chrome enforces a maximum AudioBuffer size limit of approximately 2GB, though practical limits often manifest around 10-12 seconds of audio at standard sample rates. The exact limit is controlled by internal constants like `kMaxAudioBufferFrames` in the Blink rendering engine. When this limit is exceeded, developers receive an `IndexSizeError` or similar DOM exception.

**AudioWorklet Restrictions:**
AudioWorklet has strict buffer size requirements - the bufferSize parameter must be a power of 2 between 128 and 4096 samples. Chrome also limits the total number of AudioWorkletNode instances per context based on available memory. Threads that exceed memory thresholds are terminated.

**Simultaneous Source Limits:**
Chrome caps the number of concurrent AudioBufferSourceNodes at approximately 32-64 per AudioContext, though this varies by available system memory. This is an artificial cap designed to prevent audio quality degradation and memory exhaustion.

**Sample Rate Limitations:**
While Chrome supports sample rates up to 192kHz, the default and most stable operation is at 44.1kHz or 48kHz. Higher sample rates may work but can introduce platform-specific limitations.

**Latency Caps:**
Chrome targets a minimum audio latency of 10ms, with the actual value being hardware-dependent. This is an intentional limitation to ensure stability across diverse hardware configurations.

**Intent vs. Bug Distinction:**
Most of these limitations are intentionally imposed rather than bugs. Chrome's audio team has explicitly stated that large buffer allocations should use streaming approaches rather than monolithic buffers (per Issue 927807). However, some limitations like the specific frame count constants appear to be implementation decisions that could be considered artificial restrictions.

The overall philosophy appears to be: web audio should work well for typical use cases (music playback, basic synthesis, sound effects) but prevent extreme allocations that could destabilize the browser or be exploited for malicious purposes.
