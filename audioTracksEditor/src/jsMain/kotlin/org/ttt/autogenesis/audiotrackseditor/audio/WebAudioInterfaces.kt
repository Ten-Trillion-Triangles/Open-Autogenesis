package org.ttt.autogenesis.audiotrackseditor.audio

import kotlin.js.Promise

/**
 * Web Audio API type declarations for the audio tracks editor preview.
 *
 * These `external interface` declarations enable type-safe interop with the
 * browser's Web Audio API from Kotlin/JS. The shape mirrors the established
 * kvisionApp/.../audio/WebAudioInterfaces.kt declarations (the editor is a
 * standalone module and cannot import that scope directly).
 */
@JsName("AudioContext")
external interface AudioContext {
    val destination: AudioNode
    val currentTime: Double
    val sampleRate: Int
    val state: String
    fun createGain(): GainNode
    fun createStereoPanner(): StereoPannerNode
    fun createBufferSource(): AudioBufferSourceNode
    fun decodeAudioData(data: Any): Promise<AudioBuffer>
    fun resume(): Promise<Unit>
}

@JsName("AudioNode")
external interface AudioNode {
    fun connect(dest: AudioNode)
    fun disconnect()
}

@JsName("GainNode")
external interface GainNode : AudioNode {
    val gain: AudioParam
}

@JsName("StereoPannerNode")
external interface StereoPannerNode : AudioNode {
    val pan: AudioParam
}

@JsName("AudioParam")
external interface AudioParam {
    var value: Float
    fun setValueAtTime(value: Float, startTime: Double)
    fun linearRampToValueAtTime(value: Float, endTime: Double)
    fun setTargetAtTime(value: Float, startTime: Double, timeConstant: Double)
    fun cancelScheduledValues(startTime: Double)
}

@JsName("AudioBufferSourceNode")
external interface AudioBufferSourceNode {
    var buffer: AudioBuffer?
    val playbackRate: AudioParam
    var loop: Boolean
    var loopStart: Double
    var loopEnd: Double
    fun start(whenTime: Double = definedExternally, offset: Double = definedExternally, duration: Double = definedExternally)
    fun stop(whenTime: Double = definedExternally)
    fun connect(dest: AudioNode)
    fun disconnect()
}

@JsName("AudioBuffer")
external interface AudioBuffer {
    val duration: Double
    val length: Int
    val numberOfChannels: Int
    fun getChannelData(channel: Int): FloatArray
}
