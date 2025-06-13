package app.grapheneos.networklocation.cell

import android.util.Log
import app.grapheneos.networklocation.ApplePositioningService
import app.grapheneos.networklocation.PositioningData
import app.grapheneos.networklocation.Throttle
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.networklocation.proto.AppleWpsProtos.GsmCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.LteCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.Nr5GCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.ScdmaCellTower
import app.grapheneos.networklocation.verboseLog
import java.io.IOException
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AppleCps"
private const val EXTRA_VERBOSE_TAG = "AppleCpsVV"

private const val MAX_REQUEST_TOWERS = 12
private const val THROTTLED_ADDITIONAL_RESULTS = 4
private const val MAX_ADDITIONAL_RESULTS = 25
private const val THROTTLE_TRIGGER_RESULT_COUNT = 10

class AppleCellPositioningService : CellPositioningService {
    private val applePs = ApplePositioningService()

    private var throttle = Throttle(10.seconds)

    @Throws(IOException::class)
    override fun fetchNearbyTowerPositioningData(identifiers: List<Identifier>): List<CellTowerPositioningData> {
        val requestIdentifiers = identifiers.take(MAX_REQUEST_TOWERS)
        val result = HashMap<Identifier, PositioningData?>()

        val response = fetchInner(
            requestIdentifiers, if (throttle.isThrottled()) {
                THROTTLED_ADDITIONAL_RESULTS
            } else {
                MAX_ADDITIONAL_RESULTS
            }
        )

        val fullTowerCount =
            response.nr5GCellTowersCount + response.lteCellTowersCount + response.scdmaCellTowersCount + response.gsmCellTowersCount
        if (fullTowerCount >= THROTTLE_TRIGGER_RESULT_COUNT) {
            verboseLog(TAG) { "response tower count ($fullTowerCount) triggered throttle" }
            throttle.trigger()
        }

        for (tower in response.nr5GCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    standard = Standard.NR,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.tacId,
                    cellId = tower.cellId,
                ),
                convertPositioningData(tower.location)
            )
        }
        for (tower in response.lteCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    standard = Standard.LTE,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.tacId,
                    cellId = tower.cellId.toLong(),
                ),
                convertPositioningData(tower.location)
            )
        }
        for (tower in response.scdmaCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    standard = Standard.WCDMA,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.lacId,
                    cellId = tower.cellId.toLong(),
                ),
                convertPositioningData(tower.location)
            )
        }
        for (tower in response.gsmCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    standard = Standard.GSM,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.lacId,
                    cellId = tower.cellId.toLong(),
                ),
                convertPositioningData(tower.location)
            )
        }
        for (identifier in requestIdentifiers) {
            result.putIfAbsent(identifier, null)
        }

        return result.map { CellTowerPositioningData(it.key, it.value) }
    }

    @Throws(IOException::class)
    private fun fetchInner(
        identifiers: List<Identifier>, maxAdditionalResults: Int
    ): ALSLocationResponse {
        verboseLog(TAG) { "request identifiers: $identifiers" }

        val request = ALSLocationRequest.newBuilder().run {
            for (id in identifiers) {
                when (id.standard) {
                    Standard.NR -> addNr5GCellTowers(
                        Nr5GCellTower.newBuilder()
                            .setMcc(id.mcc)
                            .setMnc(id.mnc)
                            .setTacId(id.lac)
                            .setCellId(id.cellId)
                            .build()
                    )

                    Standard.LTE -> addLteCellTowers(
                        LteCellTower.newBuilder()
                            .setMcc(id.mcc)
                            .setMnc(id.mnc)
                            .setTacId(id.lac)
                            .setCellId(id.cellId.toInt())
                            .build()
                    )

                    Standard.WCDMA -> addScdmaCellTowers(
                        ScdmaCellTower.newBuilder()
                            .setMcc(id.mcc)
                            .setMnc(id.mnc)
                            .setLacId(id.lac)
                            .setCellId(id.cellId.toInt())
                            .build()
                    )

                    Standard.GSM -> addGsmCellTowers(
                        GsmCellTower.newBuilder()
                            .setMcc(id.mcc)
                            .setMnc(id.mnc)
                            .setLacId(id.lac)
                            .setCellId(id.cellId.toInt())
                            .build()
                    )
                }
            }

            // Apple's service returns more additional results than we request
            val baselineMaxAdditionalResults = 19
            val adjustedMaxAdditionalResults = if (maxAdditionalResults > baselineMaxAdditionalResults) {
                maxAdditionalResults - baselineMaxAdditionalResults
            } else if (maxAdditionalResults == 0) {
                0
            } else {
                1
            }

            val totalRequestedTowersCount =
                nr5GCellTowersCount + lteCellTowersCount + scdmaCellTowersCount + gsmCellTowersCount

            // should be at least 1 for each standard of towers we request, otherwise it defaults to
            // around 120
            if (nr5GCellTowersCount != 0) {
                setNumberOfSurroundingNr5GCells(
                    max(
                        1,
                        (adjustedMaxAdditionalResults * (nr5GCellTowersCount.toDouble() /
                                totalRequestedTowersCount.toDouble())).toInt()
                    )
                )
            }
            if (lteCellTowersCount != 0) {
                setNumberOfSurroundingLteCells(
                    max(
                        1,
                        (adjustedMaxAdditionalResults * (lteCellTowersCount.toDouble() /
                                totalRequestedTowersCount.toDouble())).toInt()
                    )
                )
            }
            if (scdmaCellTowersCount != 0) {
                setNumberOfSurroundingScdmaCells(
                    max(
                        1,
                        (adjustedMaxAdditionalResults * (scdmaCellTowersCount.toDouble() /
                                totalRequestedTowersCount.toDouble())).toInt()
                    )
                )
            }
            if (gsmCellTowersCount != 0) {
                setNumberOfSurroundingGsmCells(
                    max(
                        1,
                        (adjustedMaxAdditionalResults * (gsmCellTowersCount.toDouble() /
                                totalRequestedTowersCount.toDouble())).toInt()
                    )
                )
            }

            setMeta(applePs.macosMeta)

            build()
        }

        val response = applePs.fetch(request)
        verboseLog(TAG) {
            "response tower list size: ${
                response.nr5GCellTowersCount + response.lteCellTowersCount +
                        response.scdmaCellTowersCount + response.gsmCellTowersCount
            }"
        }
        if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
            response.nr5GCellTowersList.forEachIndexed { i, tower ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "NR response[$i]: cell ID: ${tower.cellId}, " +
                            "positioning data: ${convertPositioningData(tower.location)}"
                )
            }
            response.lteCellTowersList.forEachIndexed { i, tower ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "LTE response[$i]: cell ID: ${tower.cellId}, " +
                            "positioning data: ${convertPositioningData(tower.location)}"
                )
            }
            response.scdmaCellTowersList.forEachIndexed { i, tower ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "(S/W)CDMA response[$i]: cell ID: ${tower.cellId}, " +
                            "positioning data: ${convertPositioningData(tower.location)}"
                )
            }
            response.gsmCellTowersList.forEachIndexed { i, tower ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "GSM response[$i]: cell ID: ${tower.cellId}, " +
                            "positioning data: ${convertPositioningData(tower.location)}"
                )
            }
        }
        return response
    }

    private fun convertPositioningData(pd: ALSLocation): PositioningData? {
        if (pd.latitude == -18000000000) {
            return null
        }
        val latitude = pd.latitude * 0.000_000_01
        val longitude = pd.longitude * 0.000_000_01
        // the API doesn't seem to return an altitude for cell towers (always returns 0), so we
        // just set it to null
        val altitudeMeters = null
        val verticalAccuracyMeters = null
        return PositioningData(
            latitude,
            longitude,
            pd.accuracy,
            altitudeMeters,
            verticalAccuracyMeters
        )
    }
}