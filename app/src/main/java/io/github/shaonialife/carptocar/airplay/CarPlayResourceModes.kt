package io.github.shaonialife.carptocar.airplay

/** Tracks ownership of the main screen and main audio independently within one session. */
internal data class CarPlayResourceModes(
    val mainScreen: Ownership = Ownership(),
    val mainAudio: Ownership = Ownership(),
) {
    data class Ownership(
        private val carTook: Boolean = false,
        private val carBorrowed: Boolean = false,
    ) {
        val carOwns: Boolean get() = carTook || carBorrowed

        fun report(resourceId: Long): Map<String, Long> = mapOf(
            "resourceID" to resourceId,
            "entity" to if (carOwns) 2L else 1L,
            "permanentEntity" to if (carTook) 2L else 1L,
        )

        fun transfer(type: Long?): Ownership = when (type) {
            1L -> copy(carTook = true) // Take
            2L -> copy(carTook = false) // Untake
            3L -> copy(carBorrowed = true) // Borrow
            4L -> copy(carBorrowed = false) // Unborrow
            else -> this
        }
    }

    fun report(): List<Map<String, Long>> = listOf(mainScreen.report(MAIN_SCREEN), mainAudio.report(MAIN_AUDIO))

    fun applyRequest(resources: List<*>): CarPlayResourceModes {
        var screen = mainScreen
        var audio = mainAudio
        for (entry in resources) {
            val resource = entry as? Map<*, *> ?: continue
            val transfer = (resource["transferType"] as? Number)?.toLong()
            when ((resource["resourceID"] as? Number)?.toLong()) {
                MAIN_SCREEN -> screen = screen.transfer(transfer)
                MAIN_AUDIO -> audio = audio.transfer(transfer)
            }
        }
        return CarPlayResourceModes(screen, audio)
    }

    private companion object {
        const val MAIN_SCREEN = 1L
        const val MAIN_AUDIO = 2L
    }
}
