package audio.highLevel

/**
 * Defines what audio channel a sound should play in. Each audio channel allows for independent global
 * controls for volume and playback,
 */
enum class AudioChannel
{
    Sfx,
    Vfx,
    Rhythm,
    Drone,
    Melody,
    Harmony
}

/**
 * High level interface, control system, and definition for a sound that plays in Autogenesis.
 */
open class AudioObject
{
    /**
     * Unique name for an audio object. This name will be used to attempt to resolve to the actual audio file
     * in the web app by finding the best match by file path. That will bind the actual audio file when playing
     * in the game client.
     */
    var name = ""

    /**
     * Defines what channel to play the sound in.
     * @see AudioChannel
     */
    var channel: AudioChannel = AudioChannel.Sfx

    /**
     * Defines volume level between 0% and 100% relative to the global volume level of the channel, which itself
     * is relative to the global volume settings in the game.
     */
    var volume: Double = 1.0

    /**
     * Defines stereo panning. -1.0 is left ear, 1.0 is right.
     */
    var panning: Double = 0.0
}