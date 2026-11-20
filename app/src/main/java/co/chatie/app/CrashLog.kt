package co.chatie.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last fatal stack trace on disk so the app can show it on the next
 * launch. A phone has no logcat to read, so a crash that happens before any UI
 * appears is otherwise invisible — this makes it reportable.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    /** Installs the handler. Call this first, before anything else can throw. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(thread: Thread?, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        return buildString {
            appendLine("chatie ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            thread?.let { appendLine("thread: ${it.name}") }
            appendLine()
            append(stack.toString())
        }
    }

    fun write(context: Context, text: String) {
        File(context.filesDir, FILE).writeText(text)
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.isFile }?.let {
            runCatching { it.readText() }.getOrNull()?.takeIf(String::isNotBlank)
        }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
