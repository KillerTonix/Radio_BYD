package dev.wheelradio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics: every key / wheel event from every route is logged here and shown on screen,
 * so we can see in the car which route BYD actually uses for each button.
 * Also mirrored to logcat with tag "WheelRadio".
 */
object KeyLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun add(source: String, message: String) {
        val line = "${fmt.format(Date())}  [$source] $message"
        Log.i("WheelRadio", line)
        _entries.value = (listOf(line) + _entries.value).take(400) // room for a Bluetooth diagnostics dump
    }
}
