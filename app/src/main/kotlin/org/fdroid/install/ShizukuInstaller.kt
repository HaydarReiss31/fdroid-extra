package org.fdroid.install

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import rikka.shizuku.Shizuku

/** Installs APKs through Shizuku's shell via `pm install`, bypassing PackageInstaller sessions. */
object ShizukuInstaller {

  const val PREF_KEY_SHIZUKU_INSTALL = "shizukuInstall"

  private val log = KotlinLogging.logger {}

  fun isEnabled(context: Context): Boolean =
    context
      .getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
      .getBoolean(PREF_KEY_SHIZUKU_INSTALL, false)

  /** Returns null on success, otherwise an error message. */
  suspend fun install(apk: File): String? =
    withContext(Dispatchers.IO) {
      try {
        if (!Shizuku.pingBinder()) return@withContext "Shizuku is not running"
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
          if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(0)
          return@withContext "Shizuku permission not granted, allow it and try again"
        }
        val size = apk.length()
        // newProcess() is private in newer Shizuku API versions, so call it via reflection
        val method =
          Shizuku::class
            .java
            .getDeclaredMethod(
              "newProcess",
              Array<String>::class.java,
              Array<String>::class.java,
              String::class.java,
            )
        method.isAccessible = true
        val cmd = arrayOf("sh", "-c", "pm install -r -S $size")
        val process = method.invoke(null, cmd, null, null) as Process
        process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
        val output = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        log.info { "Shizuku install of ${apk.name}: exit=$exit output=$output error=$error" }
        if (exit == 0 && output.contains("Success")) null
        else "Shizuku install failed: $output $error".trim()
      } catch (e: Exception) {
        log.error(e) { "Shizuku install error: " }
        "Shizuku install error: ${e::class.java.simpleName} ${e.message}"
      }
    }
}
