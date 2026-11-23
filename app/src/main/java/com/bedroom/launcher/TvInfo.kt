package com.bedroom.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.WindowManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

/**
 * Everything the TV's chip and Android will tell us, for the "TV info" screen. Read-only: nothing here
 * changes a setting. Some parts come from Android itself, the rest from the TV's own adb shell (which can
 * read more of the chip's files than an app can).
 */
object TvInfo {
    data class Section(val title: String, val rows: List<Pair<String, String>>)

    fun collect(ctx: Context): List<Section> {
        val app = ctx.applicationContext
        val sh = shellDump(app)
        return listOf(
            overview(app, sh),
            processor(sh),
            graphics(sh),
            memory(sh),
            storage(app, sh),
            display(app, sh),
            videoCodecs(),
            audio(app, sh),
            drm(),
            network(sh),
            inputs(app, sh),
            sensors(app),
            software(app, sh),
            Section("Running now", lines(sh["top"])),
            Section("Kernel", lines(sh["version"]).map { "Version" to it.second } + lines(sh["cmdline"]).map { "Boot options" to it.second } +
                lines(sh["mods"]).map { "Module" to it.second }),
            Section("All system properties", props(sh["getprop"]).map { it.key to it.value }),
        ).map { s -> if (s.rows.isEmpty()) Section(s.title, listOf("" to "This TV doesn't report anything here.")) else s }
    }

    // ------------------------------------------------------------------ the shell part (one call)

    private fun shellDump(ctx: Context): Map<String, String> {
        val script = """
            echo @@cpuinfo; cat /proc/cpuinfo
            echo @@cpufreq; for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "${'$'}(basename ${'$'}c)|${'$'}(cat ${'$'}c/online 2>/dev/null)|${'$'}(cat ${'$'}c/cpufreq/scaling_cur_freq 2>/dev/null)|${'$'}(cat ${'$'}c/cpufreq/cpuinfo_min_freq 2>/dev/null)|${'$'}(cat ${'$'}c/cpufreq/cpuinfo_max_freq 2>/dev/null)|${'$'}(cat ${'$'}c/cpufreq/scaling_governor 2>/dev/null)"; done
            echo @@freqs; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies 2>/dev/null; echo; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null
            echo @@meminfo; cat /proc/meminfo
            echo @@zram; for f in disksize comp_algorithm max_comp_streams mm_stat; do echo "${'$'}f|${'$'}(cat /sys/block/zram0/${'$'}f 2>/dev/null)"; done; cat /proc/swaps
            echo @@lmk; cat /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null
            echo @@version; cat /proc/version
            echo @@cmdline; cat /proc/cmdline
            echo @@df; df -h 2>/dev/null
            echo @@mmc; for f in name type manfid oemid date fwrev hwrev life_time pre_eol_info; do echo "${'$'}f|${'$'}(cat /sys/block/mmcblk0/device/${'$'}f 2>/dev/null)"; done
            echo @@gpu; for d in /sys/class/misc/mali0/device /sys/devices/platform/mali /sys/class/devfreq/*; do [ -d ${'$'}d ] && for f in clock gpuclock cur_freq max_freq min_freq available_frequencies utilization dvfs governor; do [ -r ${'$'}d/${'$'}f ] && echo "${'$'}d/${'$'}f|${'$'}(cat ${'$'}d/${'$'}f 2>/dev/null | head -c 300)"; done; done
            echo @@disp; dumpsys display 2>/dev/null | grep -E 'mBaseDisplayInfo|DisplayModeRecord|mActiveMode|HdrCapabilities|mDefaultMode|density' | head -n 30
            echo @@hdmi; dumpsys hdmi_control 2>/dev/null | head -n 60
            echo @@audio; dumpsys audio 2>/dev/null | grep -iE 'hdmi|spdif|arc|encoded|surround|devices' | head -n 30
            echo @@wifi; dumpsys wifi 2>/dev/null | grep -m 1 'mWifiInfo'
            echo @@ip; ip -4 addr 2>/dev/null | grep -E 'inet |^[0-9]+:'
            echo @@input; cat /proc/bus/input/devices 2>/dev/null | grep -E '^N: |^I: '
            echo @@bt; dumpsys bluetooth_manager 2>/dev/null | grep -iE '^  (name|address|state):|enabled:|Bonded devices|  [0-9A-F]{2}:' | head -n 30
            echo @@uptime; uptime
            echo @@top; top -b -n 1 -m 12 2>/dev/null | head -n 20
            echo @@mods; lsmod 2>/dev/null | head -n 80
            echo @@getprop; getprop
        """.trimIndent()
        val out = runCatching { TvShell.run(ctx, script) }.getOrDefault("")
        val map = LinkedHashMap<String, String>()
        var key: String? = null
        val sb = StringBuilder()
        for (line in out.lines()) {
            if (line.startsWith("@@")) {
                key?.let { map[it] = sb.toString().trim() }
                key = line.removePrefix("@@").trim()
                sb.setLength(0)
            } else if (key != null) sb.append(line).append('\n')
        }
        key?.let { map[it] = sb.toString().trim() }
        return map
    }

