package com.debkosh.termulaa.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.debkosh.termulaa.MainActivity
import kotlin.math.abs

/**
 * A card at the top of the screen, over whatever app is open, when a
 * message arrives: who sent it, and the sentence, so nothing needs a tap to
 * be read. Each sender has one card (a newer message replaces it), at most
 * [MAX] stack, and each goes by itself after a few seconds. Tap opens the
 * session; a sideways swipe dismisses. Needs "Display over other apps".
 */
class Popup(private val context: Context, private val store: PushStore) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var stack: LinearLayout? = null
    private val cards = LinkedHashMap<String, View>()

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    /** Shows [m] if the chosen mode wants its level, the screen is on and the permission is given. */
    fun offer(m: PushMessage, spoken: Boolean) {
        val wanted = when (store.popupMode) {
            PushStore.PopupMode.OFF -> false
            PushStore.PopupMode.IMPORTANT -> m.level == "decision" || m.level == "blocker"
            PushStore.PopupMode.ALL -> true
        }
        if (!wanted) return
        show(m, spoken)
    }

    /** Shows [m] now, whatever the mode (the settings sample uses it too). */
    fun show(m: PushMessage, spoken: Boolean = false) {
        main.post {
            if (!allowed()) return@post
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) return@post
            val sender = m.from?.takeIf { it.isNotBlank() } ?: "termulaa"
            val root = stack ?: attach() ?: return@post
            cards.remove(sender)?.let { root.removeView(it) }
            while (cards.size >= MAX) {
                val (k, v) = cards.entries.first()
                cards.remove(k)
                root.removeView(v)
            }
            val card = card(m, sender, spoken)
            cards[sender] = card
            root.addView(card, 0)
            val seconds = store.popupSeconds * if (m.level == "info") 1 else 2
            main.postDelayed({ dismiss(sender, card) }, seconds * 1000L)
        }
    }

    private fun dismiss(sender: String, card: View) {
        if (cards[sender] !== card) return
        cards.remove(sender)
        card.animate().alpha(0f).setDuration(180).withEndAction {
            stack?.removeView(card)
            if (cards.isEmpty()) detach()
        }.start()
    }

    private fun attach(): LinearLayout? {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(28)
        }
        return try {
            wm.addView(root, lp)
            stack = root
            root
        } catch (_: Exception) {
            null
        }
    }

    private fun detach() {
        val root = stack ?: return
        stack = null
        try {
            wm.removeView(root)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun card(m: PushMessage, sender: String, spoken: Boolean): View {
        val accent = when (m.level) {
            "decision" -> 0xFFFBBF24.toInt()
            "blocker" -> 0xFFF87171.toInt()
            else -> 0xFF34D399.toInt()
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF21A1D23.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), accent and 0x80FFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) }
        }
        card.addView(ImageView(context).apply {
            setImageBitmap(Avatar.bitmap(sender, dp(40)))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = listOfNotNull(
            Avatar.name(sender),
            Avatar.project(sender).takeIf { it.isNotEmpty() },
            when (m.level) { "decision" -> "needs you"; "blocker" -> "blocked"; else -> null },
            if (spoken) "speaking" else null,
        ).joinToString(" · ")
        text.addView(label(head, 12f, accent, bold = true, lines = 1))
        text.addView(label(m.title ?: "", 15f, 0xFFE6E6E6.toInt(), bold = false, lines = 3))
        m.body?.takeIf { it.isNotBlank() }?.let {
            text.addView(label(it, 13f, 0xFF8B919E.toInt(), bold = false, lines = 3))
        }
        card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Tap opens the session; a sideways drag past a third of the width dismisses.
        var downX = 0f
        var moved = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > dp(8)) moved = true
                    v.translationX = dx
                    v.alpha = 1f - (abs(dx) / v.width).coerceIn(0f, 0.8f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (abs(v.translationX) > v.width / 3f) {
                        cards.remove(sender)
                        stack?.removeView(v)
                        if (cards.isEmpty()) detach()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(120).start()
                        if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                            open(m)
                            cards.remove(sender)
                            stack?.removeView(v)
                            if (cards.isEmpty()) detach()
                        }
                    }
                }
            }
            true
        }
        return card
    }

    private fun open(m: PushMessage) {
        val link = m.link?.takeIf { it.startsWith("https://") }
        val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link))
        else Intent(context, MainActivity::class.java)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean, lines: Int) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        const val MAX = 3
    }
}
