package io.github.juwanhwang.autoqqpet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 应用内协议（assets/eula.md）不能带维护说明或任何 HTML 注释；版权人是 Juwan Hwang。 */
class EulaAssetTest {
    private val text: String = listOf("src/main/assets/eula.md", "app/src/main/assets/eula.md").map(::File).first { it.isFile }.readText()

    @Test fun noMaintainerNotesOrComments() {
        assertFalse(text.contains("<!--"))
        assertFalse(text.contains("-->"))
        assertFalse(text.contains("maintainer"))
        assertFalse(text.contains("sync_eula"))
        assertTrue(text.startsWith("# "))
        assertTrue(text.contains("Juwan Hwang"))
    }
}
