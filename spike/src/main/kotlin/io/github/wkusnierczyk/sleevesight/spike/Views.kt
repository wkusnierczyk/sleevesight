package io.github.wkusnierczyk.sleevesight.spike

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/** Small builders for the probe's plain-view screen. */
fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(vararg views: View) = LinearLayout(this).apply { views.forEach { addView(it) } }

fun Context.button(
    label: String,
    onClick: () -> Unit,
) = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener { onClick() }
}
