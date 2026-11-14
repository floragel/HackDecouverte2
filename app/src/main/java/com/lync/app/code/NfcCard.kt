package com.lync.app.code

import android.app.Activity
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.os.Bundle

/**
 * NFC "tap to connect".
 *
 *  - The phone SHOWING its card emulates a tiny NFC card (Host Card Emulation, [LyncHceService])
 *    that answers with the user's 32-bit Lync code — only while the card is on screen.
 *  - The phone SCANNING uses reader mode ([NfcReader]) and also understands physical NFC stickers
 *    whose NDEF record is "https://lync.app/c/<code>" (stick one on a laptop, a club booth…).
 *
 * iOS port: iPhones can read (Core NFC: ISO7816 select + NDEF) but cannot emulate a card,
 * so an iPhone shows its Lync code instead and reads Android cards / stickers.
 */
object NfcCard {
    /** "F0" + "LYNC" + 01 — proprietary AID registered in res/xml/apduservice.xml */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x4C, 0x59, 0x4E, 0x43, 0x01)
    private val SELECT_HEADER = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())

    private const val PREFS = "lync_nfc"

    /** Called when the card screen appears/disappears. */
    fun setBroadcasting(context: Context, code: Long?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (code == null) remove("code") else putLong("code", code)
        }.apply()
    }

    internal fun broadcastCode(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { if (it.contains("code")) it.getLong("code", 0) else null }

    fun selectApdu(): ByteArray = SELECT_HEADER + byteArrayOf(AID.size.toByte()) + AID + byteArrayOf(0x00)

    internal fun isSelect(apdu: ByteArray): Boolean =
        apdu.size >= 5 + AID.size && apdu.copyOfRange(0, 4).contentEquals(SELECT_HEADER) &&
            apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)

    internal fun response(code: Long?): ByteArray {
        if (code == null) return NOT_FOUND
        return byteArrayOf((code shr 24).toByte(), (code shr 16).toByte(), (code shr 8).toByte(), code.toByte()) + OK
    }

    fun parseResponse(r: ByteArray): Long? {
        if (r.size != 6 || r[4] != 0x90.toByte() || r[5] != 0x00.toByte()) return null
        return ((r[0].toLong() and 0xFF) shl 24) or ((r[1].toLong() and 0xFF) shl 16) or ((r[2].toLong() and 0xFF) shl 8) or (r[3].toLong() and 0xFF)
    }

    fun parseUri(uri: String): Long? =
        Regex("lync\\.app/c/(\\d+)").find(uri)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 1..0xFFFFFFFFL }
}

class LyncHceService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray =
        if (NfcCard.isSelect(commandApdu)) NfcCard.response(NfcCard.broadcastCode(this)) else byteArrayOf(0x6D, 0x00)

    override fun onDeactivated(reason: Int) = Unit
}

/** Reader side: active while the scanner is open. */
class NfcReader(private val activity: Activity, private val onCode: (Long) -> Unit) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        adapter?.enableReaderMode(
            activity, ::onTag,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun stop() { adapter?.disableReaderMode(activity) }

    private fun onTag(tag: Tag) {
        // 1) another phone running Lync (HCE)
        IsoDep.get(tag)?.let { iso ->
            runCatching {
                iso.connect()
                iso.timeout = 2000
                val code = NfcCard.parseResponse(iso.transceive(NfcCard.selectApdu()))
                iso.close()
                if (code != null) { activity.runOnUiThread { onCode(code) }; return }
            }
        }
        // 2) a Lync NFC sticker
        Ndef.get(tag)?.let { ndef ->
            runCatching {
                ndef.connect()
                val msg: NdefMessage? = ndef.cachedNdefMessage ?: ndef.ndefMessage
                ndef.close()
                msg?.records?.firstNotNullOfOrNull { rec -> rec.toUri()?.toString()?.let(NfcCard::parseUri) }
                    ?.let { code -> activity.runOnUiThread { onCode(code) } }
            }
        }
    }
}