    private fun lines(s: String?): List<Pair<String, String>> =
        s.orEmpty().lines().map { it.trimEnd() }.filter { it.isNotBlank() }.map { "" to it }

    private fun props(s: String?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val re = Regex("""^\[(.+?)]: \[(.*)]$""")
        s.orEmpty().lines().forEach { l -> re.find(l.trim())?.let { m[it.groupValues[1]] = it.groupValues[2] } }
        return m
    }

    private fun kv(s: String?, sep: Char = '|'): Map<String, String> =
        s.orEmpty().lines().mapNotNull { l -> l.split(sep, limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()

    private fun mhz(khz: String?): String = khz?.trim()?.toLongOrNull()?.let { "${it / 1000} MHz" } ?: "?"

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    // ------------------------------------------------------------------ sections

    private fun overview(ctx: Context, sh: Map<String, String>): Section {
        val p = props(sh["getprop"])
        val up = SystemClock.elapsedRealtime() / 60_000
        return Section("Overview", listOf(
            "Made by" to "${Build.MANUFACTURER} (brand ${Build.BRAND})",
            "Model" to Build.MODEL,
            "Device codename" to "${Build.DEVICE} (product ${Build.PRODUCT})",
            "Chip platform" to (p["ro.board.platform"] ?: Build.BOARD),
            "Hardware" to Build.HARDWARE,
            "Board" to Build.BOARD,
            "Processor ABIs" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Build" to "${Build.DISPLAY} · ${Build.TYPE} · ${Build.TAGS}",
            "Built" to java.text.DateFormat.getDateInstance().format(java.util.Date(Build.TIME)),
            "Up for" to if (up < 60) "$up min" else "${up / 60} h ${up % 60} m",
            "Load (1 / 5 / 15 min)" to (runCatching { File("/proc/loadavg").readText().split(' ').take(3).joinToString(" / ") }.getOrDefault("?")),
            "Temperature" to "Not reported by this TV",
            "Bedroom Home" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        ))
    }

    private val CPU_PARTS = mapOf(
        "0xc05" to "Cortex-A5", "0xc07" to "Cortex-A7", "0xc08" to "Cortex-A8", "0xc09" to "Cortex-A9", "0xc0f" to "Cortex-A15",
        "0xc0e" to "Cortex-A17", "0xd01" to "Cortex-A32", "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55",
        "0xd07" to "Cortex-A57", "0xd08" to "Cortex-A72", "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76",
    )

    private fun processor(sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val info = sh["cpuinfo"].orEmpty()
        val fields = info.lines().mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        val cores = fields.count { it.first == "processor" }
        val parts = fields.filter { it.first == "CPU part" }.map { CPU_PARTS[it.second.lowercase()] ?: it.second }.groupingBy { it }.eachCount()
        rows += "Cores" to "$cores (Android sees ${Runtime.getRuntime().availableProcessors()})"
        if (parts.isNotEmpty()) rows += "Core type" to parts.entries.joinToString(", ") { "${it.value} × ${it.key}" }
        fields.firstOrNull { it.first == "Hardware" }?.let { rows += "Chip name" to it.second }
        fields.firstOrNull { it.first.equals("model name", true) || it.first == "Processor" }?.let { rows += "Processor" to it.second }
        fields.firstOrNull { it.first == "CPU architecture" }?.let { rows += "Architecture" to "ARMv${it.second}" }
        fields.firstOrNull { it.first == "BogoMIPS" }?.let { rows += "BogoMIPS (per core)" to it.second }
        fields.firstOrNull { it.first == "Features" }?.let { rows += "Instruction features" to it.second }
        fields.firstOrNull { it.first == "Revision" }?.let { rows += "Board revision" to it.second }
        for (l in sh["cpufreq"].orEmpty().lines()) {
            val p = l.split('|')
            if (p.size < 6) continue
            val online = p[1].ifBlank { "1" } == "1"
            rows += p[0].replace("cpu", "Core ") to if (!online) "sleeping (off to save power)"
            else "now ${mhz(p[2])} · range ${mhz(p[3])} – ${mhz(p[4])} · governor ${p[5].ifBlank { "?" }}"
        }
        val f = sh["freqs"].orEmpty().lines()
        f.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { rows += "Speeds it can run at" to it.trim().split(Regex("\\s+")).joinToString(", ") { s -> mhz(s) } }
        f.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { rows += "Power governors" to it.trim() }
        fields.filter { it.first !in setOf("processor", "Features", "BogoMIPS") }.distinct().forEach { rows += "cpuinfo · ${it.first}" to it.second }
        return Section("Processor", rows)
    }

    private fun graphics(sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            val ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            rows += "Graphics chip" to (GLES20.glGetString(GLES20.GL_RENDERER) ?: "?")
            rows += "Made by" to (GLES20.glGetString(GLES20.GL_VENDOR) ?: "?")
            rows += "OpenGL ES" to (GLES20.glGetString(GLES20.GL_VERSION) ?: "?")
            rows += "Shading language" to (GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "?")
            val mt = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mt, 0)
            rows += "Largest texture" to "${mt[0]} px"
            rows += "EGL" to "${ver[0]}.${ver[1]} · ${EGL14.eglQueryString(dpy, EGL14.EGL_VENDOR)}"
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().trim().split(' ').filter { it.isNotBlank() }
            rows += "Extensions" to "${ext.size}"
            ext.sorted().forEach { rows += "Extension" to it }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
        } catch (e: Throwable) {
            rows += "Graphics chip" to "Couldn't ask (${e.javaClass.simpleName})"
        }
        kv(sh["gpu"]).forEach { (k, v) -> rows += "Chip file ${k.substringAfterLast("/devices/").substringAfterLast("/class/")}" to v }
        return Section("Graphics", rows)
    }

