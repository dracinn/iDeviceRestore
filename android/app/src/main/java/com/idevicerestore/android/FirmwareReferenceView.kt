package com.idevicerestore.android

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Firmware presentation contract matching the approved five-screen reference. */
class FirmwareReferenceView(context: Context) : LinearLayout(context) {
    private val primary = color(R.color.mock_primary)
    private val textPrimary = color(R.color.mock_text_primary)
    private val textSecondary = color(R.color.mock_text_secondary)
    private val separator = color(R.color.mock_separator)
    private val surface = color(R.color.mock_surface)
    private val deviceRows = mutableListOf<View>()

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(18))
        addHeader()
        addSearch()
        addFamilyTabs()
        addDeviceList()
        addFirmwareList()
    }

    private fun addHeader() {
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(label("Firmware", 25f, true), LayoutParams(0, dp(46), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        bar.addView(iconButton(R.drawable.ic_download, "Downloads") { delegate(R.id.downloadFirmwareButton) }, LayoutParams(dp(44), dp(44)))
        bar.addView(MaterialButton(context).apply {
            text = "⋮"; textSize = 25f; setTextColor(textPrimary); minWidth = 0; minimumWidth = 0
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { delegate(R.id.selectFirmwareButton) }
        }, LayoutParams(dp(34), dp(44)))
        addView(bar)
        addView(label("Browse, download, and manage\nApple firmware files.", 12f, false).apply {
            setLineSpacing(0f, 1.05f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun addSearch() {
        val radius = dp(12).toFloat()
        val box = TextInputLayout(context).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(surface)
            setBoxCornerRadii(radius, radius, radius, radius)
            setBoxStrokeColorStateList(android.content.res.ColorStateList.valueOf(separator))
            hint = "Search devices (e.g. MacBookAir10,1)"
            hintTextColor = android.content.res.ColorStateList.valueOf(textSecondary)
        }
        val edit = TextInputEditText(context).apply {
            setSingleLine(true); textSize = 11f; setTextColor(textPrimary); setHintTextColor(textSecondary)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterDevices(s?.toString().orEmpty())
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        box.addView(edit)
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(3) })
    }

    private fun addFamilyTabs() {
        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)) }
        listOf("Mac", "iPhone", "iPad", "Watch").forEachIndexed { index, name ->
            tabs.addView(MaterialButton(context).apply {
                text = name; textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setTextColor(if (index == 0) android.graphics.Color.WHITE else textPrimary)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 0) primary else surface)
                cornerRadius = dp(9)
                setOnClickListener {
                    if (index != 0) android.widget.Toast.makeText(context, "$name catalog will use the same reference layout", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, LayoutParams(0, dp(38), 1f))
        }
        addView(tabs)
    }

    private fun addDeviceList() {
        val card = card()
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        val devices = listOf(
            Triple("MacBook Air (M1, Late 2020)", "MacBookAir10,1", true),
            Triple("MacBook Pro (M1, 2020)", "MacBookPro17,1", false),
            Triple("Mac mini (M1, 2020)", "Macmini9,1", false),
            Triple("MacBook Air (Intel, 2020)", "MacBookAir9,1", false),
            Triple("MacBook Pro (16-inch, 2019)", "MacBookPro16,1", false)
        )
        devices.forEachIndexed { i, item -> body.addView(deviceRow(item.first, item.second, item.third, i)) }
        card.addView(body)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun deviceRow(name: String, id: String, selected: Boolean, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(8), dp(5)); tag = "$name $id"
            if (selected) setBackgroundColor(0xFFE8F2FF.toInt())
            setOnClickListener { delegate(R.id.selectFirmwareButton) }
        }
        val art = ImageView(context).apply { setImageResource(R.drawable.mock_asset_macbook); scaleType = ImageView.ScaleType.CENTER_INSIDE; alpha = if (index == 2) .7f else 1f }
        row.addView(art, LayoutParams(dp(58), dp(48)))
        val copy = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        copy.addView(label(name, 11f, true)); copy.addView(label(id, 10f, false))
        row.addView(copy, LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        row.addView(label("›", 23f, false).apply { gravity = Gravity.CENTER }, LayoutParams(dp(24), dp(48)))
        deviceRows += row
        return row
    }

    private fun addFirmwareList() {
        val heading = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(6), dp(5)) }
        heading.addView(label("Available Firmware", 13f, true), LayoutParams(0, dp(28), 1f))
        heading.addView(label("See All", 11f, false).apply { setTextColor(primary); gravity = Gravity.CENTER_VERTICAL or Gravity.END }, LayoutParams(dp(60), dp(28)))
        addView(heading)
        val card = card()
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        listOf(
            arrayOf("26.6.2 (25G83)", "Signed", "12.4 GB"),
            arrayOf("26.6.1 (25G68)", "Signed", "12.4 GB"),
            arrayOf("26.5 (25F79)", "Signed", "12.3 GB"),
            arrayOf("26.4 (25E62)", "Unsigned", "12.2 GB")
        ).forEach { body.addView(firmwareRow(it[0], it[1], it[2])) }
        card.addView(body)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun firmwareRow(version: String, status: String, size: String): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(5), 0) }
        row.addView(label(version, 10.5f, true), LayoutParams(0, dp(45), 1.2f).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(TextView(context).apply {
            text = status; textSize = 9f; gravity = Gravity.CENTER; setTextColor(if (status == "Signed") 0xFF187B38.toInt() else textPrimary)
            setBackgroundResource(if (status == "Signed") R.drawable.mock_badge_success else R.drawable.mock_badge_neutral)
        }, LayoutParams(dp(58), dp(25)).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = dp(5) })
        row.addView(label(size, 9.5f, false).apply { gravity = Gravity.CENTER }, LayoutParams(dp(52), dp(45)))
        row.addView(iconButton(R.drawable.ic_download, "Download $version") { delegate(R.id.downloadFirmwareButton) }, LayoutParams(dp(38), dp(38)))
        return row
    }

    private fun filterDevices(query: String) {
        val q = query.trim().lowercase()
        deviceRows.forEach { it.visibility = if (q.isEmpty() || it.tag.toString().lowercase().contains(q)) View.VISIBLE else View.GONE }
    }

    private fun delegate(id: Int) { rootView.findViewById<View?>(id)?.performClick() }
    private fun card() = MaterialCardView(context).apply { radius = dp(12).toFloat(); cardElevation = 0f; setCardBackgroundColor(surface); strokeColor = separator; strokeWidth = dp(1) }
    private fun label(value: String, size: Float, bold: Boolean) = TextView(context).apply { text = value; textSize = size; setTextColor(if (bold) textPrimary else textSecondary); if (bold) setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }
    private fun iconButton(iconRes: Int, description: String, click: () -> Unit) = MaterialButton(context).apply {
        text = ""
        contentDescription = description
        setIcon(ContextCompat.getDrawable(context, iconRes))
        iconTint = android.content.res.ColorStateList.valueOf(textPrimary)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconPadding = 0
        minWidth = 0
        minimumWidth = 0
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnClickListener { click() }
    }
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
