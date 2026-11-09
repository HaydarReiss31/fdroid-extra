package org.fdroid.install

import android.content.Context
import android.content.Context.MODE_PRIVATE
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

/** Installs APKs as root via `su -c pm install`, bypassing PackageInstaller sessions. */
object RootInstaller {

  const val PREF_KEY_INSTALL_METHOD = "installMethod"

  private val log = KotlinLogging.logger {}

  fun isEnabled(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getString(PREF_KEY_INSTALL_METHOD, "default") == "root"

  const val PREF_KEY_SKIP_PREAPPROVAL = "skipPreapproval"

  fun skipPreapproval(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getBoolean(PREF_KEY_SKIP_PREAPPROVAL, false)

  /** Returns null on success, otherwise an error message. */
  suspend fun install(apk: File): String? =
    withContext(Dispatchers.IO) {
      try {
        val size = apk.length()
        val process =
          ProcessBuilder("su", "-c", "pm install -r -S $size").redirectErrorStream(true).start()
        // pm reads the APK from stdin when -S is given
        process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        log.info { "Root install of ${apk.name}: exit=$exit output=$output" }
        if (exit == 0 && output.contains("Success")) null else "Root install failed: $output"
      } catch (e: Exception) {
        log.error(e) { "Root install error: " }
        "Root install error: ${e::class.java.simpleName} ${e.message}"
      }
    }
}
