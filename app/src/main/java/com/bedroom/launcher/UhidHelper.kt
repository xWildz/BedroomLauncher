package com.bedroom.launcher

import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs as the adb *shell* user (started by [GamepadBridge] through `app_process`), NOT inside the app.
 * The shell user is in this TV's `uhid` group, so it can create a virtual USB gamepad via /dev/uhid,
 * exactly like the BT800 remote's Bluetooth driver does. The kernel's hid-generic driver turns it into a
 * real controller ("Bedroom Phone Gamepad") that Android and RetroArch see like any plugged-in pad.
 *
 * stdin protocol, one line each:  "S <buttons hex> <hat 0-8>"  or  "Q" to remove the gamepad.
 */
object UhidHelper {
    private const val UHID_DESTROY = 1
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12
    private const val EVENT_SIZE = 4376 // sizeof(struct uhid_event), packed

    /** 16 buttons + one 8-way hat. Buttons 1..15 map to BTN_A, BTN_B, BTN_C, BTN_X, BTN_Y, BTN_Z, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE... */
    private val DESCRIPTOR = intArrayOf(
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x05, // Usage (Game Pad)
        0xA1, 0x01, // Collection (Application)
        0x05, 0x09, //   Usage Page (Button)
        0x19, 0x01, //   Usage Minimum (1)
        0x29, 0x10, //   Usage Maximum (16)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x10, //   Report Count (16)
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x05, 0x01, //   Usage Page (Generic Desktop)
        0x09, 0x39, //   Usage (Hat switch)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x07, //   Logical Maximum (7)
        0x35, 0x00, //   Physical Minimum (0)
        0x46, 0x3B, 0x01, // Physical Maximum (315)
        0x65, 0x14, //   Unit (Degrees)
        0x75, 0x04, //   Report Size (4)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x42, //   Input (Data, Var, Abs, Null state)
        0x65, 0x00, //   Unit (None)
        0x75, 0x04, //   Report Size (4)  padding
        0x95, 0x01, //   Report Count (1)
        0x81, 0x03, //   Input (Const)
        0xC0,       // End Collection
    ).map { it.toByte() }.toByteArray()

    /**
     * "Remote control" device (HID Consumer page, one 16-bit usage per report). hid-generic turns these
     * into real remote keys: 0x42-0x45 arrows, 0x41 OK (select), 0x224 Back, 0x223 Home, 0x40 Menu,
     * 0xCD play/pause, 0xE9/0xEA volume, 0xE2 mute, 0x221 search, 0x60 info.
     */
    private val REMOTE_DESCRIPTOR = intArrayOf(
        0x05, 0x0C,       // Usage Page (Consumer)
        0x09, 0x01,       // Usage (Consumer Control)
        0xA1, 0x01,       // Collection (Application)
        0x15, 0x00,       //   Logical Minimum (0)
        0x26, 0xFF, 0x03, //   Logical Maximum (0x3FF)
        0x19, 0x00,       //   Usage Minimum (0)
        0x2A, 0xFF, 0x03, //   Usage Maximum (0x3FF)
        0x75, 0x10,       //   Report Size (16)
        0x95, 0x01,       //   Report Count (1)
        0x81, 0x00,       //   Input (Data, Array, Abs)
        0xC0,             // End Collection
    ).map { it.toByte() }.toByteArray()

    /** Mode "keys": the remote-control device. Lines: "K <usage hex>" = press + release. */
    private fun runKeys() {
        val out = try {
            FileOutputStream("/dev/uhid")
        } catch (e: Exception) {
            println("error: can't open /dev/uhid (${e.message})")
            return
        }
        out.write(create("Bedroom Phone Remote", "bedroom-phone-remote", 0xB3D1, REMOTE_DESCRIPTOR))
        out.flush()
        println("ready")
        System.out.flush()
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val p = line.trim().split(" ")
            when (p.firstOrNull()) {
                "K" -> {
                    val usage = p.getOrNull(1)?.toIntOrNull(16) ?: continue
                    out.write(usageReport(usage))
                    out.flush()
                    Thread.sleep(20)
                    out.write(usageReport(0))
                    out.flush()
                }
                "Q" -> break
            }
        }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array())
        out.close()
    }

    private fun usageReport(usage: Int): ByteArray {
        val bb = ByteBuffer.allocate(4 + 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(UHID_INPUT2)
        bb.putShort(2)
        bb.putShort((usage and 0xFFFF).toShort())
        return bb.array()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "keys") {
            runKeys()
            return
        }
        val out = try {
            FileOutputStream("/dev/uhid")
        } catch (e: Exception) {
            println("error: can't open /dev/uhid (${e.message})")
            return
        }
        // "pad2": a second controller (player 2) with its own name and product id, so RetroArch sees two pads.
        val p2 = args.firstOrNull() == "pad2"
        out.write(
            if (p2) create("Bedroom Phone Gamepad 2", "bedroom-phone-gamepad-2", 0xB3D2, DESCRIPTOR)
            else create("Bedroom Phone Gamepad", "bedroom-phone-gamepad", 0xB3D0, DESCRIPTOR),
        )
        out.flush()
        println("ready")
        System.out.flush()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val p = line.trim().split(" ")
            when (p.firstOrNull()) {
                "S" -> if (p.size >= 3) {
                    val buttons = p[1].toIntOrNull(16) ?: continue
                    val hat = p[2].toIntOrNull() ?: continue
                    out.write(input(buttons, hat))
                }
                "Q" -> break
            }
        }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UHID_DESTROY).array())
        out.close()
    }

    private fun create(name: String, phys: String, product: Int, descriptor: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(UHID_CREATE2)
        putFixed(bb, name, 128)
        putFixed(bb, phys, 64)
        putFixed(bb, "", 64)
        bb.putShort(descriptor.size.toShort())
        bb.putShort(0x03)       // BUS_USB
        bb.putInt(0x1209)       // vendor (pid.codes, open-source range)
        bb.putInt(product)
        bb.putInt(0x0100)       // version
        bb.putInt(0)            // country
        bb.put(descriptor)
        return bb.array()
    }

    private fun putFixed(bb: ByteBuffer, s: String, n: Int) {
        val b = s.toByteArray(Charsets.US_ASCII)
        val len = minOf(b.size, n - 1)
        bb.put(b, 0, len)
        repeat(n - len) { bb.put(0) }
    }

    private fun input(buttons: Int, hat: Int): ByteArray {
        val bb = ByteBuffer.allocate(4 + 2 + 3).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(UHID_INPUT2)
        bb.putShort(3)
        bb.put((buttons and 0xFF).toByte())
        bb.put(((buttons shr 8) and 0xFF).toByte())
        bb.put((hat and 0x0F).toByte()) // 8 = centered (null)
        return bb.array()
    }
}
