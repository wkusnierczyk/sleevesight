package app.sleevesight.bench

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** The bench's one screen: model, backend and run set, Start and Stop, and the log of the run. */
class MainActivity : Activity() {
    private lateinit var models: RadioGroup
    private lateinit var backends: RadioGroup
    private lateinit var sets: RadioGroup
    private lateinit var start: Button
    private lateinit var stopButton: Button
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val stop = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 3, pad, pad * 4)
            }
        column.addView(label("Model"))
        models = group(Model.entries.map { "${it.label} (${it.file})" })
        column.addView(models)
        column.addView(label("Backend"))
        backends = group(BackendChoice.entries.map { it.name })
        column.addView(backends)
        column.addView(label("Run"))
        sets = group(RunSet.entries.map { it.label })
        column.addView(sets)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = Button(this).apply { text = "Start" }
        stopButton = Button(this).apply { text = "Stop" }
        start.setOnClickListener { begin() }
        stopButton.setOnClickListener {
            stop.set(true)
            append("Stopping after the current reply…")
        }
        buttons.addView(start)
        buttons.addView(stopButton)
        column.addView(buttons)
        logView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
            }
        column.addView(logView)
        scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        // Creates the external files folder, so `adb push` has somewhere to put the models.
        Bench.modelDir(this).mkdirs()
        Bench.resultsDir(this).mkdirs()
        stopButton.isEnabled = false
        append(status())
    }

    private fun status(): String =
        buildString {
            val dir = Bench.modelDir(this@MainActivity)
            appendLine("Models folder: ${dir.path}")
            for (m in Model.entries) {
                val f = java.io.File(dir, m.file)
                appendLine("  ${m.file}: ${if (f.isFile) Bench.mb(f.length()) else "missing"}")
            }
            val prompts = runCatching { Prompts.load(this@MainActivity) }.getOrNull()
            appendLine("Prompts: ${prompts?.all?.size ?: "?"} (prompt version ${prompts?.version ?: "?"})")
            appendLine("Results: ${Bench.resultsDir(this@MainActivity).path}")
            append(
                "Sampling: temperature ${Bench.TEMPERATURE}, top_k ${Bench.TOP_K}, top_p ${Bench.TOP_P}, " +
                    "max ${Bench.MAX_OUTPUT} output tokens",
            )
        }

    private fun begin() {
        if (worker?.isAlive == true) return
        val model = Model.entries[checked(models)]
        val backend = BackendChoice.entries[checked(backends)]
        val set = RunSet.entries[checked(sets)]
        val out = java.io.File(Bench.resultsDir(this), Bench.runName(model, backend, set))
        stop.set(false)
        enable(running = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logView.text = ""
        append("${model.label}, $backend, ${set.label}")
        worker =
            Thread({
                try {
                    Bench(
                        applicationContext,
                        model,
                        backend,
                        set,
                        out,
                        { line -> runOnUiThread { append(line) } },
                        stop,
                    ).run()
                } finally {
                    runOnUiThread {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        enable(running = false)
                    }
                }
            }, "bench").apply { start() }
    }

    private fun enable(running: Boolean) {
        start.isEnabled = !running
        stopButton.isEnabled = running
        for (g in listOf(models, backends, sets)) {
            for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = !running
        }
    }

    private fun append(line: String) {
        logView.append(line.trimEnd() + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun label(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun group(options: List<String>): RadioGroup =
        RadioGroup(this).apply {
            options.forEachIndexed { i, text ->
                addView(
                    RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        this.text = text
                        tag = i
                    },
                )
            }
            check(getChildAt(0).id)
        }

    private fun checked(group: RadioGroup): Int = group.findViewById<RadioButton>(group.checkedRadioButtonId).tag as Int
}
