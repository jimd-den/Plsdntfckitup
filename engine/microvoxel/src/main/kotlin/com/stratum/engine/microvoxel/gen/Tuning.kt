package com.stratum.engine.microvoxel.gen

/**
 * One knob a stage offers for live editing.
 *
 * Stages describe their options as data so that anything which edits a world
 * -- the in-game World panel, an AI world-builder, a test -- can offer them
 * without knowing the stage: a third-party stage that describes itself gets
 * sliders for free. Values travel as strings, like every stage option, so
 * what the panel writes is exactly what a pack's JSON would say.
 */
sealed class StageParam(val key: String, val label: String, val help: String) {
    abstract val default: String

    /** A number in [min]..[max]; [step] 0 is continuous, otherwise values snap to it (1 for whole numbers). */
    class Number(
        key: String, label: String, help: String,
        val min: Float, val max: Float, private val initial: Float, val step: Float = 0f,
    ) : StageParam(key, label, help) {
        override val default: String get() = format(initial)
        fun format(v: Float): String {
            val snapped = if (step > 0f) (Math.round((v - min) / step) * step + min) else v
            val clamped = snapped.coerceIn(min, max)
            return if (step >= 1f && step % 1f == 0f) clamped.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, clamped)
        }
    }

    /** One of [options]. */
    class Choice(key: String, label: String, help: String, val options: List<String>, override val default: String) : StageParam(key, label, help) {
        init { require(default in options) { "default '$default' is not one of $options" } }
    }

    /** On or off. */
    class Toggle(key: String, label: String, help: String, private val initial: Boolean) : StageParam(key, label, help) {
        override val default: String get() = initial.toString()
    }
}

/** What a stage is, for a person choosing whether to run it and how. */
data class StageInfo(
    val id: String,
    val title: String,
    val summary: String,
    val params: List<StageParam> = emptyList(),
)

/** A stage factory that can describe itself; the built-in ones all do. Optional for third parties. */
interface Describable {
    fun describe(): StageInfo
}
