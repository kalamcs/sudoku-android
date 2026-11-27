// Copyright (c) 2026 Kalam Shah
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.kalamcs.sudoku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardViewportTest {
    @Test fun pinchKeepsTheSameCellUnderTheFingers() {
        val viewport = BoardViewport()
        assertEquals(21, viewport.cellAt(340f, 230f, 900f))
        viewport.zoom(2f, 340f, 230f, 900f)
        assertEquals(2f, viewport.scale)
        assertEquals(21, viewport.cellAt(340f, 230f, 900f))
        assertEquals(20, viewport.cellAt(240f, 230f, 900f))
    }

    @Test fun panAndZoomStayInsideTheBoard() {
        val viewport = BoardViewport()
        viewport.zoom(5f, 450f, 450f, 900f)
        assertEquals(3f, viewport.scale)
        viewport.pan(-9_000f, -9_000f, 900f)
        assertEquals(-1800f, viewport.left)
        assertEquals(-1800f, viewport.top)
        assertEquals(80, viewport.cellAt(899f, 899f, 900f))
        assertNull(viewport.cellAt(900f, 900f, 900f))
        viewport.zoom(.1f, 800f, 800f, 900f)
        assertEquals(1f, viewport.scale)
        assertEquals(0f, viewport.left)
        assertEquals(0f, viewport.top)
        viewport.reset()
        assertEquals(0, viewport.cellAt(0f, 0f, 900f))
    }
}
