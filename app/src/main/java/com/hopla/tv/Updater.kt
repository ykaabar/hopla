package com.hopla.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** A newer Hopla published on GitHub: the APK of the latest release, named "hopla-<versionCode>.apk". */
data class Update(val versionCode: Long, val name: String, val apk: String)

private val APK = Regex("""^hopla-(\d+)\.apk$""")

/** The latest release, when it is newer than [current]; null otherwise. */
fun parseRelease(json: String, current: Long): Update? {
    val r = JSONObject(json)
    val assets = r.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val a = assets.optJSONObject(i) ?: continue
        val code = APK.find(a.optString("name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
        return if (code > current) Update(code, r.optString("tag_name").removePrefix("v"), a.optString("browser_download_url")) else null
    }
    return null
}

/** Blocking: asks GitHub for the latest release of this app's public repository. */
fun checkUpdate(context: Context): Update? {
    val current = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    }
    val json = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").bufferedReader().use { it.readText() }
    return parseRelease(json, current)
}

/** Blocking download; then hands the APK to the system installer (the household confirms with one press). */
fun installUpdate(context: Context, update: Update) {
    // Android asks once whether Hopla may install apps; without it the installer would refuse silently.
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        toast(context, "Autorise Hopla à installer des applis, puis relance la mise à jour depuis le menu.")
        return
    }
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val apk = File(dir, "hopla.apk")
    val tmp = File(dir, "hopla.apk.tmp")
    open(update.apk).use { input -> tmp.outputStream().use { input.copyTo(it) } }
    if (!tmp.renameTo(apk)) throw java.io.IOException("écriture impossible")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun toast(context: Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
}
