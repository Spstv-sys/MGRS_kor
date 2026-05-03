package com.example.mgrskor.map

import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

/**
 * Розширення стандартного [RotationGestureOverlay], яке після кожного жесту
 * повороту повідомляє слухача про поточну орієнтацію карти.
 *
 * Використовується для синхронізації UI-індикатора (стрілки компаса) з
 * фактичним кутом повороту [MapView.getMapOrientation].
 */
class MapRotationOverlay(
    private val map: MapView,
    private val onOrientationChanged: (Float) -> Unit
) : RotationGestureOverlay(map) {

    override fun onRotate(deltaAngle: Float) {
        super.onRotate(deltaAngle)
        onOrientationChanged(map.mapOrientation)
    }
}
