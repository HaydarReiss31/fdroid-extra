package org.fdroid.install

import android.content.Context
import android.content.Context.MODE_PRIVATE
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

/** Installs APKs as root via `su -c pm install`, bypassing PackageInstaller sessions. */
object RootInstaller {

  const val PREF_KEY_ROOT_INSTALL = "rootInstall"

  private val log = KotlinLogging.logger {}

  fun isEnabled(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getBoolean(PREF_KEY_ROOT_INSTALL, false)

  const val PREF_KEY_SKIP_PREAPPROVAL = "skipPreapproval"

  fun skipPreapproval(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getBoolean(PREF_KEY_SKIP_PREAPPROVAL, false)

  private val suCandidates =
    listOf(
      "su",
      "/system/bin/su",
      "/system/xbin/su",
      "/sbin/su",
      "/debug_ramdisk/su",
      "/data/adb/ksu/bin/su",
      "/data/adb/ap/bin/su",
    )

  /** Tries several common su locations, since PATH differs between root solutions. */
  private fun startSu(command: String): Process {
    var last: Exception? = null
    for (su in suCandidates) {
      try {
        return ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
      } catch (e: java.io.IOException) {
        last = e
      }
    }
    throw last ?: java.io.IOException("su not found")
  }

  /** Returns null on success, otherwise an error message. */
  suspend fun install(apk: File): String? =
    withContext(Dispatchers.IO) {
      try {
        val size = apk.length()
        val process = startSu("pm install -r -S $size")
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
