package moe.chenxy.huaweipods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuaweiWindNoiseReductionControllerTest {
    @Test
    fun `packets match FreeBuds SE 4 ANC capture`() {
        assertPacket("5A0008002BB401011D0200594B", HuaweiWindNoiseReductionController.queryPacket())
        assertPacket("5A0009002BB401011D020100DCA8", HuaweiWindNoiseReductionController.setPacket(false))
        assertPacket("5A0009002BB401011D020101CC89", HuaweiWindNoiseReductionController.setPacket(true))
    }

    @Test
    fun `state parser accepts captured off and on responses`() {
        assertEquals(false, HuaweiWindNoiseReductionController.parseState(hex("5A0009002BB401011D020100DCA8")))
        assertEquals(true, HuaweiWindNoiseReductionController.parseState(hex("5A0009002BB401011D020101CC89")))
    }

    @Test
    fun `state parser returns latest valid state from a stream`() {
        val unrelated = hex("5A0006002B040201003171")
        val off = hex("5A0009002BB401011D020100DCA8")
        val on = hex("5A0009002BB401011D020101CC89")
        assertEquals(true, HuaweiWindNoiseReductionController.parseState(unrelated + off + on))
    }

    @Test
    fun `state parser rejects corrupt and unrelated frames`() {
        val corrupt = hex("5A0009002BB401011D020101CC00")
        val otherFeature = hex("5A0009002BB401010B020101F0B7")
        assertNull(HuaweiWindNoiseReductionController.parseState(corrupt + otherFeature))
    }

    private fun assertPacket(expected: String, actual: ByteArray) {
        assertArrayEquals(hex(expected), actual)
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
