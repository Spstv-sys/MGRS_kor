package com.example.mgrskor.mgrs

import mil.nga.mgrs.grid.GridType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MgrsFormatterTest {

    @Test
    fun `chooseGridType maps accuracy to grid precision`() {
        assertEquals(GridType.METER, MgrsFormatter.chooseGridType(0.5f))
        assertEquals(GridType.TEN_METER, MgrsFormatter.chooseGridType(5f))
        assertEquals(GridType.HUNDRED_METER, MgrsFormatter.chooseGridType(50f))
        assertEquals(GridType.KILOMETER, MgrsFormatter.chooseGridType(500f))
        assertEquals(GridType.METER, MgrsFormatter.chooseGridType(Float.NaN))
    }

    @Test
    fun `format kyiv center returns expected MGRS zone`() {
        // Київ ~ 50.45N 30.52E -> MGRS зона 36U
        val mgrs = MgrsFormatter.format(50.4501, 30.5234, GridType.HUNDRED_METER)
        assertTrue("очікували зону 36U, отримали: $mgrs", mgrs.startsWith("36U"))
    }

    @Test
    fun `format does not throw at extreme south`() {
        // Антарктида - перевіряємо що не падає
        val mgrs = MgrsFormatter.format(-79.9, 0.1, GridType.KILOMETER)
        assertTrue(mgrs.isNotBlank())
    }

    @Test
    fun `swapping lat lon yields different result - safety check`() {
        // Якщо випадково передати lat/lon у неправильному порядку — результат відрізняється.
        // Цей тест зафіксує контракт обгортки: format(lat, lon, ...).
        val correct = MgrsFormatter.format(50.0, 30.0, GridType.KILOMETER)
        val swapped = MgrsFormatter.format(30.0, 50.0, GridType.KILOMETER)
        assertTrue("обмін lat/lon має давати інший MGRS", correct != swapped)
    }
}
