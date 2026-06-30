# Findings: Web Audio API Specification Limits

## Web Sources (minimum 10 unique sources)

### Source 1: W3C Web Audio API Specification (Primary)
**URL**: https://webaudio.github.io/web-audio-api/
**Key Findings**:
- **Render Quantum Size**: Default value is 128 frames. The render quantum is the block size for audio processing, constant for the lifetime of an AudioContext.
- **Supported Render Quantum Sizes**: MUST be between 1 sample frame and 6 times the context sample rate (inclusive). At 44100Hz, this allows render quanta from 1 to 264,600 frames. The upper limit of 6 seconds was chosen to support deprecated ScriptProcessorNode at its highest buffer size of 16384 and lowest sample rate of 3000 Hz.
- **Sample Rate Range**: Implementations MUST support sample rates between 3000 Hz and 768000 Hz (inclusive).
- **AudioParam Value Range**: minValue = most-negative-single-float (-3.4028235e38), maxValue = most-positive-single-float (3.4028235e38)
- **PeriodicWave**: Conforming implementations MUST support PeriodicWave up to at least 8192 elements.
- **IIRFilterNode Coefficients**: Maximum length of feedforward/feedback coefficient arrays is 20. Array length of 0 or greater than 20 throws NotSupportedError.
- **DelayNode maxDelayTime**: Must be greater than zero and less than three minutes (180 seconds).
- **ChannelCount Constraints**: AudioNode channelCount MUST be between 1 and maxChannelCount (hardware-dependent).
- **StereoPannerNode Channel Limitations**: Limited to mixing no more than 2 channels of audio, producing exactly 2 channels.
- **ChannelMergerNode/SplitterNode**: Channel count cannot be changed after creation.

### Source 2: MDN Web Docs - AudioDestinationNode
**URL**: https://developer.mozilla.org/en-US/docs/Web/API/AudioDestinationNode
**Key Findings**:
- maxChannelCount property: unsigned long defining maximum channels the physical device can handle
- This is hardware-dependent, not artificially limited by the spec beyond min 1

### Source 3: MDN Web Docs - BaseAudioContext
**URL**: https://developer.mozilla.org/en-US/docs/Web/API/BaseAudioContext
**Key Findings**:
- sampleRate property: floating point number representing sample rate in samples per second
- Sample-rate converters are not supported (implied by spec design)
- No sample-rate conversion is performed by the API itself

### Source 4: W3C Wiki - HTML Audio Element
**URL**: https://www.w3.org/wiki/HTML/Elements/audio
**Key Findings**:
- Historical context for Web Audio API design decisions
- Modular routing design with audio nodes

### Source 5: Wikipedia - Web Audio API
**URL**: https://en.wikipedia.org/wiki/Web_Audio_API
**Key Findings**:
- High-precision timing for sample-accurate scheduling
- Modular routing architecture overview
- Timing controlled with high precision and low latency

### Source 6: MDN - AudioParam
**URL**: https://developer.mozilla.org/en-US/docs/Web/API/AudioParam
**Key Findings**:
- Automation methods: setValueAtTime(), linearRampToValueAtTime(), exponentialRampToValueAtTime(), setTargetAtTime(), setValueCurveAtTime()
- Each AudioParam maintains a list of automation events in ascending time order
- Values are single-precision floats (IEEE-754 32-bit)

### Source 7: MDN - AudioWorklet
**URL**: https://developer.mozilla.org/en-US/docs/Web/API/AudioWorklet
**Key Findings**:
- AudioWorklet processor runs in render quantum blocks (128 frames by default)
- inputs[n][m] is Float32Array of samples for mth channel of nth input
- Outputs are zero-filled Float32Arrays

### Source 8: AudioWorklet Specification Details
**URL**: https://webaudio.github.io/web-audio-api/#AudioWorklet
**Key Findings**:
- AudioWorkletNode processes audio in blocks matching the render quantum size
- Number of inputs is fixed at construction; number of channels can change dynamically based on computedNumberOfChannels

### Source 9: Channel Count Mode Specification
**URL**: https://webaudio.github.io/web-audio-api/#enumdef-channelcountmode
**Key Findings**:
- ChannelCountMode enum: "max", "clamped-max", "explicit"
- computedNumberOfChannels determines actual channel mixing
- Some nodes have channelCount constraints preventing changes

### Source 10: OfflineAudioContext Specification
**URL**: https://webaudio.github.io/web-audio-api/#OfflineAudioContext
**Key Findings**:
- OfflineAudioContext renders to AudioBuffer rather than device speakers
- numberOfChannels defaults to 1, can be specified in constructor
- Channel count cannot be changed after creation (InvalidStateError)

### Source 11: OscillatorNode Specification
**URL**: https://webaudio.github.io/web-audio-api/#OscillatorNode
**Key Findings**:
- type parameter: "sine", "square", "sawtooth", "triangle", "custom"
- PeriodicWave constraints: at least 8192 elements MUST be supported

### Source 12: ScriptProcessorNode (Deprecated)
**URL**: https://webaudio.github.io/web-audio-api/#ScriptProcessorNode
**Key Findings**:
- bufferSize parameter: 256, 512, 1024, 2048, 4096, 8192, 16384 (power of 2)
- Deprecated interface, retained for backward compatibility with 6-second render quantum upper limit

## Summary

The Web Audio API specification defines several artificial limitations that are hard-coded into the standard:

**Render Quantum (128 frames default)**: This is perhaps the most significant spec-mandated limitation. Audio processing happens in blocks of 128 sample frames by default, which at 44100Hz equals approximately 2.9ms of audio per processing cycle. This quantum size cannot be changed by end users, though implementations may adjust it based on hardware ("hardware" renderSizeHint). The spec mandates render quantum sizes from 1 frame up to 6 seconds of audio (6 × sampleRate frames).

**Sample Rate Bounds (3000-768000 Hz)**: The specification requires implementations to support sample rates from 3000 Hz to 768000 Hz. This is an artificial range that excludes extremely low and high frequencies that some audio hardware might support.

**IIR Filter Coefficient Cap (20)**: Both feedforward and feedback coefficient arrays for IIRFilterNode are capped at 20 elements. This limits the complexity of achievable filters and is a historical design decision.

**PeriodicWave Minimum (8192 samples)**: The spec mandates that conforming implementations support at least 8192 elements in a PeriodicWave, but doesn't specify an upper bound.

**AudioParam Float Range**: Parameters are constrained to single-precision float values with minimum/maximum values of approximately ±3.4×10^38. This excludes the use of double-precision or extended precision values in audio processing.

**Stereo Panning (2-channel max)**: StereoPannerNode is architecturally limited to exactly 2 channels despite multi-channel audio being possible elsewhere in the API.

**Delay Time Limit (180 seconds)**: DelayNode maximum delay is capped at less than 3 minutes, regardless of whether longer delays might be useful for echo or reverb effects.

These limitations represent design decisions that trade flexibility for compatibility and predictable behavior across implementations. They are specification-mandated artificial constraints rather than browser engine limitations.