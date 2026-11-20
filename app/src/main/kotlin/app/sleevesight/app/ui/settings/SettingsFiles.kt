package app.sleevesight.app.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.settings.LoadMessage
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

// *Save to a file* and *Load from a file* (#815): the files the user picks, read and written.

/**
 * How the settings reach a file the user chose: [read] its text, [write] it, and [name] it as the
 * picker shows it (null if it cannot say). Through the [ContentResolver] in the app ([of]); a test
 * passes its own.
 */
class SettingsFiles(
    val read: suspend (Uri) -> String,
    val write: suspend (Uri, String) -> Unit,
    val name: suspend (Uri) -> String?,
) {
    companion object {
        /** The most of a file read: a settings file is a few kilobytes. */
        private const val MAX_BYTES = SettingsFile.MAX_BYTES

        /** The files the user picks, through [resolver], read and written off the main thread. */
        fun of(resolver: ContentResolver): SettingsFiles =
            SettingsFiles(
                read = { uri ->
                    withContext(Dispatchers.IO) {
                        val stream = resolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
                        val bytes = stream.use { readAtMost(it, MAX_BYTES) }
                        bytes.toString(Charsets.UTF_8)
                    }
                },
                write = { uri, text ->
                    withContext(Dispatchers.IO) {
                        val stream = openTruncated(resolver, uri) ?: throw IOException("No stream for $uri")
                        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                },
                name = { uri ->
                    withContext(Dispatchers.IO) {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }
                },
            )
    }
}

/**
 * [uri] opened to be written over: "wt", truncated, so a shorter text leaves nothing of an older one;
 * where the provider does not take that mode (some do not), "w", which a new file needs no more.
 */
private fun openTruncated(
    resolver: ContentResolver,
    uri: Uri,
): OutputStream? =
    try {
        resolver.openOutputStream(uri, "wt")
    } catch (_: IllegalArgumentException) {
        resolver.openOutputStream(uri, "w")
    } catch (_: FileNotFoundException) {
        resolver.openOutputStream(uri, "w")
    }

/** All of [stream], or an [IOException] if it is longer than [max] bytes. */
internal fun readAtMost(
    stream: InputStream,
    max: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) return out.toByteArray()
        out.write(buffer, 0, n)
        if (out.size() > max) throw IOException("Larger than a settings file")
    }
}

private const val BUFFER = 8 * 1024

/**
 * What *Save to a file* and *Load from a file* work with: [files], the [appVersion] a file is saved
 * with, the keys [missing] (their services a load leaves off), [today] for the name it suggests,
 * and [load], which loads a file's contents from the settings shown, saying the message given once
 * saved.
 */
internal class SettingsFileAccess(
    val files: SettingsFiles,
    val appVersion: String,
    val missing: Set<SecretId>,
    val load: (shown: Settings, contents: SettingsFile.Contents, message: LoadMessage) -> Deferred<Boolean>,
    val today: () -> LocalDate = LocalDate::now,
)
