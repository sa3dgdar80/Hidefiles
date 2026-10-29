package com.example.hidefiles

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = File(filesDir, "crash.txt")
        if (f.exists()) {
            val tv = TextView(this)
            tv.text = f.readText()
            tv.textSize = 11f
            tv.setTextIsSelectable(true)
            tv.setPadding(24, 24, 24, 24)

            val btn = Button(this)
            btn.text = "Delete report and retry"
            btn.setOnClickListener {
                f.delete()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }

            val scroll = ScrollView(this)
            scroll.addView(tv)
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.addView(btn)
            layout.addView(scroll)
            setContentView(layout)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
