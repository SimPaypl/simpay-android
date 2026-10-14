package pl.simpay.android.checkout.mobile.internal.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

/**
 * Colours and dimensions of the SimPay sheet, matching the SimPay web gateway.
 *
 * Defined in code rather than as a theme so the SDK never fights the styling of the
 * integrating app and needs no theme attributes from it.
 */
internal object SheetStyle {
    const val BACKGROUND = 0xFFFFFFFF.toInt()
    const val TEXT_PRIMARY = 0xFF101828.toInt()
    const val TEXT_SECONDARY = 0xFF667085.toInt()

    /** SimPay brand green, used for every primary action and highlight. */
    const val ACCENT = 0xFF00D150.toInt()
    const val ACCENT_DISABLED = 0xFFA8E9C1.toInt()

    const val SURFACE = 0xFFF5F6F8.toInt()
    const val BORDER = 0xFFE8EAEE.toInt()
    const val SUCCESS = 0xFF00D150.toInt()
    const val DANGER = 0xFFD92D20.toInt()
    const val HIGHLIGHT = 0xFFE9FBF0.toInt()
}

internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

internal fun roundedBackground(
    color: Int,
    radiusPx: Float,
    strokeColor: Int? = null,
    strokeWidthPx: Int = 0
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx
    setColor(color)
    strokeColor?.let { setStroke(strokeWidthPx, it) }
}

internal fun topRoundedBackground(color: Int, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        setColor(color)
    }

/** Builds the reusable pieces of the sheet UI. */
internal object SheetViews {

    fun body(context: Context, text: String, color: Int = SheetStyle.TEXT_SECONDARY): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 15f
        }

    /** Small grey caption above a value, as used by the gateway summary. */
    fun caption(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(SheetStyle.TEXT_SECONDARY)
        textSize = 12f
    }

    fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(SheetStyle.TEXT_PRIMARY)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Light grey panel grouping related content, like the gateway cards. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        background = roundedBackground(SheetStyle.SURFACE, context.dp(16).toFloat())
    }

    /** Vertical accent rule marking the amount due. */
    fun accentBar(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(4), context.dp(40)).apply {
            leftMargin = context.dp(12)
        }
        background = roundedBackground(SheetStyle.ACCENT, context.dp(2).toFloat())
    }

    /**
     * Large mark centred above a final message.
     *
     * The gravity applies to the parent [LinearLayout], so the caller only has to add
     * it to the vertical content column.
     */
    fun illustration(
        context: Context,
        @DrawableRes resId: Int,
        sizeDp: Int = 88
    ): ImageView = ImageView(context).apply {
        setImageResource(resId)
        layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(14)
        }
    }

    fun primaryButton(context: Context, text: String): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = roundedBackground(SheetStyle.ACCENT, context.dp(14).toFloat())
        stateListAnimator = null
        minimumHeight = context.dp(52)
    }

    fun secondaryButton(context: Context, text: String): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(SheetStyle.TEXT_SECONDARY)
        background = roundedBackground(
            color = Color.TRANSPARENT,
            radiusPx = context.dp(14).toFloat(),
            strokeColor = SheetStyle.BORDER,
            strokeWidthPx = context.dp(1)
        )
        stateListAnimator = null
        minimumHeight = context.dp(48)
    }

    fun setButtonEnabled(context: Context, button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.background = roundedBackground(
            color = if (enabled) SheetStyle.ACCENT else SheetStyle.ACCENT_DISABLED,
            radiusPx = context.dp(14).toFloat()
        )
    }

    fun verticalContainer(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun spacer(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(heightDp))
    }

    /** Branded spinner with the SimPay mark, used by every waiting state. */
    fun progress(context: Context): View = SimPayLoaderView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(72), context.dp(72)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    fun scroll(context: Context, content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** A single channel row: logo placeholder, name and an optional highlight. */
    fun channelRow(
        context: Context,
        name: String,
        subtitle: String?,
        highlighted: Boolean
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            background = roundedBackground(
                color = if (highlighted) SheetStyle.HIGHLIGHT else SheetStyle.SURFACE,
                radiusPx = context.dp(16).toFloat(),
                strokeColor = if (highlighted) SheetStyle.ACCENT else SheetStyle.BORDER,
                strokeWidthPx = context.dp(if (highlighted) 2 else 1)
            )

            val logo = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                background = roundedBackground(Color.WHITE, context.dp(12).toFloat())
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            }

            val initial = TextView(context).apply {
                text = name.take(1).uppercase()
                setTextColor(SheetStyle.ACCENT)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                background = roundedBackground(Color.WHITE, context.dp(12).toFloat())
            }

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(context.dp(12), 0, context.dp(8), 0)

                addView(
                    TextView(context).apply {
                        text = name
                        setTextColor(SheetStyle.TEXT_PRIMARY)
                        textSize = 16f
                        typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    }
                )
                subtitle?.let { value ->
                    addView(
                        TextView(context).apply {
                            text = value
                            setTextColor(SheetStyle.TEXT_SECONDARY)
                            textSize = 13f
                        }
                    )
                }
            }

            // The initial is shown behind the logo and stays visible when the remote
            // asset cannot be decoded, for example for SVG icons.
            val logoContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                addView(initial)
                addView(logo)
            }

            addView(logoContainer)
            addView(texts)
            addView(
                TextView(context).apply {
                    text = "›"
                    setTextColor(SheetStyle.TEXT_SECONDARY)
                    textSize = 22f
                }
            )

            setTag(TAG_LOGO, logo)
        }
    }

    const val TAG_LOGO = 0x7F0000A1

    /**
     * Drops everything that is not a digit, also when the text arrives from a paste.
     */
    private object DigitsOnlyFilter : InputFilter {
        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            val filtered = source.subSequence(start, end).filter { it.isDigit() }
            return if (filtered.length == end - start) null else filtered
        }
    }

    /**
     * Six digit BLIK input with wide letter spacing and a numeric keyboard.
     */
    fun blikInput(context: Context, onChanged: (String) -> Unit): EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(6), DigitsOnlyFilter)
        textSize = 30f
        letterSpacing = 0.35f
        gravity = Gravity.CENTER
        setTextColor(SheetStyle.TEXT_PRIMARY)
        hint = "••••••"
        isSaveEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        background = roundedBackground(
            color = Color.WHITE,
            radiusPx = context.dp(16).toFloat(),
            strokeColor = SheetStyle.BORDER,
            strokeWidthPx = context.dp(1)
        )
        setPadding(context.dp(16), context.dp(18), context.dp(16), context.dp(18))

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChanged(s?.toString().orEmpty())
            }
        })
    }
}

