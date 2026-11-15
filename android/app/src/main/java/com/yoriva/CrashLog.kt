package com.yoriva
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
object CrashLog {
    private const val TAG = "YorivaCrash"
    private const val FILE_NAME = "yoriva-crash.txt"
    fun install(context: Context) {
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, throwable)
            } catch (_: Throwable) {
            }
            try {
                showCrashDialog(appContext, throwable)
            } catch (_: Throwable) {
                prev?.uncaughtException(thread, throwable)
                    ?: throwable.printStackTrace()
            }
        }
    }
    private fun showCrashDialog(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val preview = sw.toString().lines().take(15).joinToString("\n")
        try {
            AlertDialog.Builder(context)
                .setTitle("Yoriva Crash")
                .setMessage("The app hit an unexpected error and needs to close.\n\n$preview")
                .setCancelable(false)
                .setPositiveButton("Send to Developers") { _, _ ->
                    try {
                        val title = "Crash: " + (throwable::class.simpleName ?: "Unknown")
                        val body = "## Crash report\n\n" +
                            "Model: ${Build.MODEL}\nSDK: ${Build.VERSION.SDK_INT}\n" +
                            "Thread: ${Thread.currentThread().name}\n\n" +
                            "### Stack trace\n\n```\n$sw\n```"
                        val url = "https://github.com/berkchy/nexora/issues/new?title=" +
                            java.net.URLEncoder.encode(title, "UTF-8") +
                            "&body=" + java.net.URLEncoder.encode(body, "UTF-8")
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(1)
                }
                .setNegativeButton("Close") { _, _ ->
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(1)
                }
                .show()
        } catch (_: Throwable) {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
    fun candidateFiles(context: Context): List<File> = buildList {
        add(File(context.getExternalFilesDir(null), FILE_NAME))
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let { add(File(it, FILE_NAME)) }
    }
    private fun write(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = "=== $stamp pid=${android.os.Process.myPid()} ===\n" +
            "model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n" +
            "thread=${Thread.currentThread().name}\n$sw\n"
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val resolver = context.contentResolver
                runCatching {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(FILE_NAME),
                        null,
                    )?.use { c -> while (c.moveToNext()) resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns._ID} = ?", arrayOf(c.getInt(0).toString())) }
                }
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            }
        }
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(body)
        }
        runCatching {
            File(context.getExternalFilesDir(null), FILE_NAME).writeText(body)
        }
        Log.e(TAG, sw.toString())
    }
}