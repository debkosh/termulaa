package com.debkosh.termulaa.push

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Each sender (a session) gets a steady colour and an initial, so it reads
 * as one person in the app, the notification shade and the popup.
 */
object Avatar {
    private val COLORS = longArrayOf(
        0xFF34D399, 0xFF60A5FA, 0xFFF472B6, 0xFFFBBF24, 0xFFA78BFA,
        0xFF2DD4BF, 0xFFFB923C, 0xFF4ADE80, 0xFF38BDF8, 0xFFE879F9,
    )

    /** Status marks and a "[project]" before the name. */
    private val PREFIX = Regex("^[^\\[\\p{L}\\p{N}]*(\\[[^\\]]*\\]\\s*)?")

    fun color(sender: String): Int = COLORS[Math.floorMod(sender.hashCode(), COLORS.size)].toInt()

    /**
     * The letter a sender shows: the first letter of its name after any
     * "[project]" prefix and status marks, so "[memd] phone notifications"
     * is P.
     */
    fun initial(sender: String): String {
        val name = sender.replace(PREFIX, "")
        return (name.firstOrNull { it.isLetterOrDigit() } ?: sender.firstOrNull { it.isLetterOrDigit() } ?: '?')
            .uppercase()
    }

    /** The bracketed project in a session name, "[memd] …" → "memd", or "". */
    fun project(sender: String): String = Regex("\\[([^\\]]+)\\]").find(sender)?.groupValues?.get(1) ?: ""

    /** The name without its project and marks: "[memd] phone notifications" → "phone notifications". */
    fun name(sender: String): String =
        sender.replace(PREFIX, "").ifBlank { sender }

    fun bitmap(sender: String, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(sender) }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFF0F1115.toInt()
        p.textSize = size * 0.5f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(initial(sender), size / 2f, y, p)
        return b
    }
}
