# Web Audio API Artificial Limitations Research

## Topic
Artificial limitations imposed on web audio: file size limits, format restrictions, browser-specific caps, intentionally imposed quotas, and any deliberately artificial restrictions imposed by browsers or the Web Audio API specification.

## Target Sources
100 unique technical sources

## Research Threads
| Thread ID | Aspect | Status | File |
|-----------|--------|--------|------|
| 01 | Audio File Size Limits: decodeAudioData, AudioBuffer maximum size | PENDING | 01-audio-file-size-limits-findings.md |
| 02 | Audio Format Restrictions: supported codecs, MP3/OGG/WAV limitations | PENDING | 02-audio-format-restrictions-findings.md |
| 03 | Chrome Browser Audio Limits: quotas, restrictions, artificial caps | PENDING | 03-chrome-audio-limits-findings.md |
| 04 | Safari Audio Restrictions: iOS Safari limitations, autoplay policy | PENDING | 04-safari-audio-restrictions-findings.md |
| 05 | Firefox Audio Restrictions: Gecko-specific limitations | PENDING | 05-firefox-audio-restrictions-findings.md |
| 06 | Web Audio API Specification Limits: render quantum, channel limits | PENDING | 06-webaudio-spec-limits-findings.md |
| 07 | Mobile Browser Audio Caps: iOS/Android audio session restrictions | PENDING | 07-mobile-audio-caps-findings.md |
| 08 | Security-Motivated Audio Restrictions: CORS, autoplay, mixWithOthers | PENDING | 08-security-audio-restrictions-findings.md |
| 09 | Number Limits: max AudioNodes, concurrent sources, buffer limits | PENDING | 09-number-limits-findings.md |
## Synthesis Status
COMPLETE

## Completed At
2026-05-18 (Phase 1 + Phase 2 + Gap Fill complete)

## Final Report
FINAL-report-web-audio-limitations.md (54KB, 502 lines)

## Source Count
- Total citations: 157
- Unique URLs: 104
- Target (100+ unique): ACHIEVED

## Files in md-audio-limits/
- 00-research-steering.md - Master coordination
- 01-10: Core research threads (10 files)
- 11-13: Gap-fill threads (3 files)
- 99-comprehensive-count.md - Final source count
- FINAL-report-web-audio-limitations.md - Complete synthesis

## Key Artificially Imposed Limitations Found

### Spec-Mandated Limits
- Render quantum: 128 samples (~2.9ms at 44.1kHz)
- IIRFilter coefficients: max 20 elements
- DelayNode max: 180 seconds
- Sample rate range: 3000-768000 Hz
- StereoPannerNode: exactly 2 channels only
- PeriodicWave: minimum 8192 elements

### Browser-Imposed Limits
| Browser | Key Artificial Limits |
|---------|----------------------|
| Chrome | ~2GB AudioBuffer max, ~32-64 concurrent sources, kMaxAudioBufferFrames internal constant |
| Safari/iOS | User gesture required, sample rate locked to hardware, strict autoplay policy |
| Firefox | Autoplay blocking (Firefox 66+), AudioWorklet later (2020), conservative memory allocation |

### Security-Motivated Restrictions
- AudioContext starts suspended (requires user gesture)
- CORS requirements for cross-origin audio
- AudioWorklet requires secure context
- OutputLatency read-only (privacy protection)

### Mobile-Specific Caps
- iOS: Audio Session architecture with system controls
- Android: Audio focus system, web audio pauses/ducks
- Both: Background audio severely restricted
- Both: Privacy restrictions on audio recording

## Notes
- Focus on ARTIFICIAL limitations (deliberately imposed by browsers/spec) vs PHYSICAL limitations (sample rate, bit depth)
- Document exact numbers where possible: "Chrome limits AudioBuffer to X seconds"
- Note which restrictions are spec-mandated vs browser-specific
- Research WHY each limitation exists when documented