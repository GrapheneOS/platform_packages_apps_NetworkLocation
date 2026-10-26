package app.grapheneos.networklocation

import app.grapheneos.networklocation.ApplePositioningServiceLimits.BASELINE_ADDITIONAL_TOWERS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.MAX_ADDITIONAL_APS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.MAX_ADDITIONAL_TOWERS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.MAX_REQUEST_APS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.MAX_REQUEST_TOWERS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.MAX_RESPONSE_BYTES
import app.grapheneos.networklocation.ApplePositioningServiceLimits.RESPONSE_HEADER_BYTES
import app.grapheneos.networklocation.ApplePositioningServiceLimits.THROTTLED_ADDITIONAL_APS
import app.grapheneos.networklocation.ApplePositioningServiceLimits.THROTTLED_ADDITIONAL_TOWERS
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.readBoundedBytes
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.DynamicMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// These bounds cover the known schema under the requested-count model. They do
// not establish Apple's server-enforced counts or bound unknown protobuf fields.
class ApplePositioningResponseSizeTest {
    private val responseDescriptor = ALSLocationResponse.getDescriptor()

    @Test
    fun maximumKnownWifiResponseFitsBudget() {
        // Allow the requested surrounding count per input AP, plus the AP itself.
        // This is deliberately more than a shared surrounding-results budget.
        val surrounding = maxOf(1, MAX_ADDITIONAL_APS, THROTTLED_ADDITIONAL_APS)
        val count = Math.multiplyExact(MAX_REQUEST_APS, Math.addExact(surrounding, 1))
        assertResponseFits("wireless_aps", count)
    }

    @Test
    fun maximumKnownCellularResponsesFitBudget() {
        // Each requested RAT gets at least one surrounding result before the
        // server's baseline results, including when the caller requests fewer.
        val surrounding = maxOf(
            MAX_ADDITIONAL_TOWERS,
            THROTTLED_ADDITIONAL_TOWERS,
            Math.addExact(BASELINE_ADDITIONAL_TOWERS, 1)
        )
        val count = Math.multiplyExact(MAX_REQUEST_TOWERS, Math.addExact(surrounding, 1))
        // All allocations of these records between supported RATs are bounded by
        // the largest record, so check the full count for each supported RAT.
        for (field in listOf("gsm_cell_towers", "lte_cell_towers", "scdma_cell_towers", "nr5g_cell_towers")) {
            assertResponseFits(field, count)
        }
    }

    @Test
    fun newResponseFieldsRequireReview() {
        assertEquals(
            setOf("gsm_cell_towers", "wireless_aps", "cdma_cell_towers", "lte_cell_towers", "scdma_cell_towers", "nr5g_cell_towers"),
            responseDescriptor.fields.map { it.name }.toSet()
        )
    }

    private fun assertResponseFits(fieldName: String, count: Int) {
        val field = responseDescriptor.findFieldByName(fieldName)
        val record = maximumKnownMessage(field.messageType)
        val response = DynamicMessage.newBuilder(responseDescriptor).apply {
            repeat(count) { addRepeatedField(field, record) }
        }.build()
        val body = response.toByteArray()
        val totalSize = Math.addExact(RESPONSE_HEADER_BYTES, body.size)
        assertTrue("$fieldName: $totalSize bytes exceeds $MAX_RESPONSE_BYTES", totalSize <= MAX_RESPONSE_BYTES)

        // Exercise the stream helper with framing accounted for as in production.
        (ByteArray(RESPONSE_HEADER_BYTES) + body).inputStream().use { input ->
            input.skipNBytes(RESPONSE_HEADER_BYTES.toLong())
            assertArrayEquals(body, input.readBoundedBytes(MAX_RESPONSE_BYTES - RESPONSE_HEADER_BYTES))
        }
    }

    private fun maximumKnownMessage(
        descriptor: Descriptor,
        ancestors: Set<Descriptor> = emptySet()
    ): DynamicMessage {
        check(descriptor !in ancestors) { "A recursion bound is needed for ${descriptor.fullName}" }
        return DynamicMessage.newBuilder(descriptor).apply {
            for (field in descriptor.fields) {
                check(!field.isRepeated) { "A count bound is needed for ${field.fullName}" }
                val value: Any = when (field.type) {
                    // Negative signed values use ten-byte varints; -1 represents
                    // the full five-byte unsigned 32-bit value for UINT32.
                    FieldDescriptor.Type.INT32, FieldDescriptor.Type.UINT32 -> -1
                    FieldDescriptor.Type.INT64 -> -1L
                    FieldDescriptor.Type.MESSAGE -> maximumKnownMessage(field.messageType, ancestors + descriptor)
                    FieldDescriptor.Type.STRING -> {
                        check(field.fullName == "app.grapheneos.networklocation.proto.WirelessAP.mac_id") {
                            "A string-size bound is needed for ${field.fullName}"
                        }
                        "ff:ff:ff:ff:ff:ff"
                    }
                    else -> error("A size bound is needed for ${field.fullName}")
                }
                setField(field, value)
            }
        }.build()
    }
}
