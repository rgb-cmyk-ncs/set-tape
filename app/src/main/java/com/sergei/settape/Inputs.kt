package com.sergei.settape

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Finding and describing the audio inputs Android currently offers. */
object Inputs {
    const val AUTO = "auto"

    private val USABLE = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_HDMI,
    )

    /** Inputs worth offering, USB first, one entry per distinct device. */
    fun list(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in USABLE }
            .distinctBy { key(it) }
            .sortedBy { if (isUsb(it)) 0 else 1 }

    /** A name for the device that survives unplugging and replugging (ids do not). */
    fun key(d: AudioDeviceInfo): String = "${d.type}|${d.productName}"

    fun isUsb(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.trim().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset jack"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> if (name.isEmpty()) "USB audio" else "$name (USB)"
            AudioDeviceInfo.TYPE_USB_HEADSET -> if (name.isEmpty()) "USB headset" else "$name (USB headset)"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "Line in"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital in"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI in"
            else -> if (name.isEmpty()) "Input" else name
        }
    }

    /**
     * The device to record from for a saved choice. With [AUTO], or when the saved device is not
     * plugged in, a USB audio device wins; null means "whatever Android uses by default".
     */
    fun resolve(am: AudioManager, key: String): AudioDeviceInfo? {
        val all = list(am)
        if (key != AUTO) all.firstOrNull { key(it) == key }?.let { return it }
        return all.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE } ?: all.firstOrNull { isUsb(it) }
    }

    /** The most channels the device says it can deliver at once (at least 2). */
    fun maxChannels(d: AudioDeviceInfo?): Int {
        if (d == null) return 2
        var max = 0
        for (n in d.channelCounts) if (n > max) max = n
        for (m in d.channelIndexMasks) max = maxOf(max, Integer.bitCount(m))
        for (m in d.channelMasks) max = maxOf(max, Integer.bitCount(m))
        return if (max <= 0) 2 else max
    }

    /** The sample rate to try first: the user's choice, else 48 kHz if offered, else 44.1 kHz. */
    fun preferredRate(d: AudioDeviceInfo?, wanted: Int): Int {
        if (wanted > 0) return wanted
        val rates = d?.sampleRates ?: IntArray(0)
        if (rates.isEmpty() || 48000 in rates) return 48000
        if (44100 in rates) return 44100
        return rates.filter { it <= 96000 }.maxOrNull() ?: rates[0]
    }
}
