package app.grapheneos.networklocation.cell

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest.ALSMeta
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.networklocation.proto.AppleWpsProtos.GsmCellTower
import app.grapheneos.networklocation.proto.AppleWpsProtos.LteCellTower
import app.grapheneos.networklocation.verboseLog
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.grapheneos.tls.ModernTLSSocketFactory

private const val TAG = "AppleCps"
private const val EXTRA_VERBOSE_TAG = "AppleCpsVV"

private const val MAX_REQUEST_TOWERS = 12
private const val THROTTLED_ADDITIONAL_RESULTS = 4
private const val MAX_ADDITIONAL_RESULTS = 25
private val THROTTLE_COOLDOWN = 10.seconds
private const val THROTTLE_TRIGGER_RESULT_COUNT = 10

class AppleCellPositioningService : CellPositioningService {

    private val tlsSocketFactory = ModernTLSSocketFactory()

    private var throttleTriggeredElapsedRealtime = -THROTTLE_COOLDOWN

    @Throws(IOException::class)
    override fun fetchNearbyTowerPositioningData(identifiers: List<Identifier>): List<CellTowerPositioningData> {
        val requestIdentifiers = identifiers.take(MAX_REQUEST_TOWERS)
        val result = HashMap<Identifier, PositioningData?>()

        val response = fetchInner(
            requestIdentifiers,
            if (SystemClock.elapsedRealtime().milliseconds - throttleTriggeredElapsedRealtime >= THROTTLE_COOLDOWN) {
                MAX_ADDITIONAL_RESULTS
            } else {
                THROTTLED_ADDITIONAL_RESULTS
            }
        )

        val fullTowerCount =
            response.nr5GCellTowersCount + response.lteCellTowersCount + response.scdmaCellTowersCount +
                    response.cdmaCellTowersCount + response.gsmCellTowersCount

        if (fullTowerCount >= THROTTLE_TRIGGER_RESULT_COUNT) {
            verboseLog(TAG) { "response tower count ($fullTowerCount) triggered throttle" }
            throttleTriggeredElapsedRealtime = SystemClock.elapsedRealtime().milliseconds
        }

        // TODO: support NR (5G)
        for (tower in response.lteCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    technology = Technology.LTE,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.tacId,
                    cellId = tower.cellId,
                ),
                convertPositioningData(tower.location)
            )
        }
        for (tower in response.gsmCellTowersList) {
            result.putIfAbsent(
                Identifier(
                    technology = Technology.GSM,
                    mcc = tower.mcc,
                    mnc = tower.mnc,
                    lac = tower.lacId,
                    cellId = tower.cellId,
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
        identifiers: List<Identifier>,
        maxAdditionalResults: Int
    ): ALSLocationResponse {
        val (url, enforceModernTls) = getServerUrl()

        verboseLog(TAG) { "request identifiers: $identifiers" }

        val connection = url.openConnection() as HttpsURLConnection
        try {
            if (enforceModernTls) {
                connection.sslSocketFactory = tlsSocketFactory
            }
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty(
                "User-Agent",
                "locationd/2960.0.57 CFNetwork/3826.500.111.1.1 Darwin/24.4.0"
            )
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true

            DataOutputStream(connection.outputStream).use { outputStream ->
                val locale = "en-US_US".toByteArray()
                val identifier = "com.apple.locationd".toByteArray()
                val version = "15.4.24E248".toByteArray()
                val requestCode = 1

                outputStream.writeShort(1) // hardcoded
                outputStream.writeShort(locale.size)
                outputStream.write(locale)
                outputStream.writeShort(identifier.size)
                outputStream.write(identifier)
                outputStream.writeShort(version.size)
                outputStream.write(version)
                outputStream.writeInt(requestCode)

                val protobufData = ALSLocationRequest.newBuilder().run {
                    val identifiersLte = identifiers.filter { it.technology == Technology.LTE }
                    val identifiersGsm = identifiers.filter { it.technology == Technology.GSM }

                    addAllLteCellTowers(identifiersLte.map {
                        LteCellTower.newBuilder()
                            .setMcc(it.mcc)
                            .setMnc(it.mnc)
                            .setTacId(it.lac)
                            .setCellId(it.cellId)
                            .build()
                    })
                    addAllGsmCellTowers(identifiersGsm.map {
                        GsmCellTower.newBuilder()
                            .setMcc(it.mcc)
                            .setMnc(it.mnc)
                            .setLacId(it.lac)
                            .setCellId(it.cellId)
                            .build()
                    })


                    val totalRequestedTowersCount = identifiersLte.size + identifiersGsm.size

                    // should be at least 1 for each technology we request, otherwise it defaults to around 400
                    if (identifiersLte.isNotEmpty()) {
                        setNumberOfSurroundingLteCells(
                            max(
                                1,
                                maxAdditionalResults * (identifiersLte.size / totalRequestedTowersCount)
                            )
                        )
                    }
                    if (identifiersGsm.isNotEmpty()) {
                        setNumberOfSurroundingGsmCells(
                            max(
                                1,
                                maxAdditionalResults * (identifiersGsm.size / totalRequestedTowersCount)
                            )
                        )
                    }

                    setMeta(
                        ALSMeta.newBuilder()
                            .setSoftwareBuild("macOS15.4/24E248")
                            .setProductId("arm64")
                            .build()
                    )

                    build()
                }

                outputStream.writeInt(protobufData.serializedSize)
                protobufData.writeTo(outputStream)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("non-200 response code: $responseCode")
            }
            val ignoredHeaderSize = 10
            val protoBytes: ByteArray = connection.inputStream.use { inputStream ->
                inputStream.skipNBytes(ignoredHeaderSize.toLong())
                inputStream.readAllBytes()
            }
            val response = ALSLocationResponse.parseFrom(protoBytes)
            verboseLog(TAG) {
                "response tower list size: ${
                    response.nr5GCellTowersCount + response.lteCellTowersCount +
                            response.scdmaCellTowersCount + response.cdmaCellTowersCount + response.gsmCellTowersCount
                }, " +
                        "byte size: ${protoBytes.size + ignoredHeaderSize}"
            }
            if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
                Log.v(EXTRA_VERBOSE_TAG, "response headers: " + connection.headerFields)
                response.nr5GCellTowersList.forEachIndexed { i, tower ->
                    Log.v(
                        EXTRA_VERBOSE_TAG, "response[$i]: cell ID: ${tower.cellId}, " +
                                "positioning data: ${convertPositioningData(tower.location)}"
                    )
                }
                response.lteCellTowersList.forEachIndexed { i, tower ->
                    Log.v(
                        EXTRA_VERBOSE_TAG, "response[$i]: cell ID: ${tower.cellId}, " +
                                "positioning data: ${convertPositioningData(tower.location)}"
                    )
                }
                response.scdmaCellTowersList.forEachIndexed { i, tower ->
                    Log.v(
                        EXTRA_VERBOSE_TAG, "response[$i]: cell ID: ${tower.cellId}, " +
                                "positioning data: ${convertPositioningData(tower.location)}"
                    )
                }
                response.cdmaCellTowersList.forEachIndexed { i, tower ->
                    Log.v(
                        EXTRA_VERBOSE_TAG, "response[$i]: BSID: ${tower.bsid}, " +
                                "positioning data: ${convertPositioningData(tower.location)}"
                    )
                }
                response.gsmCellTowersList.forEachIndexed { i, tower ->
                    Log.v(
                        EXTRA_VERBOSE_TAG, "response[$i]: cell ID: ${tower.cellId}, " +
                                "positioning data: ${convertPositioningData(tower.location)}"
                    )
                }
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun convertPositioningData(pd: ALSLocation): PositioningData? {
        if (pd.latitude == -18000000000) {
            return null
        }
        val latitude = pd.latitude * 0.000_000_01;
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

    @Throws(IOException::class)
    private fun getServerUrl(): Pair<URL, Boolean> {
        val context: Context = AppGlobals.getInitialApplication()
        val setting = NETWORK_LOCATION_SETTING.get(context)
        return when (setting) {
            NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY ->
                Pair(URL("https://gs-loc.apple.grapheneos.org/clls/wloc"), true)

            NETWORK_LOCATION_SERVER_APPLE ->
                Pair(URL("https://gs-loc.apple.com/clls/wloc"), false)

            NETWORK_LOCATION_DISABLED ->
                // network location can be disabled by the user at any point
                throw IOException("network location setting became disabled")

            else ->
                throw IllegalStateException("unexpected URL setting: $setting")
        }
    }
}
