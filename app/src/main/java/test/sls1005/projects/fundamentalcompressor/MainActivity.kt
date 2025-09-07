package test.sls1005.projects.fundamentalcompressor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URI
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme

internal enum class CompressionOrArchiveFormat(
    internal val fileExtension: String,
    internal val mimeType: String
) {
    BR("br", "application/x-br"),
    BZIP2("bz2", "application/x-bzip2"),
    GZIP("gz", "application/gzip"),
    LZ4("lz4", "application/x-lz4"),
    LZMA("lzma", "application/x-lzma"),
    //RAR("rar", "application/vnd.rar"),
    SEVEN_Z("7z", "application/x-7z-compressed"),
    TAR("tar", "application/x-tar"),
    XZ("xz", "application/x-xz"),
    Z("Z", "application/x-compress"),
    ZIP("zip", "application/zip"),
    ZSTD("zst", "application/zstd")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createLogChannelIfApplicable(this)
        setContent {
            FundamentalCompressorTheme {
                val requestNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    ) {
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        CompressorActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier
                                .sizeIn(minWidth = 400.dp, minHeight = 100.dp)
                                .padding(10.dp)
                        ) {
                            Text(
                                "Compress",
                                fontSize = 24.sp,
                                lineHeight = 26.sp,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        DecompressorActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier
                                .sizeIn(minWidth = 400.dp, minHeight = 100.dp)
                                .padding(10.dp)
                        ) {
                            Text(
                                "Decompress",
                                fontSize = 24.sp,
                                lineHeight = 26.sp,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        DocumentationActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier
                                .sizeIn(minWidth = 400.dp, minHeight = 100.dp)
                                .padding(10.dp)
                        ) {
                            Text(
                                "Documentation",
                                fontSize = 24.sp,
                                lineHeight = 26.sp,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        delay(50)
                        val file = File((this@MainActivity).filesDir, "config.bin")
                        val requestPromptIsEverShown = (
                            if (file.exists()) {
                                var code = 0
                                try {
                                    file.inputStream().use {
                                        withContext(Dispatchers.IO) {
                                            if (it.available() > 0) {
                                                code = it.read()
                                            }
                                        }
                                    }
                                } catch (_: Exception) {

                                }
                                withContext(Dispatchers.Default) {
                                    if (code != -1) {
                                        (code.toUByte().toInt() and 1) == 1
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                false
                            }
                        )
                        if (!requestPromptIsEverShown) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            file.writeBytes(byteArrayOf((1).toUByte().toByte()))
                        }
                    }
                }
            }
        }
    }
}


internal inline fun CompressionOrArchiveFormat.isArchiveFormat(): Boolean { // there are fewer archive format than compression format, so checking for the former is more efficient
    return when (this) {
        CompressionOrArchiveFormat.SEVEN_Z, CompressionOrArchiveFormat.TAR, CompressionOrArchiveFormat.ZIP -> true
        else -> false
    }
}

internal inline fun toDisplayName(format: CompressionOrArchiveFormat): String {
    return when(format) {
        CompressionOrArchiveFormat.BR -> "Brotli (.br)"
        CompressionOrArchiveFormat.BZIP2 -> "BZIP2 (.bz2)"
        CompressionOrArchiveFormat.GZIP -> "GZIP (.gz)"
        CompressionOrArchiveFormat.LZ4 -> "LZ4 (.lz4)"
        CompressionOrArchiveFormat.LZMA -> "LZMA (.lzma)"
        CompressionOrArchiveFormat.SEVEN_Z -> "7z (.7z)"
        CompressionOrArchiveFormat.TAR -> "TAR (.tar)"
        CompressionOrArchiveFormat.XZ -> "XZ (.xz)"
        CompressionOrArchiveFormat.Z -> "Z (.Z)"
        CompressionOrArchiveFormat.ZIP -> "ZIP (.zip)"
        CompressionOrArchiveFormat.ZSTD -> "Zstandard (.zst)"
    }
}

internal inline fun getDisplayNameOf(format: CompressionOrArchiveFormat?, isTarball: Boolean = false, ctx: Context? = null): String {
    return if (isTarball) {
        (format?.fileExtension ?: "*").let { ext ->
            "Tarball (.tar.$ext)"
        }
    } else {
        format?.let { toDisplayName(it) } ?: ctx?.getString(R.string.unspecified) ?: ""
    }
}

internal inline fun inferFileNameFromContentUri(uri: Uri, ctx: Context): String? {
    return DocumentFile.fromSingleUri(ctx, uri)?.name
}

internal inline fun showMsg(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

internal inline fun createLogChannelIfApplicable(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannel("log", ctx.getString(R.string.log), NotificationManager.IMPORTANCE_LOW)
        )
    }
}

