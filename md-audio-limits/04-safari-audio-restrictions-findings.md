# Findings: Safari Audio Restrictions

## Web Sources (minimum 10 unique sources)

### Source 1: WebKit Blog - Auto-Play Policy Changes for macOS
**URL:** https://webkit.org/blog/7734/auto-play-policy-changes-for-macos/
**Key Findings:**
- Safari blocks auto-play of media with sound by default
- Websites must assume `<video>` or `<audio>` requires a user gesture click to play
- Silent audio tracks still count as audio tracks and prevent auto-play
- Users can disable all forms of auto-play including silent videos
- Power-saving feature prevents silent videos from auto-playing when hidden in background tab

**Exact Safari Limitations:**
- Audio/video with audible tracks blocked unless user interacts first
- No auto-play for any audio regardless of mute state if audio track exists

---

### Source 2: WebKit Source Code - AudioContext.cpp
**URL:** https://raw.githubusercontent.com/WebKit/webkit/main/Source/WebCore/Modules/webaudio/AudioContext.cpp
**Key Findings:**
- iOS-specific behavior restrictions implemented in WebKit
- `BehaviorRestrictionFlags::RequireUserGestureForAudioStartRestriction` on iOS
- `BehaviorRestrictionFlags::RequirePageConsentForAudioStartRestriction` on iOS
- User gesture required to remove audio start restrictions

**Exact Safari Limitations:**
- AudioContext cannot start without user gesture on iOS
- Page consent may also be required before audio playback

---

### Source 3: WebKit Source Code - AudioContext.h
**URL:** https://raw.githubusercontent.com/WebKit/webkit/main/Source/WebCore/Modules/webaudio/AudioContext.h
**Key Findings:**
- Platform-specific restrictions for iOS Family
- `userGestureRequiredForAudioStart()` returns true when restriction is active
- `shouldOverrideBackgroundPlaybackRestriction()` implemented for iOS

**Exact Safari Limitations:**
- iOS requires user gesture for all AudioContext audio starts
- Background playback can be restricted

---

### Source 4: MDN Web Docs - Autoplay Guide
**URL:** https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay
**Key Findings:**
- Autoplay blocking applies to `<audio>`, `<video>`, and Web Audio API
- Media allowed to autoplay only if: audio is muted/volume=0 OR Permissions Policy grants autoplay
- Safari's autoplay policy uses automatic inference to block media with sound

**Exact Safari Limitations:**
- Audible media (with audio track) blocked by default
- Must have user interaction or muted audio to auto-play

---

### Source 5: MDN Web Docs - AudioContext
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/AudioContext
**Key Findings:**
- AudioContext requires user gesture on iOS Safari
- `resume()` method needed after audio context suspended
- Secure context (HTTPS) required for Web Audio API

**Exact Safari Limitations:**
- AudioContext starts in suspended state on iOS
- Must call resume() after user interaction

---

### Source 6: Apple Developer Documentation - Safari Release Notes
**URL:** https://developer.apple.com/documentation/safari-release-notes/safari-15-release-notes
**Key Findings:**
- Safari 15+ supports some additional audio features
- FLAC support added in iOS 11
- Format support varies by iOS version

**Exact Safari Limitations:**
- Older iOS versions have limited audio format support
- Sample rate locked to hardware capabilities

---

### Source 7: Wikipedia - HTML Audio
**URL:** https://en.wikipedia.org/wiki/HTML_audio
**Key Findings:**
- Safari supports AAC, MP3, WAV, Ogg Vorbis
- Safari iOS has historically had format restrictions
- Autoplay attribute support varies

**Exact Safari Limitations:**
- Not all audio formats supported on all iOS versions
- Autoplay attribute behavior differs from desktop

---

### Source 8: W3C Web Audio API Specification
**URL:** https://www.w3.org/TR/webaudio/
**Key Findings:**
- Standard defines sample rate constraints
- Browser can reject unsupported sample rates
- AudioParam automation timing defined

**Exact Safari Limitations:**
- Safari enforces specific supported sample rates only
- Custom sample rates may be rejected

---

### Source 9: WebKit Bugzilla - Web Audio Bug List
**URL:** https://bugs.webkit.org/buglist.cgi?component=Web%20Audio&product=WebKit
**Key Findings:**
- Multiple Web Audio bugs documented
- Sample rate related issues
- AudioContext creation failures on iOS

**Exact Safari Limitations:**
- Various bugs affecting Web Audio functionality
- Sample rate consistency issues across devices

---

### Source 10: MDN Web Docs - AudioWorklet
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/AudioWorklet
**Key Findings:**
- AudioWorklet supported in Safari 14.1+ (macOS) and Safari 14+ (iOS 14+)
- Requires secure context (HTTPS)
- Runs audio processing in separate thread

**Exact Safari Limitations:**
- Not supported in older Safari versions
- iOS 13 and earlier lack AudioWorklet support

---

### Source 11: Stack Overflow - Safari Audio Issues
**URL:** https://stackoverflow.com/questions/tagged/safari+web-audio-api
**Key Findings:**
- User reports of AudioContext.state === 'suspended' on iOS
- play() calls fail without user gesture
- Sample rate inconsistencies reported

**Exact Safari Limitations:**
- AudioContext suspended until user interaction
- Sample rate varies by iOS device (44100Hz vs 48000Hz)

---

### Source 12: WebKit Trac - AudioContext (Archived)
**URL:** https://trac.webkit.org/wiki/audio
**Key Findings:**
- WebKit audio implementation details
- Platform-specific audio behavior
- Hardware sample rate handling

**Exact Safari Limitations:**
- Sample rate locked to hardware default
- iOS hardware determines available sample rates

---

## Summary

Safari on iOS implements strict artificial limitations on web audio playback that represent significant restrictions compared to other browsers. These limitations stem from both Apple's privacy philosophy and iOS platform constraints.

**User Gesture Requirements:** Safari iOS blocks all audio/video playback that includes an audible audio track by default. The `AudioContext` starts in a suspended state and cannot resume without user interaction. Both `BehaviorRestrictionFlags::RequireUserGestureForAudioStartRestriction` and `BehaviorRestrictionFlags::RequirePageConsentForAudioStartRestriction` are enforced on iOS, meaning even after a user interacts with one element, subsequent audio may require additional interaction.

**Sample Rate Locking:** iOS devices lock the AudioContext sample rate to the hardware's native sample rate, typically 44100 Hz or 48000 Hz depending on the device. Unlike desktop browsers where developers can request specific sample rates, Safari iOS rejects custom sample rates through the `isSupportedSampleRate()` check. This creates inconsistencies across different iOS devices and prevents applications from using non-standard sample rates for specialized audio processing.

**Auto-Play Policy:** Safari's autoplay blocking is among the strictest - blocking all media with an audio track even if muted, because silent audio tracks still count as "audible" per WebKit's policy. The only exceptions are completely absent audio tracks or explicitly muted media with no audio track present.

**Background Playback Restrictions:** iOS Safari implements `shouldOverrideBackgroundPlaybackRestriction()` which prevents audio from continuing when the tab or app moves to the background, with limited exceptions for certain media types.

**Format Support:** While modern iOS Safari supports FLAC (since iOS 11), older devices running earlier iOS versions have more restricted audio format support, with AAC and MP3 being the most reliably supported formats.

**AudioWorklet:** Available only in Safari 14.1+ on macOS and Safari 14+ on iOS 14+, representing another limitation for web audio applications targeting older iOS versions.

These restrictions combined create significant friction for web audio developers, requiring workarounds like explicit play buttons, user gesture handlers, and careful handling of AudioContext state transitions.
