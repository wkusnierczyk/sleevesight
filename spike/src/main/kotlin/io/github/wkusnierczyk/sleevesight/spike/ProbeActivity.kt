package io.github.wkusnierczyk.sleevesight.spike

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Placeholder for the Phase 0 platform probe (#11). */
class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Sleevesight probe" })
    }
}
