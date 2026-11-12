package dev.mahali.hatch.feature.terminal

// Old IME and key-repeat callbacks keep the handle of the generation they came from.
internal class InputBinding<T> internal constructor(
    val destination: T,
    private val ready: Boolean,
    private val current: (InputBinding<T>) -> Boolean,
    private val release: (InputBinding<T>) -> Unit,
) : AutoCloseable {
    private var closed = false
    val acceptsInput: Boolean get() = !closed && ready && current(this)
    override fun close() {
        if (closed) return
        closed = true
        release(this)
    }
}

internal class InputBindings<T>(private val changed: (InputBinding<T>?) -> Unit = {}) {
    private var current: InputBinding<T>? = null
    fun bind(destination: T, ready: Boolean): InputBinding<T> {
        current?.close()
        return InputBinding(destination, ready, { current === it }, { if (current === it) { current = null; changed(null) } })
            .also { current = it; changed(it) }
    }
}
