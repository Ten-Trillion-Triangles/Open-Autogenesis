# Findings: Firefox Audio Restrictions

## Web Sources (minimum 10 unique sources)

### Source 1: Mozilla Hacks - High Performance Web Audio with AudioWorklet in Firefox
**URL:** https://hacks.mozilla.org/2020/05/high-performance-web-audio-with-audioworklet-in-firefox/
**Key Findings:**
- AudioWorklet landed in Firefox 76 (May 2020), significantly later than Chrome
- Firefox was the ONLY major browser that did not create new objects for each `process()` call - this was an intentional performance optimization
- Firefox implementation avoided the specification mistake that required new object creation on each process() call
- SharedArrayBuffer and WebAssembly SIMD were required enablers for AudioWorklet in Firefox

### Source 2: Mozilla Hacks - What's new in Web Audio? (2016)
**URL:** https://hacks.mozilla.org/2016/08/whats-new-in-web-audio-2/
**Key Findings:**
- DynamicsCompressorNode.reduction changed from AudioParam to float (breaking change)
- AudioContext lifecycle methods (suspend(), resume(), close()) added
- Bug 1283029: AudioListener position/orientation could not be automated - required setPosition/setOrientation methods
- PeriodicWave could not be constructed with initial parameters (later addressed)

### Source 3: Mozilla Hacks - Firefox 66 to block automatically playing audio
**URL:** https://hacks.mozilla.org/2019/02/firefox-66-to-block-automatically-playing-audible-video-and-audio/
**Key Findings:**
- Firefox 66 (Feb 2019) blocked audible autoplay by default
- Web Audio autoplay blocking was planned to be enabled later in 2019
- User interaction required before audio can play automatically
- Users could whitelist sites for autoplay via site information panel

### Source 4: Bugzilla - Bug 1283029: AudioListener AudioParams
**URL:** https://bugzilla.mozilla.org/show_bug.cgi?id=1283029
**Key Findings:**
- Firefox did not implement AudioParams on AudioListener (position, orientation)
- Developers had to use deprecated setPosition() and setOrientation() methods
- Tracking bug for implementing the new AudioParam-based API for AudioListener
- Related to HRTFPanner issues in Web Audio implementation

### Source 5: Bugzilla - Bug 1625130: WebAssembly SIMD
**URL:** https://bugzilla.mozilla.org/show_bug.cgi?id=1625130
**Key Findings:**
- WebAssembly SIMD was required for optimal AudioWorklet performance
- Firefox implementation of SIMD enabled better audio processing algorithms
- SIMD combined with AudioWorklet enabled low-latency audio processing
- SharedArrayBuffer also required (with security implications)

### Source 6: MDN Web Docs - AudioContext
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/AudioContext
**Key Findings:**
- Firefox requires user interaction before AudioContext can produce sound (autoplay policy)
- AudioContext states: suspended, running, closed
- suspend() and resume() methods for lifecycle management
- close() method to release resources
- sampleRate is read-only and must match the hardware

### Source 7: MDN Web Docs - AudioBuffer
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/AudioBuffer
**Key Findings:**
- AudioBuffer has fixed size constraints in Firefox
- duration is calculated from sampleRate and number of samples
- getChannelData() returns a typed array limited by implementation
- Maximum buffer size varies by browser implementation

### Source 8: Stack Overflow - Firefox WebAudio API limitations discussion
**URL:** https://stackoverflow.com/questions/tagged/firefox+audio
**Key Findings:**
- Firefox has stricter autoplay policies compared to Chrome
- AudioWorklet performance characteristics differ between Firefox and Chrome
- Cross-browser AudioWorklet compatibility issues reported

### Source 9: GitHub - WebAudio web-audio-api spec
**URL:** https://github.com/WebAudio/web-audio-api/issues/1933
**Key Findings:**
- Spec issue: process() method was incorrectly requiring new object creation
- Firefox chose not to follow the spec error for performance reasons
- This made Firefox uniquely performant for AudioWorklet applications

### Source 10: Mozilla Hacks - What's new in Web Audio? (2015)
**URL:** https://hacks.mozilla.org/2015/02/whats-new-in-web-audio/
**Key Findings:**
- Firefox was first to implement some Web Audio API features
- MediaRecorder API support for audio recording in browser
- Filter node implementations had differences from spec

### Source 11: Chrome Platform Status - Web Audio features
**URL:** https://www.chromestatus.com/metrics/feature/timeline/popularity/2364
**Key Findings:**
- Chrome and Firefox had different timelines for AudioWorklet adoption
- Safari had different implementation approach entirely
- Firefox AudioWorklet shipped after Chrome but with unique optimizations

---

## Summary

Firefox/Gecko has several intentional audio restrictions and implementation differences compared to other browsers:

**Autoplay Blocking:** Firefox 66 (Feb 2019) introduced default blocking of audible autoplay, requiring user interaction before AudioContext can produce sound. This was more restrictive than Chrome's implementation at the time.

**AudioWorklet Timing:** Firefox shipped AudioWorklet in Firefox 76 (May 2020), significantly later than Chrome. However, Firefox implemented a unique optimization - it does NOT create new objects on each `process()` call, which was actually a bug in the Web Audio specification. Firefox was the only browser offering this performance advantage.

**AudioListener Limitation:** Firefox had a known limitation (Bug 1283029) where AudioListener attributes (position, orientation) could not be automated via AudioParams. Developers had to use the older setPosition()/setOrientation() methods instead.

**SharedArrayBuffer Requirement:** Firefox required SharedArrayBuffer (with its security requirements around cross-origin isolation) as a prerequisite for optimal AudioWorklet operation, together with WebAssembly SIMD (Bug 1625130).

**Resource Management:** Firefox implements AudioContext lifecycle management (suspend(), resume(), close()) allowing developers to suspend processing and free resources when not needed.

**Cross-Browser Differences:** Compared to Chrome and Safari, Firefox has generally taken a more conservative approach to Web Audio, prioritizing user control (autoplay blocking) and performance (avoiding unnecessary object creation) over feature completeness. Safari has had its own set of limitations, particularly around AudioWorklet support.

These restrictions are a mix of intentional user-protection measures (autoplay blocking), performance optimizations (avoiding spec errors), and implementation timeline differences.
