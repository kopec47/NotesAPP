package com.example.notes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val shakeThreshold: Float = 1.8f, // próg w m/s^2
    private val cooldown: Long = 1000L,       // 1 sekunda między wstrząsami
    private val onShake: () -> Unit
) : SensorEventListener {

    private var lastShakeTime = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((gX*gX + gY*gY + gZ*gZ).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        if (gForce > shakeThreshold && now - lastShakeTime > cooldown) {
            lastShakeTime = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
