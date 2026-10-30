package dev.offlineflow.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug builds only. Plain chat and mail screens the launch video is recorded on, so the footage shows
 * the real floating mic and real models without anyone's private conversations.
 *   adb shell am start -n dev.hearsay.debug/dev.offlineflow.demo.DemoActivity --es scene chat --es name Maya
 *   scene: chat | mail; incoming chat messages as --es m1 … --es m4
 */
class DemoActivity : Activity() {
    private val ink = Color.parseColor("#111827")
    private val muted = Color.parseColor("#6B7280")
    private val brand = Color.parseColor("#4F46E5")
    private lateinit var thread: LinearLayout
    private lateinit var scroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun text(s: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = when (intent.getStringExtra("scene")) {
            "mail" -> mail()
            else -> chat(intent.getStringExtra("name") ?: "Maya", listOfNotNull(*(1..4).map { intent.getStringExtra("m$it") }.toTypedArray()).ifEmpty { null }
                ?: listOf("dinner this week? 🍝", "that new place on Santana Row finally opened"))
        }
        root.setBackgroundColor(Color.WHITE)
        setContentView(root)
    }

    // ---------------------------------------------------------------- chat

    private fun chat(name: String, incoming: List<String>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Header: avatar, name, status.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(text("‹", 30f, ink).apply { setPadding(0, 0, dp(12), dp(4)) })
            addView(text(name.take(1), 18f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = round(Color.parseColor("#F59E0B"), 22)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                addView(text(name, 18f, ink, true))
                addView(text("online", 13f, Color.parseColor("#10B981")))
            })
        }
        col.addView(header)
        col.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")) }, LinearLayout.LayoutParams(-1, dp(1)))
        thread = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(16)) }
        thread.addView(text("Today", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) }, LinearLayout.LayoutParams(-1, -2))
        incoming.forEach { bubble(it, mine = false) }
        scroll = ScrollView(this).apply { addView(thread); isFillViewport = true }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Composer.
        val input = EditText(this).apply {
            hint = "Message"; textSize = 17f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5; background = round(Color.parseColor("#F3F4F6"), 22)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        watch(input)
        val send = text("➤", 20f, Color.WHITE).apply {
            post { spot("send.txt", this) }
            gravity = Gravity.CENTER; background = round(brand, 22); contentDescription = "Send"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { bubble(t, mine = true); input.setText("") }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }
        col.addView(bar)
        return insets(col)
    }

    private fun bubble(s: String, mine: Boolean) {
        val tv = text(s, 16.5f, if (mine) Color.WHITE else ink).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); maxWidth = dp(280)
            background = round(if (mine) brand else Color.parseColor("#F3F4F6"), 18)
        }
        thread.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (mine) Gravity.END else Gravity.START; topMargin = dp(6)
        })
        if (::scroll.isInitialized) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- mail

    private fun mail(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text("✕", 20f, ink))
            addView(text("Compose", 20f, ink, true).apply { setPadding(dp(20), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Send", 15f, Color.WHITE, true).apply {
                setPadding(dp(18), dp(8), dp(18), dp(8)); background = round(brand, 18)
            })
        })
        fun row(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(label, 16f, muted), LinearLayout.LayoutParams(dp(72), -2))
            addView(text(value, 16f, ink))
        }
        fun line() = View(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")) }
        col.addView(row("From", "kushagra@hearsay.dev")); col.addView(line(), -1, dp(1))
        col.addView(row("To", "team@hearsay.dev")); col.addView(line(), -1, dp(1))
        col.addView(row("Subject", "Launch update")); col.addView(line(), -1, dp(1))
        val body = EditText(this).apply {
            hint = "Compose email"; textSize = 17f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START; background = null
            setPadding(dp(16), dp(16), dp(16), dp(16)); setLineSpacing(0f, 1.15f)
        }
        watch(body)
        col.addView(ScrollView(this).apply { addView(body, ViewGroup.LayoutParams(-1, -2)); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        return insets(col)
    }

    // The recording script reads the field and the Send button from files: uiautomator would register a
    // UiAutomation, and that unbinds every accessibility service, the floating mic included.
    private val demoDir get() = java.io.File(getExternalFilesDir(null), "demo").apply { mkdirs() }

    private fun spot(name: String, v: View) {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        runCatching { java.io.File(demoDir, name).writeText("${loc[0] + v.width / 2} ${loc[1] + v.height / 2}") }
    }

    private fun watch(field: EditText) {
        fun write() = runCatching { java.io.File(demoDir, "field.txt").writeText(field.text.toString()) }
        write()
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { write() }
        })
        // Focus it and bring up the keyboard, the way a real compose screen opens.
        field.post {
            field.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0)
        }
    }

    /** Keeps content clear of the status bar, and the composer above the keyboard. */
    private fun insets(v: View): View = FrameLayout(this).apply {
        addView(v, -1, -1)
        setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom); ins
        }
    }
}
