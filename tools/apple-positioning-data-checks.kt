package app.grapheneos.networklocation

import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.networklocation.proto.AppleWpsProtos.WirelessAP
import app.grapheneos.networklocation.proto.AppleWpsProtos.Nr5GCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.LteCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.ScdmaCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.GsmCellTower

fun main() {
    for (present in listOf(false, true)) {
        val zero = ALSLocation.newBuilder().setLatitude(0).setLongitude(0).setAccuracy(0).build()
        val encoded = ALSLocationResponse.newBuilder()
            .addWirelessAps(WirelessAP.newBuilder().setMacId("02:00:00:00:00:01").apply { if (present) setLocation(zero) })
            .addNr5GCellTowers(Nr5GCellTower.newBuilder().apply { if (present) setLocation(zero) })
            .addLteCellTowers(LteCellTower.newBuilder().apply { if (present) setLocation(zero) })
            .addScdmaCellTowers(ScdmaCellTower.newBuilder().apply { if (present) setLocation(zero) })
            .addGsmCellTowers(GsmCellTower.newBuilder().apply { if (present) setLocation(zero) })
            .build().toByteArray()
        val response = ALSLocationResponse.parseFrom(encoded)
        val ap = response.getWirelessAps(0)
        val nr = response.getNr5GCellTowers(0)
        val lte = response.getLteCellTowers(0)
        val wcdma = response.getScdmaCellTowers(0)
        val gsm = response.getGsmCellTowers(0)
        val converted = listOf(
            convertAppleWifiPositioningData(ap.location.takeIf { ap.hasLocation() }),
            convertAppleCellPositioningData(nr.location.takeIf { nr.hasLocation() }),
            convertAppleCellPositioningData(lte.location.takeIf { lte.hasLocation() }),
            convertAppleCellPositioningData(wcdma.location.takeIf { wcdma.hasLocation() }),
            convertAppleCellPositioningData(gsm.location.takeIf { gsm.hasLocation() }),
        )
        for (location in converted) {
            check((location != null) == present)
            if (present) check(location!!.latitude == 0.0 && location.longitude == 0.0)
        }
    }
    val base = ALSLocation.newBuilder().setLatitude(3_400_000_000L)
        .setLongitude(-11_800_000_000L).setAccuracy(46)
    fun decode(builder: ALSLocation.Builder): ALSLocation =
        ALSLocation.parseFrom(builder.build().toByteArray())
    // Optional-field presence must survive the actual protobuf round trip.
    for (confidence in listOf<Int?>(null, 0, 68, 99, -1, 300)) {
        val builder = base.clone()
        confidence?.let { builder.setConfidence(it) }
        val message = decode(builder)
        for (convert in listOf(::convertAppleWifiPositioningData, ::convertAppleCellPositioningData)) {
            val result = convert(message)!!
            check(result.rawConfidence == confidence)
            check(result.accuracyMeters == 46) // confidence never rescales the raw value
            check(result.latitude == 34.0 && result.longitude == -118.0)
            check(result.altitudeMeters == null && result.verticalAccuracyMeters == null)
        }
    }
    val zero = convertAppleWifiPositioningData(decode(base.clone().setAltitude(0).setVerticalAccuracy(0)))!!
    check(zero.altitudeMeters == 0 && zero.verticalAccuracyMeters == 0)
    val known = convertAppleWifiPositioningData(decode(base.clone().setAltitude(12345).setVerticalAccuracy(250)))!!
    check(known.altitudeMeters == 123 && known.verticalAccuracyMeters == 2)
    check(known.altitudeReference == AltitudeReference.MEAN_SEA_LEVEL)
    check(known.meanSeaLevelAltitudeMeters == 123)
    val unspecified = PositioningData(34.0, -118.0, 46, 123, 2)
    check(unspecified.meanSeaLevelAltitudeMeters == null)
    val missingVertical = convertAppleWifiPositioningData(decode(base.clone().setAltitude(12345)))!!
    check(missingVertical.altitudeMeters == 123 && missingVertical.verticalAccuracyMeters == null)
    for (sentinel in listOf(-100, -50000)) {
        val result = convertAppleWifiPositioningData(decode(base.clone().setAltitude(sentinel).setVerticalAccuracy(250)))!!
        check(result.altitudeMeters == null && result.verticalAccuracyMeters == null)
    }
    check(convertAppleWifiPositioningData(decode(base.clone().setAltitude(12345).setVerticalAccuracy(-100)))!!.verticalAccuracyMeters == null)
    for (invalid in listOf(-1, -50, -101)) {
        check(convertAppleWifiPositioningData(decode(base.clone().setAltitude(12345)
            .setVerticalAccuracy(invalid)))!!.verticalAccuracyMeters == null)
    }
    val invalid = decode(base.clone().setLatitude(-18000000000))
    check(convertAppleWifiPositioningData(invalid) == null && convertAppleCellPositioningData(invalid) == null)
    for (builder in listOf(
        base.clone().setLatitude(9_000_000_001L),
        base.clone().setLatitude(-9_000_000_001L),
        base.clone().setLatitude(Long.MAX_VALUE),
        base.clone().setLongitude(18_000_000_001L),
        base.clone().setLongitude(-18_000_000_001L),
        base.clone().setLongitude(Long.MIN_VALUE),
        base.clone().setAccuracy(-1),
        base.clone().setAccuracy(Int.MIN_VALUE),
    )) {
        val message = decode(builder)
        check(convertAppleWifiPositioningData(message) == null)
        check(convertAppleCellPositioningData(message) == null)
    }
    // Zero and boundary coordinates remain valid; no arbitrary accuracy ceiling.
    for (latitude in listOf(-9_000_000_000L, 0L, 9_000_000_000L)) {
        for (longitude in listOf(-18_000_000_000L, 0L, 18_000_000_000L)) {
            for (accuracy in listOf(0, 25, Int.MAX_VALUE)) {
                val message = decode(base.clone().setLatitude(latitude)
                    .setLongitude(longitude).setAccuracy(accuracy))
                for (convert in listOf(::convertAppleWifiPositioningData, ::convertAppleCellPositioningData)) {
                    val result = checkNotNull(convert(message))
                    check(result.latitude == latitude * 1e-8 && result.longitude == longitude * 1e-8)
                    check(result.accuracyMeters == accuracy)
                }
            }
        }
    }
    println("Apple decoder checks passed: absent versus zero Wi-Fi/cell location, coordinate/scale bounds, raw scale, optional confidence, absent versus zero altitude/uncertainty, unit scale, sentinels")
}
