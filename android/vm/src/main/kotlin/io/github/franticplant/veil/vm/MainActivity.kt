package io.github.franticplant.veil.vm

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The VM host package's first screen. It reports the AVF capabilities and runs the vsock probe; the
 * service and the engine backend come next.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var protectedButton: Button
    private lateinit var unprotectedButton: Button
    private lateinit var dumpButton: Button
    private lateinit var copyButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output =
            TextView(this).apply {
                textSize = 12f
                setTextIsSelectable(true)
            }
        protectedButton =
            Button(this).apply {
                text = "Probe protected VM"
                setOnClickListener { probe(true) }
            }
        unprotectedButton =
            Button(this).apply {
                text = "Probe non-protected VM"
                setOnClickListener { probe(false) }
            }
        dumpButton =
            Button(this).apply {
                text = "Dump AVF API"
                setOnClickListener { dump() }
            }
        copyButton =
            Button(this).apply {
                text = "Copy output"
                setOnClickListener { copyOutput() }
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 32, 32, 32)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Veil VM Host"
                        textSize = 20f
                    }
                )
                addView(
                    TextView(this@MainActivity).apply {
                        val avf =
                            if (packageManager.hasSystemFeature(AVF_FEATURE)) "present"
                            else "missing"
                        text =
                            "AVF: $avf" +
                                "\nMANAGE_VIRTUAL_MACHINE: ${granted(MANAGE)}" +
                                "\nUSE_CUSTOM_VIRTUAL_MACHINE: ${granted(CUSTOM)}"
                        textSize = 14f
                    }
                )
                addView(protectedButton)
                addView(unprotectedButton)
                addView(dumpButton)
                addView(copyButton)
                addView(
                    ScrollView(this@MainActivity).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f,
                            )
                        addView(output)
                    }
                )
            }
        setContentView(column)
    }

    private fun probe(protected: Boolean) {
        if (running) return
        running = true
        setButtons(false)
        output.text = "running ${if (protected) "protected" else "non-protected"} probe...\n"
        Thread {
            val text = VmProbe.run(this, protected)
            runOnUiThread {
                output.text = text
                logOutput(text)
                running = false
                setButtons(true)
            }
        }
            .start()
    }

    private fun dump() {
        if (running) return
        running = true
        setButtons(false)
        output.text = "reading AVF API...\n"
        Thread {
            val text = VmProbe.dump(this)
            runOnUiThread {
                output.text = text
                logOutput(text)
                running = false
                setButtons(true)
            }
        }
            .start()
    }

    private fun setButtons(enabled: Boolean) {
        protectedButton.isEnabled = enabled
        unprotectedButton.isEnabled = enabled
        dumpButton.isEnabled = enabled
        copyButton.isEnabled = enabled
    }

    private fun copyOutput() {
        val text = output.text?.toString().orEmpty()
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Veil VM probe", text))
        Toast.makeText(this, "Copied ${text.length} characters", Toast.LENGTH_SHORT).show()
    }

    private fun logOutput(text: String) {
        // adb logcat -s VeilVmProbe, for when the screen text cannot be copied.
        text.lineSequence().forEach { Log.i("VeilVmProbe", it) }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val AVF_FEATURE = "android.software.virtualization_framework"
        private const val MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
        private const val CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    }
}
