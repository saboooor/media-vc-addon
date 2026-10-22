package ca.saboor.discs

/** Feed one original channel to both encoder inputs, so a positional speaker stays mono. */
object SpeakerChannels {
    fun isolate(input: Array<FloatArray>, offset: Int, length: Int, channel: Int) {
        if (input.size < 2) return
        val selected = input[channel.coerceIn(0, input.lastIndex)]
        input.forEach { samples ->
            if (samples !== selected) selected.copyInto(samples, offset, offset, offset + length)
        }
    }
}
