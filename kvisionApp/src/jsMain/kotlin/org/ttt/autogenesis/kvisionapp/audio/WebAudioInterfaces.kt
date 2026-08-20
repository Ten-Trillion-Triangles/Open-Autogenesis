package org.ttt.autogenesis.kvisionapp.audio

import kotlin.js.Promise

/**
 * Web Audio API types via external interface.
 * These declarations enable type-safe interop with the browser's Web Audio API.
 */
@JsName("AudioContext")
external interface AudioContext {
    val destination: AudioNode
    val currentTime: Double
    val sampleRate: Int
    fun createGain(): GainNode
    fun createStereoPanner(): StereoPannerNode
    fun createBufferSource(): AudioBufferSourceNode
    fun createAnalyser(): AnalyserNode
    fun decodeAudioData(data: Any): Promise<AudioBuffer>
    val state: String
    suspend fun resume()
}

@JsName("GainNode")
external interface GainNode {
    val gain: AudioParam
    fun connect(dest: AudioNode)
    fun disconnect()
}

@JsName("AudioNode")
external interface AudioNode {
    fun connect(dest: AudioNode)
    fun disconnect()
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

@JsName("AnalyserNode")
external interface AnalyserNode : AudioNode {
    val fftSize: Int
    var smoothingTimeConstant: Float
    fun getFloatTimeDomainData(output: FloatArray)
}

@JsName("AudioData")
external interface AudioData {
    val source: AudioBufferSourceNode
}