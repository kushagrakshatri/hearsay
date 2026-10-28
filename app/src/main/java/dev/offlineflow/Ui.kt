package dev.offlineflow

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M

/** Small view builders shared by Settings and Setup, so both screens look the same. */
class Ui(val ctx: Context) {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Number) = (v.toFloat() * density).toInt()

    fun color(@AttrRes attr: Int, view: View? = null): Int =
        if (view != null) MaterialColors.getColor(view, attr) else MaterialColors.getColor(ctx, attr, 0)

    val primary get() = color(M.attr.colorPrimary)
    val onSurface get() = color(M.attr.colorOnSurface)
    val onSurfaceVariant get() = color(M.attr.colorOnSurfaceVariant)
    val surfaceContainer get() = color(M.attr.colorSurfaceContainerLow)
    val error get() = color(M.attr.colorError)

    fun title(t: String, size: Float = 22f) = TextView(ctx).apply {
        text = t; textSize = size; setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setTextColor(onSurface)
    }

    fun body(t: String, size: Float = 15f) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(onSurface); setLineSpacing(0f, 1.15f)
    }

    fun hint(t: String) = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(onSurfaceVariant); setLineSpacing(0f, 1.15f)
    }

    fun overline(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); setTextColor(primary)
        setPadding(dp(4), dp(28), dp(4), dp(8))
    }

    fun filled(label: String, action: () -> Unit) = MaterialButton(ctx).apply {
        text = label; isAllCaps = false; minHeight = dp(52); cornerRadius = dp(26); setOnClickListener { action() }
    }

    fun tonal(label: String, action: () -> Unit) = filled(label, action).apply {
        backgroundTintList = ColorStateList.valueOf(color(M.attr.colorSecondaryContainer))
        setTextColor(color(M.attr.colorOnSecondaryContainer))
    }

    fun outlined(label: String, action: () -> Unit) =
        MaterialButton(ctx, null, M.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; cornerRadius = dp(24); setOnClickListener { action() }
        }

    fun text(label: String, action: () -> Unit) =
        MaterialButton(ctx, null, M.attr.borderlessButtonStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }

    /** A rounded, flat card on the surface tone. */
    fun card(vararg children: View) = MaterialCardView(ctx).apply {
        radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
        setCardBackgroundColor(surfaceContainer)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
        addView(column(dp(20), dp(18)).apply { children.forEach { addView(it) } })
    }

    fun column(padH: Int = 0, padV: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(padH, padV, padH, padV)
    }

    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun icon(@DrawableRes res: Int, tint: Int, sizeDp: Int = 24) = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun field(value: String, label: String, multiline: Int = 0, secret: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(ctx).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline > 0 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline > 0) { minLines = multiline; maxLines = 12; gravity = Gravity.TOP or Gravity.START }
        }
        layout.addView(edit)
        return layout to edit
    }

    fun space(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    companion object {
        /** Draws behind the system bars and pads [root] so content clears them and the keyboard. */
        fun edgeToEdge(activity: Activity, root: View, extraTop: Int = 0, extraBottom: Int = 0) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                v.setPadding(l + bars.left, t + bars.top + extraTop, r + bars.right, b + bars.bottom + extraBottom)
                insets
            }
        }
    }
}