    private fun memory(sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val mem = sh["meminfo"].orEmpty().lines().mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        fun mb(k: String) = mem[k]?.substringBefore(' ')?.toLongOrNull()?.let { "${it / 1024} MB" } ?: "?"
        rows += "Total" to mb("MemTotal")
        rows += "Available now" to mb("MemAvailable")
        rows += "Completely free" to mb("MemFree")
        rows += "Cached (reusable)" to mb("Cached")
        rows += "Swap (compressed memory)" to "${mb("SwapTotal")}, ${mb("SwapFree")} free"
        val z = kv(sh["zram"])
        z["disksize"]?.toLongOrNull()?.let { rows += "zram size" to size(it) }
        z["comp_algorithm"]?.takeIf { it.isNotBlank() }?.let { rows += "zram compression" to it }
        z["max_comp_streams"]?.takeIf { it.isNotBlank() }?.let { rows += "zram streams" to it }
        z["mm_stat"]?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size >= 3 }?.let {
            rows += "zram use" to "${size(it[0])} of data squeezed into ${size(it[1])} (${size(it[2])} with overhead)"
        }
        sh["lmk"]?.takeIf { it.isNotBlank() }?.let { s ->
            rows += "Low-memory killer starts at" to s.split(',').mapNotNull { it.trim().toLongOrNull() }.joinToString(", ") { "${it * 4 / 1024} MB" }
        }
        mem.forEach { (k, v) -> rows += "meminfo · $k" to v }
        return Section("Memory", rows)
    }

    private fun storage(ctx: Context, sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            rows += "Built-in storage" to "${size(s.availableBytes)} free of ${size(s.totalBytes)}"
        }
        val m = kv(sh["mmc"])
        m["name"]?.takeIf { it.isNotBlank() }?.let { rows += "Storage chip" to "$it (${m["type"].orEmpty()})" }
        m["manfid"]?.takeIf { it.isNotBlank() }?.let { rows += "Chip maker ID" to it }
        m["date"]?.takeIf { it.isNotBlank() }?.let { rows += "Chip made" to it }
        m["fwrev"]?.takeIf { it.isNotBlank() }?.let { rows += "Chip firmware" to it }
        m["life_time"]?.takeIf { it.isNotBlank() }?.let { lt ->
            val v = lt.split(Regex("\\s+")).mapNotNull { it.removePrefix("0x").toIntOrNull(16) }
            rows += "Wear (estimated)" to v.joinToString(" / ") { if (it == 0) "unknown" else "${(it - 1) * 10}–${it * 10}% used" }
        }
        m["pre_eol_info"]?.takeIf { it.isNotBlank() }?.let {
            rows += "Health" to when (it.removePrefix("0x").toIntOrNull(16)) { 1 -> "Normal"; 2 -> "Warning (80% of spare blocks used)"; 3 -> "Urgent"; else -> it }
        }
        lines(sh["df"]).forEach { rows += "df" to it.second }
        return Section("Storage", rows)
    }

    private fun display(ctx: Context, sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val d = ctx.getSystemService(WindowManager::class.java)?.defaultDisplay
        if (d != null) {
            val mode = d.mode
            rows += "Current mode" to "${mode.physicalWidth} × ${mode.physicalHeight} at ${"%.2f".format(Locale.US, mode.refreshRate)} Hz"
            rows += "Supported modes" to d.supportedModes.joinToString(", ") { "${it.physicalWidth}×${it.physicalHeight}@${"%.0f".format(Locale.US, it.refreshRate)}" }
            val hdr = d.hdrCapabilities
            val types = hdr?.supportedHdrTypes?.map {
                when (it) { Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"; Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"; else -> "type $it" }
            }.orEmpty()
            rows += "HDR" to if (types.isEmpty()) "None reported" else types.joinToString(", ")
            if (hdr != null && types.isNotEmpty()) rows += "HDR brightness" to "max ${hdr.desiredMaxLuminance} nits, average ${hdr.desiredMaxAverageLuminance}, min ${hdr.desiredMinLuminance}"
            if (Build.VERSION.SDK_INT >= 26) rows += "Wide colour" to if (d.isWideColorGamut) "Yes" else "No"
            val dm = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
            rows += "Apps draw at" to "${dm.widthPixels} × ${dm.heightPixels} px, ${dm.densityDpi} dpi (scale ${dm.density})"
            rows += "Display name" to d.name
        }
        lines(sh["disp"]).forEach { rows += "dumpsys display" to it.second }
        lines(sh["hdmi"]).forEach { rows += "HDMI / CEC" to it.second }
        return Section("Display & HDMI", rows)
    }

    private fun videoCodecs(): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val list = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        rows += "Codecs on the chip" to "${list.size} (${list.count { !it.isEncoder }} decoders, ${list.count { it.isEncoder }} encoders)"
        for (c in list.sortedWith(compareBy({ it.isEncoder }, { it.name }))) {
            for (type in c.supportedTypes) {
                val caps = runCatching { c.getCapabilitiesForType(type) }.getOrNull() ?: continue
                val bits = mutableListOf<String>()
                caps.videoCapabilities?.let { v ->
                    bits += "up to ${v.supportedWidths.upper}×${v.supportedHeights.upper}"
                    if (runCatching { v.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false)) bits += "4K60"
                    else if (runCatching { v.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)) bits += "4K30"
                    else if (runCatching { v.areSizeAndRateSupported(1920, 1080, 60.0) }.getOrDefault(false)) bits += "1080p60"
                    bits += "max ${v.bitrateRange.upper / 1_000_000} Mbps"
                }
                caps.audioCapabilities?.let { a ->
                    bits += "${a.maxInputChannelCount} ch"
                    bits += "max ${a.bitrateRange.upper / 1000} kbps"
                }
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) bits += "secure (protected video)"
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) bits += "tunneled"
                if (type.startsWith("video/") && caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }) bits += "10-bit/HDR10"
                if (type.startsWith("video/") && caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 || it.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR }) bits += "VP9 HDR"
                val kind = if (c.isEncoder) "Encoder" else "Decoder"
                rows += "$kind · ${type.removePrefix("video/").removePrefix("audio/")}" to "${c.name} · ${bits.joinToString(", ")}"
            }
        }
        return Section("Video & audio codecs", rows)
    }

    private fun audio(ctx: Context, sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            rows += "Volume" to "${am.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" + if (am.isStreamMute(AudioManager.STREAM_MUSIC)) " (muted)" else ""
            rows += "Output sample rate" to "${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz, ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} frames per buffer"
            for (dev in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val type = when (dev.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TV speakers"; AudioDeviceInfo.TYPE_HDMI -> "HDMI"; AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Optical / S/PDIF"; AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"; AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                    AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"; else -> "type ${dev.type}"
                }
                val enc = dev.encodings.map {
                    when (it) { AudioFormat.ENCODING_AC3 -> "Dolby Digital"; AudioFormat.ENCODING_E_AC3 -> "Dolby Digital Plus"; AudioFormat.ENCODING_DTS -> "DTS"
                        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"; AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"; AudioFormat.ENCODING_PCM_16BIT -> "PCM 16"
                        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"; AudioFormat.ENCODING_IEC61937 -> "IEC61937 passthrough"; else -> "enc $it" }
                }
                rows += "Output · $type" to listOfNotNull(
                    dev.productName?.toString()?.takeIf { it.isNotBlank() },
                    if (enc.isNotEmpty()) "formats: " + enc.joinToString(", ") else "formats: any",
                    dev.sampleRates.takeIf { it.isNotEmpty() }?.let { "rates: " + it.joinToString(", ") },
                    dev.channelCounts.takeIf { it.isNotEmpty() }?.let { "channels: " + it.joinToString(", ") },
                ).joinToString(" · ")
            }
        }
        lines(sh["audio"]).forEach { rows += "dumpsys audio" to it.second }
        return Section("Sound", rows)
    }

    /** Reads the copy-protection (DRM) systems' own description of themselves. Information only. */
    private fun drm(): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val systems = listOf(
            "Widevine" to UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
            "PlayReady" to UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"),
            "ClearKey" to UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
        )
        for ((name, uuid) in systems) {
            if (!MediaDrm.isCryptoSchemeSupported(uuid)) { rows += name to "Not on this TV"; continue }
            val d = runCatching { MediaDrm(uuid) }.getOrNull()
            if (d == null) { rows += name to "Supported"; continue }
            try {
                fun prop(k: String) = runCatching { d.getPropertyString(k) }.getOrNull()?.takeIf { it.isNotBlank() }
                rows += name to "Supported"
                prop("vendor")?.let { rows += "$name · vendor" to it }
                prop("version")?.let { rows += "$name · version" to it }
                prop("securityLevel")?.let { rows += "$name · security level" to "$it" + if (it == "L1") " (full HD/4K allowed by streaming apps)" else if (it == "L3") " (streaming apps limit quality)" else "" }
                prop("systemId")?.let { rows += "$name · system ID" to it }
                prop("hdcpLevel")?.let { rows += "$name · HDCP now" to it }
                prop("maxHdcpLevel")?.let { rows += "$name · HDCP max" to it }
                prop("maxNumberOfSessions")?.let { rows += "$name · max sessions" to it }
                prop("oemCryptoApiVersion")?.let { rows += "$name · OEMCrypto API" to it }
                prop("algorithms")?.let { rows += "$name · algorithms" to it }
            } finally {
                runCatching { if (Build.VERSION.SDK_INT >= 28) d.close() else @Suppress("DEPRECATION") d.release() }
            }
        }
        return Section("Copy protection (DRM)", rows)
    }

    private fun network(sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                val ips = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().joinToString(", ") { it.hostAddress ?: "" }
                if (ips.isNotBlank()) rows += "Interface ${ni.name}" to ips + if (ni.inetAddresses.toList().any { Auth.isTailscale(it) }) " (Tailscale)" else ""
            }
        }
        sh["wifi"]?.substringAfter("mWifiInfo")?.split(", ")?.map { it.trim() }?.forEach { part ->
            val k = part.substringBefore(':').trim()
            val v = part.substringAfter(':', "").trim()
            if (k.isNotBlank() && v.isNotBlank()) rows += "Wi-Fi · $k" to v
        }
        lines(sh["ip"]).forEach { rows += "ip" to it.second }
        return Section("Network", rows)
    }

    private fun inputs(ctx: Context, sh: Map<String, String>): Section {
        val rows = mutableListOf<Pair<String, String>>()
        val im = ctx.getSystemService(InputManager::class.java)
        im?.inputDeviceIds?.toList()?.mapNotNull { id -> im.getInputDevice(id) }?.forEach { d: InputDevice ->
            val kinds = buildList {
                if (d.supportsSource(InputDevice.SOURCE_GAMEPAD)) add("gamepad")
                if (d.supportsSource(InputDevice.SOURCE_DPAD)) add("remote / D-pad")
                if (d.supportsSource(InputDevice.SOURCE_KEYBOARD)) add("keys")
                if (d.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) add("touch")
                if (d.isVirtual) add("virtual")
            }
            rows += d.name to "${kinds.joinToString(", ")} · vendor ${"%04x".format(d.vendorId)} product ${"%04x".format(d.productId)}"
        }
        lines(sh["bt"]).forEach { rows += "Bluetooth" to it.second }
        return Section("Remotes, gamepads & Bluetooth", rows)
    }

    private fun sensors(ctx: Context): Section {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val list = sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        return Section("Sensors", list.map { it.name to "${it.vendor} · type ${it.stringType}" })
    }

    private fun software(ctx: Context, sh: Map<String, String>): Section {
        val pm = ctx.packageManager
        fun ver(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Fingerprint" to Build.FINGERPRINT
        rows += "Bootloader" to Build.BOOTLOADER
        rows += "Radio / baseband" to (Build.getRadioVersion() ?: "none")
        rows += "Kernel" to (sh["version"] ?: System.getProperty("os.version") ?: "?")
        rows += "Java VM" to (System.getProperty("java.vm.version") ?: "?")
        rows += "Installed apps" to "${pm.getInstalledPackages(0).size}"
        listOf(
            "Google Play services" to "com.google.android.gms", "Play Store" to "com.android.vending",
            "WebView" to "com.google.android.webview", "YouTube" to "com.google.android.youtube.tv",
            "YouTube Music" to "com.google.android.youtube.tvmusic", "Hulu" to "com.hulu.livingroomplus",
            "RetroArch" to "com.retroarch", "Tailscale" to "com.tailscale.ipn",
        ).forEach { (n, p) -> ver(p)?.let { rows += n to it } }
        lines(sh["uptime"]).forEach { rows += "uptime" to it.second }
        return Section("Software", rows)
    }
}
