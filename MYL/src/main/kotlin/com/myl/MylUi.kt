package com.myl

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The "Add a link" paste box. The list itself lives on the Pi (the
 * MyList server holds links.txt); this dialog just POSTs the pasted
 * address to the Pi's /add endpoint, the same one its web page uses.
 * The current activity is found by the same ActivityThread
 * reflection the KM and XD plugins use for their browser dialogs.
 */
object MylUi {

    private fun currentActivity(): Activity? {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return null
            val field = thread.javaClass.getDeclaredField("mActivities")
            field.isAccessible = true
            val activities = field.get(thread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (pausedField.getBoolean(record)) continue
                val activityField =
                    record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                (activityField.get(record) as? Activity)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun showAddDialog(piBase: String) {
        val activity = currentActivity() ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                val input = EditText(activity).apply {
                    hint = "Paste a HubCloud or video link"
                    setSingleLine(false)
                }
                AlertDialog.Builder(activity)
                    .setTitle("Add to MyList")
                    .setView(input)
                    .setPositiveButton("Add") { _, _ ->
                        val link = input.text.toString().trim()
                        if (link.startsWith("http")) {
                            Thread {
                                val ok = postToPi(piBase, link)
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        Toast.makeText(
                                            activity,
                                            if (ok) {
                                                "Added — reopen MyList to see it"
                                            } else {
                                                "Could not reach the Pi"
                                            },
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        // toast is best effort
                                    }
                                }
                            }.start()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                // No activity to host the dialog; the web page at the
                // Pi's address remains the way to add links.
            }
        }
    }

    private fun postToPi(piBase: String, link: String): Boolean {
        return try {
            val conn = URL("$piBase/add").openConnection()
                as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )
            val body = "url=" + URLEncoder.encode(link, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }
}