internal inline fun showNotificationInLogChannel(ctx: Context, notificationID: Int, progress: Double, title: String, firstLine: String, fullText: String = "", intentForActivity: Intent? = null) { // if `progress` > 1.0, the progress is undetermined; if `progress` < 0.0, it is not used.
    NotificationCompat.Builder(
        ctx, "log"
    ).setSmallIcon(
        R.drawable.notification_icon
    ).setContentTitle(
        title
    ).setContentText(
        firstLine
    ).setOnlyAlertOnce(
        true
    ).setPriority(
        NotificationCompat.PRIORITY_DEFAULT
    ).also {
        if (progress > 1.0) {
            it.setProgress(100, 100, true)
        } else if (progress >= 0.0) {
            it.setProgress(1000000000, (1000000000 * progress).toInt(), false)
        } // else: do nothing
        if (fullText.isNotEmpty()) {
            it.setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
        }
        if (intentForActivity != null) {
            it.setContentIntent(
                PendingIntent.getActivity(ctx, 0, intentForActivity, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ).setAutoCancel(
                true
            )
        }
        NotificationManagerCompat.from(ctx).apply {
            if (areNotificationsEnabled()) {
                notify(notificationID, it.build())
            } // else: do nothing
        }
    }
}

internal inline fun showNotificationAboutUnknownError(ctx: Context, notificationID: Int, intentForActivity: Intent? = null) {
    showNotificationInLogChannel(ctx, notificationID, -1.0, ctx.getString(R.string.error), ctx.getString(R.string.error0), "", intentForActivity)
}

internal inline fun showErrorMsgAndNotifyUserAboutUnknownError(ctx: Context, notificationID: Int, intentForActivity: Intent? = null) {
    showMsg(ctx, ctx.getString(R.string.error0))
    showNotificationAboutUnknownError(ctx, notificationID, intentForActivity)
}

internal inline fun showNotificationAboutCancelledTask(ctx: Context, notificationID: Int, intentForActivity: Intent? = null) {
    showNotificationInLogChannel(ctx, notificationID, -1.0, ctx.getString(R.string.canceled), ctx.getString(R.string.operation_is_canceled), "", intentForActivity)
}

internal inline fun isOfSupportedScheme(uri: Uri): Boolean {
    return uri.scheme?.let { scheme ->
        arrayOf("file", "content").any { it.equals(scheme, ignoreCase = true) }
    } ?: false
}

internal inline fun areOfSupportedScheme(vararg uris: Uri): Boolean {
    return !(uris.any { !isOfSupportedScheme(it) })
}

internal fun isFileUriOfMyFileOrDir(ctx: Context, uri: Uri): Boolean {
    if (uri.scheme?.equals("file", ignoreCase = true) ?: false) {
        val path = Paths.get(URI(uri.toString())).toAbsolutePath().normalize()
        val myDirs = ArrayList(
            arrayOf(ctx.cacheDir, ctx.filesDir).map { it.toPath().toAbsolutePath().normalize() }
        )
        val n = path.nameCount
        /*
        if (myDirs.size == 0) {
            return false
        }
        */
        if (n > 0) {
            for (i in 0 .. (n - 1)) {
                var j = 0
                while (j < myDirs.size) {
                    var shouldExcludeCurrent = false
                    if (i < myDirs[j].nameCount) {
                        if (myDirs[j].getName(i) != path.getName(i)) {
                            if (myDirs.size == 1) {
                                return false
                            } else {
                                shouldExcludeCurrent = true
                            }
                        }
                    } else if (myDirs[j].nameCount == 0) {
                        shouldExcludeCurrent = true
                    } else {
                        return true
                    }
                    if (shouldExcludeCurrent) {
                        myDirs.removeAt(j)
                    } else {
                        j += 1
                    }
                }
            }
            return myDirs.any { (it.nameCount <= n) && (it.nameCount > 0) }
        } else {
            return myDirs.any { it == path }
        }
    } else {
        return false
    }
}

internal inline fun noneOfThemIsFileUriOfMyFileOrDir(ctx: Context, vararg uris: Uri): Boolean {
    return !(uris.any { isFileUriOfMyFileOrDir(ctx, it) })
}
