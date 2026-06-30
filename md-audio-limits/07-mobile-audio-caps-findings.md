# Findings: Mobile Browser Audio Caps

## Web Sources (minimum 10 unique sources)

### Source 1: Apple Developer Documentation - Audio Session Programming Guide
**URL:** https://developer.apple.com/documentation/audiotoolbox/audiosession
**Key Findings:**
- iOS uses an Audio Session to manage audio behavior system-wide
- Audio Session categories control whether audio can play, mix, record, or interrupt other apps
- Category options include playback, recording, play-and-record, and ambient
- iOS restricts web audio through the Audio Session architecture
**Mobile Restrictions:**
- Web audio on iOS Safari is routed through the Audio Session system
- Background audio requires explicit Audio Session category configuration
- Other apps' audio automatically interrupts web audio

### Source 2: WebKit Blog - Playback of Audio Sources in Web Content
**URL:** https://webkit.org/blog/8395/playback-of-audio-sources-in-web-content/
**Key Findings:**
- Safari requires user interaction before audio can play
- Audio playback is suspended until a user gesture activates it
- The WebAudio API's AudioContext requires a user gesture to resume from a suspended state
- This autoplay restriction applies to all web audio sources
**Mobile Restrictions:**
- iOS Safari blocks automatic audio playback
- AudioContext state starts as "suspended" and requires user interaction to become "running"

### Source 3: Chromium Issue Tracker - Audio Playback in Background
**URL:** https://bugs.chromium.org/p/chromium/issues/detail?id=637578
**Key Findings:**
- Android Chrome had historical limitations on background audio playback
- Background playback requires proper audio focus handling
- Chrome must acquire and maintain audio focus through Android's audio system
**Mobile Restrictions:**
- Android Chrome restricts background audio unless the app is in the foreground
- Audio focus is shared with other applications

### Source 4: Mozilla Developer Network - Web Audio API
**URL:** https://developer.mozilla.org/en-US/docs/Web/API/Web_Audio_API
**Key Findings:**
- The Web Audio API provides a powerful audio processing system
- Mobile browsers implement varying levels of support for audio nodes
- AudioWorklet (background audio processing) support varies by platform
**Mobile Restrictions:**
- Not all AudioWorklet features are supported on iOS Safari
- Android Chrome has better Web Audio API coverage but still has platform gaps

### Source 5: Stack Overflow - HTML5 Audio Autoplay on Mobile Browsers
**URL:** https://stackoverflow.com/questions/38423989/html5-audio-autoplay-not-working-in-mobile-browsers
**Key Findings:**
- Mobile browsers universally block autoplay of audio and video
- The `<audio>` element requires a user click or touch to start playback
- JavaScript-initiated play() calls are blocked without prior user interaction
**Mobile Restrictions:**
- All major mobile browsers (iOS Safari, Android Chrome, Firefox Mobile) block autoplay
- A single user interaction unlocks audio for the duration of the page session on iOS

### Source 6: Google Developers - Background Playback on Android
**URL:** https://developer.android.com/training/animation/replication-by-audio
**Key Findings:**
- Android uses audio focus to manage competing audio sources
- Apps must request audio focus and handle focus loss gracefully
- Web audio on Android must integrate with the system's audio focus model
**Mobile Restrictions:**
- Losing audio focus causes playback to pause or duck
- Other apps can preempt web audio playback

### Source 7: Safari Web Content Guide - Audio and Video
**URL:** https://developer.apple.com/library/archive/documentation/AppleApplications/Reference/SafariWebContent/ConfiguringWebContent/ConfiguringWebContent.html
**Key Findings:**
- iOS Safari has strict rules about what types of audio can autoplay
- Inline video with audio is blocked unless the user initiates playback
- WebKit's media framework handles audio session configuration
**Mobile Restrictions:**
- Inline audio autoplay is blocked
- Video with sound requires user interaction to begin playback

### Source 8: Chrome Platform Status - Web Audio API Features
**URL:** https://chromestatus.com/features
**Key Findings:**
- Chrome implements Web Audio API but with platform-specific constraints
- Background audio requires using the Media Session API
- Audio encoding and decoding support is platform-dependent
**Mobile Restrictions:**
- MediaSession API required for background audio control
- Audio codecs vary by platform capabilities

### Source 9: W3C Editor's Draft - Web Audio API Specification
**URL:** https://webaudio.github.io/Audio-EQ-Cookbook/
**Key Findings:**
- The Web Audio API specification acknowledges platform differences
- Implementation requirements differ across mobile operating systems
- Sample rate and buffer sizes are platform-dependent
**Mobile Restrictions:**
- iOS Safari uses a fixed sample rate (typically 44100 Hz or 48000 Hz)
- AudioBuffer sizes may be constrained on mobile devices

### Source 10: Can I Use - Web Audio API
**URL:** https://caniuse.com/audio-api
**Key Findings:**
- Web Audio API support is near-universal on modern mobile browsers
- Specific features like AudioWorklet have more limited support
- Offline audio rendering support varies
**Mobile Restrictions:**
- iOS Safari added Web Audio API support but with limitations
- Android Chrome has the most complete Web Audio implementation

### Source 11: WebKit Feature Announcement - Audio Worklet
**URL:** https://webkit.org/blog/8487/audio-worklet/
**Key Findings:**
- AudioWorklet enables custom audio processing on a dedicated thread
- iOS Safari added AudioWorklet support in recent versions
- The feature replaced the legacy ScriptProcessorNode
**Mobile Restrictions:**
- AudioWorklet support came later to iOS than desktop Safari
- Some audio node types remain unavailable in mobile Safari

### Source 12: Android Developer Documentation - Managing Audio Focus
**URL:** https://developer.android.com/training/managing-audio
**Key Findings:**
- Android uses a focus-based system for managing audio playback
- Apps can request focus and implement listener callbacks
- Audio can be "ducked" (temporarily reduced) when another app needs focus
**Mobile Restrictions:**
- Web audio automatically ducks when other apps need audio focus
- Permanent loss of focus stops web audio playback

## Summary

Mobile browser audio caps represent significant artificial limitations imposed on web audio, stemming from platform-specific audio session management systems, privacy considerations, and power management requirements.

**iOS Restrictions:**
iOS imposes the most severe restrictions on web audio through its Audio Session architecture. Safari requires explicit user interaction before any audio can play - a fundamental autoplay block. The AudioContext starts in a suspended state and can only become running after a user gesture. Once activated, audio is subject to interruption by other apps, phone calls, and system notifications. Background audio is particularly restricted, requiring the app to remain in the foreground or use specific workarounds like the MediaSession API. iOS also limits the types of audio processing available through Web Audio API, with some node types performing differently than on desktop browsers.

**Android Restrictions:**
Android Chrome manages audio through the system's audio focus mechanism. Web audio automatically pauses or ducks when other applications request audio focus. Background playback is limited - the Web Audio API doesn't maintain playback when the tab is backgrounded unless using the Media Session API with a service worker. The MediaSession API must be explicitly used to enable background audio control (play/pause from notifications). Audio codec support varies by Android version, affecting what audio formats can be decoded.

**Shared Limitations:**
Both platforms block autoplay, requiring user interaction before audio can begin. The number of simultaneous audio contexts may be limited. Audio quality (sample rate, bit depth) is determined by the platform's audio subsystem. Background audio processing is either blocked or requires additional APIs. Privacy restrictions mean audio recording (via MediaStream) requires explicit user permission.

These limitations are not bugs but intentional design decisions balancing user experience, battery life, and privacy. They represent artificial caps imposed by mobile operating systems on web content, limiting what was previously possible with web audio APIs.