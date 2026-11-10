package com.chen3feng.beautycam

/** All strengths are 0..1 unless noted. */
data class BeautySettings(
    val enabled: Boolean = true,
    val smooth: Float = 0.50f,
    val whiten: Float = 1.00f,
    val slim: Float = 0.00f,
    val eyes: Float = 0.50f,
    val warm: Float = 1.00f,
    val lips: Float = 0.35f
)
