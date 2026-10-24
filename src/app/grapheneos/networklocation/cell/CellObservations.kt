package app.grapheneos.networklocation.cell

import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo

/** Keep identifiable observations with valid boot-clock times, newest per cell. */
internal fun selectCellObservations(
    cellInfos: List<CellInfo>,
    elapsedRealtimeMillis: Long,
): Map<Identifier, CellInfo> {
    val observations = LinkedHashMap<Identifier, CellInfo>()
    for (cellInfo in cellInfos) {
        // An unavailable timestamp is also in the future: Android initializes
        // its nanosecond field to Long.MAX_VALUE, then divides it for this API.
        // Keep valid older observations at their actual age; do not freshen
        // cached modem data or manufacture a timestamp for an unknown age.
        if (cellInfo.timestampMillis !in 0L..elapsedRealtimeMillis) continue
        val identifier = cellIdentifier(cellInfo) ?: continue
        val previous = observations[identifier]
        if (previous == null || cellInfo.timestampMillis > previous.timestampMillis) {
            observations[identifier] = cellInfo
        }
    }
    return observations
}

private fun cellIdentifier(cellInfo: CellInfo): Identifier? {
    val identity = cellInfo.cellIdentity
    val mcc = identity.mccString?.toIntOrNull() ?: return null
    val mnc = identity.mncString?.toIntOrNull() ?: return null
    return when (identity) {
        is CellIdentityNr -> {
            if (identity.tac == CellInfo.UNAVAILABLE || identity.nci == CellInfo.UNAVAILABLE_LONG) null
            else Identifier(Standard.NR, mcc, mnc, identity.tac, identity.nci)
        }
        is CellIdentityLte -> {
            if (identity.tac == CellInfo.UNAVAILABLE || identity.ci == CellInfo.UNAVAILABLE) null
            else Identifier(Standard.LTE, mcc, mnc, identity.tac, identity.ci.toLong())
        }
        is CellIdentityWcdma -> {
            if (identity.lac == CellInfo.UNAVAILABLE || identity.cid == CellInfo.UNAVAILABLE) null
            else Identifier(Standard.WCDMA, mcc, mnc, identity.lac, identity.cid.toLong())
        }
        is CellIdentityGsm -> {
            if (identity.lac == CellInfo.UNAVAILABLE || identity.cid == CellInfo.UNAVAILABLE) null
            else Identifier(Standard.GSM, mcc, mnc, identity.lac, identity.cid.toLong())
        }
        else -> null
    }
}
