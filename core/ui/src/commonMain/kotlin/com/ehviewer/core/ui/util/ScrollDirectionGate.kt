package com.ehviewer.core.ui.util

class ScrollDirectionGate(private val hideDistance: Float, private val showDistance: Float) {
    private var distance = 0f

    fun update(delta: Float): Boolean? {
        if (delta == 0f) return null
        if (delta * distance < 0f) distance = 0f
        distance += delta
        return when {
            distance >= hideDistance -> true.also { distance = 0f }
            distance <= -showDistance -> false.also { distance = 0f }
            else -> null
        }
    }
}
