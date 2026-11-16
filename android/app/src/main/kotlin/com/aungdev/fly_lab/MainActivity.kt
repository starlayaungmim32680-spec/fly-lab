package com.aungdev.fly_lab

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 16 Nov 2026 (Fly Lab closed itself on open, no cable for adb):
        // a Java crash is saved and shown in the Lab read-out on the next
        // start ("last crash: ..."). Native crashes can't be caught here -
        // LabCamera handles the GPU one with its own flag.
        val prefs = applicationContext.getSharedPreferences("flylab", Context.MODE_PRIVATE)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                var c: Throwable = e
                while (c.cause != null && c.cause !== c) c = c.cause!!
                val where = c.stackTrace.firstOrNull()?.toString() ?: ""
                prefs.edit().putString("lastCrash",
                    "${c.javaClass.simpleName}: ${c.message ?: ""} @ $where".take(220))
                    .commit()
            } catch (_: Throwable) {}
            Log.e("FlyLab", "crash", e)
            old?.uncaughtException(t, e)
        }
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Channel "flylab/camera" - LabCamera.kt.
        LabCameraRegistrar.register(this, flutterEngine)
        // Channel "flylab/editor" - PhotoVideoEngine.kt (editor step 1).
        PhotoVideoRegistrar.register(this, flutterEngine)
    }
}
