package test.sls1005.projects.fundamentalcompressor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import java.nio.charset.Charset
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.slice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme
import test.sls1005.projects.fundamentalcompressor.ui.CardWithTitle
import test.sls1005.projects.fundamentalcompressor.ui.PasswordInputField
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyCentrally
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyFromStart
import test.sls1005.projects.fundamentalcompressor.util.bitmasking.MaskableInt
import test.sls1005.projects.fundamentalcompressor.util.bitmasking.maskableIntOf
import test.sls1005.projects.fundamentalcompressor.util.functions.concatenateSmallNumberOfStringsOnAnotherThread
import test.sls1005.projects.fundamentalcompressor.util.functions.jobIsCompletedOrCancelled
import test.sls1005.projects.fundamentalcompressor.util.functions.omitAfterFirstFewRecordsConcurrently
import test.sls1005.projects.fundamentalcompressor.util.functions.omitBeforeLastFewRecordsConcurrently
import test.sls1005.projects.fundamentalcompressor.util.CallbackInvokerInputStream

private const val DECOMPRESSOR_MODE_AUTO = 1
private const val DECOMPRESSOR_MODE_ASSUME_TARBALL = 2
private const val DECOMPRESSOR_MODE_TARBALL_IS_INFERRED = 4

class DecompressorActivity : ComponentActivity() {
    private data class FileFormatInferenceResult(val format: CompressionOrArchiveFormat, val isTarball: Boolean)
    private enum class LinkingStrategy {
        Ignore, UsePlaceholders
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri = intent?.getData() // ?: null; this variable is needed; it initializes the state of uri to the initial state, and it is used to infer the format.
        val initiallyInferred = initialUri?.let { inferFileFormat(initialUri) }  // ?: null
        val formatWasInferredInitially = (initiallyInferred != null)
        val initiallyInferredFormat = initiallyInferred?.format //?: null
        val initiallyWasTarball = initiallyInferred?.isTarball ?: false
        createLogChannelIfApplicable(this)
        setContent {
            FundamentalCompressorTheme {
                var selectedOrInferredFormat by remember { mutableStateOf(initiallyInferredFormat ?: CompressionOrArchiveFormat.ZIP) }
                var formatIsManuallySelected by remember { mutableStateOf(false) }
                var formatWasInferred by remember { mutableStateOf(formatWasInferredInitially) } // This is necessary; it is used to make the auto mode unavailable. It must be mutable.
                var inputFileUri by remember { mutableStateOf<Uri?>(initialUri) }
                var decompressorMode by remember {
                    mutableStateOf(
                        if (formatWasInferredInitially) {
                            if (initiallyWasTarball) {
                                maskableIntOf(DECOMPRESSOR_MODE_TARBALL_IS_INFERRED, DECOMPRESSOR_MODE_ASSUME_TARBALL)
                            } else {
                                MaskableInt(0)
                            }
                        } else {
                            MaskableInt(DECOMPRESSOR_MODE_AUTO)
                        }
                    )
                }
                var shouldUsePassword by remember { mutableStateOf(false) }
                val password = rememberTextFieldState()
                var shouldOverrideFileNameEncoding by remember { mutableStateOf(false) }
                var selectedEncodingForOverriding by remember { mutableStateOf(Charset.defaultCharset()) }
                var linkStrategy by remember { mutableStateOf(LinkingStrategy.Ignore) }
                val autoMode by remember {
                    derivedStateOf {
                        decompressorMode.bitsSet(DECOMPRESSOR_MODE_AUTO)
                    }
                }
                val assumesTarball by remember {
                    derivedStateOf {
                        decompressorMode.bitsSet(DECOMPRESSOR_MODE_ASSUME_TARBALL)
                    }
                }
                val shouldCreateNoMoreThanOneFile by remember {
                    derivedStateOf {
                        if (assumesTarball) {
                            false
                        } else if (autoMode && decompressorMode.exactlyMatches(DECOMPRESSOR_MODE_AUTO)) {
                            true
                        } else {
                            (!selectedOrInferredFormat.isArchiveFormat())
                        }
                    }
                }
                var currentTask by remember { mutableStateOf<Job?>(null) }
                val currentProgress = remember { mutableFloatStateOf(-1.0f) } // No "by" here.
                val taskStatus = remember { mutableIntStateOf(0) } // 0: initial; 1: running task; 2: completed; -1: cancelled; -2: error; -3: password required
                val isRunningTask by remember {
                    derivedStateOf {
                        taskStatus.intValue == 1
                    }
                }
                val taskIsCompleted by remember {
                    derivedStateOf {
                        taskStatus.intValue == 2
                    }
                }
                val requiresPassword by remember {
                    derivedStateOf {
                        taskStatus.intValue == -3
                    }
                }
                val inputFileName by remember {
                    derivedStateOf {
                        inputFileUri?.let { uri ->
                            inferFileNameFromContentUri(uri, this@DecompressorActivity) // ?: null
                        } ?: ""
                    }
                }
                val inputFileSize by remember {
                    derivedStateOf {
                        inputFileUri?.let {
                            DocumentFile.fromSingleUri(this@DecompressorActivity, it)?.length() // ?: null
                        } ?: -1L
                    }
                }
                val inputFileSizeStr by remember {
                    derivedStateOf {
                        if (inputFileSize == -1L) {
                            getString(R.string.unknown)
                        } else {
                            Formatter.formatShortFileSize(this@DecompressorActivity, inputFileSize)
                        }
                    }
                }
                val selectInputFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { newUri ->
                    if (newUri != null) {
                        inputFileUri = newUri
                        inferFileFormat(newUri)?.also {
                            val (format, isTarball) = it
                            formatWasInferred = true // These lines are needed because the user may select files many times
                            decompressorMode = if (isTarball) {
                                maskableIntOf(DECOMPRESSOR_MODE_TARBALL_IS_INFERRED, DECOMPRESSOR_MODE_ASSUME_TARBALL)
                            } else {
                                MaskableInt(0)
                            }
                            selectedOrInferredFormat = format
                        } ?: run {
                            formatWasInferred = false
                            decompressorMode = MaskableInt(DECOMPRESSOR_MODE_AUTO)
                        }
                        formatIsManuallySelected = false
                        if (jobIsCompletedOrCancelled(currentTask)) {
                            currentTask = null
                            taskStatus.intValue = 0
                        }
                    }
                }
                val createOutputFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), // use this if the output is only one file
                    if (decompressorMode.exactlyMatches(DECOMPRESSOR_MODE_AUTO)) {
                        { outputFileUri -> // null means the user wants to cancel
                            currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus)
                        }
                    } else {
                        when (selectedOrInferredFormat) {
                            CompressionOrArchiveFormat.BR -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.BR)
                            }
                            CompressionOrArchiveFormat.BZIP2 -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.BZIP2)
                            }
                            CompressionOrArchiveFormat.GZIP -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.GZIP)
                            }
                            CompressionOrArchiveFormat.LZ4 -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.LZ4)
                            }
                            CompressionOrArchiveFormat.LZMA -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.LZMA)
                            }
                            CompressionOrArchiveFormat.XZ -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.XZ)
                            }
                            CompressionOrArchiveFormat.Z -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.Z)
                            }
                            CompressionOrArchiveFormat.ZSTD -> { outputFileUri ->
                                currentTask = checkInputAndStartDecompressing(inputFileUri, outputFileUri, currentProgress, taskStatus, CompressionOrArchiveFormat.ZSTD)
                            }
                            else -> { _-> }
                        }
                    }
                )
                val selectOutputDirAndCreateOutputFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), // only invoked for archive format or tarball, and for the latter, only if requested.
                    // Consideration: Under SAF, overwriting files is generally impossible with `create()`, and the algorithm which this app uses does not process the case that a directory is not under the created directory. So I don't think "zip path traversal" would be a problem here. But what if the path points to the app's app-specific directory? That would be a problem. However, we would never know, even if it does, as the path is abstract. Issue solved. But is a symlink attack still possible under SAF? I don't think so.
                    if (autoMode) {
                        { outputDirUri ->
                            currentTask = checkInputAndStartExtractingFromTarball(
                                inputFileUri,
                                outputDirUri,
                                currentProgress,
                                taskStatus,
                                fileNameEncoding = if (shouldOverrideFileNameEncoding) {
                                    selectedEncodingForOverriding
                                } else {
                                    null
                                },
                                linkStrategy = linkStrategy
                            )
                        }
                    } else {
                        when (selectedOrInferredFormat) {
                            CompressionOrArchiveFormat.BR -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.BR,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.BZIP2 -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.BZIP2,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.GZIP -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.GZIP,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.LZ4 -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.LZ4,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.LZMA -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.LZMA,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.XZ -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.XZ,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.Z -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.Z,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.ZSTD -> { outputDirUri ->
                                currentTask = checkInputAndStartExtractingFromTarball(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.ZSTD,
                                    if (shouldOverrideFileNameEncoding) {
                                        selectedEncodingForOverriding
                                    } else {
                                        null
                                    },
                                    linkStrategy
                                )
                            }
                            CompressionOrArchiveFormat.SEVEN_Z -> { outputDirUri ->
                                currentTask = checkInputAndStartDecompressing(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.SEVEN_Z,
                                    password = if (shouldUsePassword || requiresPassword) { password.text.toString() } else { null }
                                )
                            }
                            CompressionOrArchiveFormat.TAR -> { outputDirUri ->
                                currentTask = checkInputAndStartDecompressingOrUnarchiving(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.TAR,
                                    linkStrategy = linkStrategy,
                                    fileNameEncoding = if (shouldOverrideFileNameEncoding) { selectedEncodingForOverriding } else { null }
                                )
                            }
                            CompressionOrArchiveFormat.ZIP -> { outputDirUri ->
                                currentTask = checkInputAndStartDecompressing(
                                    inputFileUri,
                                    outputDirUri,
                                    currentProgress,
                                    taskStatus,
                                    CompressionOrArchiveFormat.ZIP,
                                    fileNameEncoding = if (shouldOverrideFileNameEncoding) { selectedEncodingForOverriding } else { null }
                                )
                            }
                            //else -> { _-> }
                        }
                    }
                )
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    ) {
                        inputFileUri?.also { inputUriLocal ->
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                            ) {
                                PlaceVerticallyCentrally {
                                    if (isRunningTask) {
                                        Text(
                                            stringResource(
                                                if (shouldCreateNoMoreThanOneFile) {
                                                    R.string.file_is_being_decompressed 
                                                } else {
                                                    R.string.file_is_being_unarchived
                                                }
                                            ),
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp)
                                        )
                                        Text(
                                            inputFileName,
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 5.dp)
                                        )
                                        if ((currentProgress.floatValue.let { (0.0f <= it) && (it <= 1.0f) }) && (selectedOrInferredFormat != CompressionOrArchiveFormat.SEVEN_Z)) {
                                            LinearProgressIndicator({ currentProgress.floatValue }, modifier = Modifier.padding(start = 25.dp, end = 25.dp, top = 5.dp, bottom = 5.dp))
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.padding(start = 25.dp, end = 25.dp, top = 5.dp, bottom = 5.dp))
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                currentTask?.apply {
                                                    if (isActive) {
                                                        cancel()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.cancel), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    } else {
                                        Column(modifier = Modifier.padding(bottom = 5.dp)) {
                                            Text(stringResource(id = R.string.file_has_been_selected), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                            TextButton(
                                                onClick = {
                                                    selectInputFile.launch("application/*")
                                                },
                                                shape = RectangleShape,
                                                modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = if (taskIsCompleted) { 10.dp } else { 2.dp })
                                            ) {
                                                Text(stringResource(id = R.string.file_name_and_compressed_size, inputFileName, inputFileSizeStr), fontSize = 24.sp, lineHeight = 26.sp)
                                            }
                                            if (!taskIsCompleted) {
                                                Text(stringResource(id = R.string.decompressor_info1), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 10.dp))
                                            }
                                            if (!formatWasInferred) {
                                                Text(
                                                    stringResource(id = R.string.error1),
                                                    fontSize = 24.sp,
                                                    modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (taskIsCompleted) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                ) {
                                    PlaceVerticallyCentrally {
                                        Text(stringResource(id = R.string.task_is_completed), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        OutlinedButton(
                                            onClick = { (this@DecompressorActivity).finish() },
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.exit), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    }
                                }
                            }
                            CardWithTitle(stringResource(id = R.string.options)) {
                                val formatDisplayName by remember {
                                    derivedStateOf {
                                        getDisplayNameOf(
                                            if (autoMode) {
                                                null
                                            } else {
                                                selectedOrInferredFormat
                                            },
                                            isTarball = assumesTarball,
                                            this@DecompressorActivity
                                        )
                                    }
                                }
                                var menuExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = menuExpanded,
                                    onExpandedChange = { menuExpanded = it },
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 10.dp)
                                ) {
                                    OutlinedTextField(
                                        formatDisplayName,
                                        readOnly = true,
                                        onValueChange = { /* Empty */ },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                        label = { Text(stringResource(id = R.string.file_format)) },
                                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        val couldNotInferFormat = !formatWasInferred
                                        if (couldNotInferFormat) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(id = R.string.unspecified), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    decompressorMode = MaskableInt(DECOMPRESSOR_MODE_AUTO)
                                                    formatIsManuallySelected = true
                                                    menuExpanded = false
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                }
                                            )
                                        }
                                        (remember {
                                            arrayOf(
                                                CompressionOrArchiveFormat.BR,
                                                CompressionOrArchiveFormat.BZIP2,
                                                CompressionOrArchiveFormat.GZIP,
                                                CompressionOrArchiveFormat.LZMA,
                                                CompressionOrArchiveFormat.LZ4,
                                                CompressionOrArchiveFormat.XZ,
                                                CompressionOrArchiveFormat.Z,
                                                CompressionOrArchiveFormat.ZSTD,
                                                CompressionOrArchiveFormat.SEVEN_Z,
                                                CompressionOrArchiveFormat.TAR,
                                                CompressionOrArchiveFormat.ZIP
                                            )
                                        }).forEach {
                                            DropdownMenuItem(
                                                text = { Text(toDisplayName(it), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    selectedOrInferredFormat = it
                                                    decompressorMode = decompressorMode.withFlagsUnset(DECOMPRESSOR_MODE_AUTO, DECOMPRESSOR_MODE_ASSUME_TARBALL) // The result can be 0 or DECOMPRESSOR_MODE_TARBALL_IS_INFERRED
                                                    formatIsManuallySelected = true
                                                    menuExpanded = false
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                }
                                            )
                                        }
                                        if (couldNotInferFormat) {
                                            DropdownMenuItem(
                                                text = { Text(getDisplayNameOf(null, isTarball = true), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    decompressorMode = maskableIntOf(DECOMPRESSOR_MODE_AUTO, DECOMPRESSOR_MODE_ASSUME_TARBALL)
                                                    formatIsManuallySelected = true
                                                    menuExpanded = false
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                }
                                            )
                                        }
                                        (remember {
                                            arrayOf(
                                                CompressionOrArchiveFormat.BR,
                                                CompressionOrArchiveFormat.BZIP2,
                                                CompressionOrArchiveFormat.GZIP,
                                                CompressionOrArchiveFormat.LZMA,
                                                CompressionOrArchiveFormat.LZ4,
                                                CompressionOrArchiveFormat.XZ,
                                                CompressionOrArchiveFormat.Z,
                                                CompressionOrArchiveFormat.ZSTD
                                            )
                                        }).forEach {
                                            DropdownMenuItem(
                                                text = { Text(getDisplayNameOf(it, isTarball = true), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    selectedOrInferredFormat = it
                                                    decompressorMode = decompressorMode.withBitsSet(DECOMPRESSOR_MODE_ASSUME_TARBALL)
                                                    formatIsManuallySelected = true
                                                    menuExpanded = false
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                if (formatWasInferred && (!formatIsManuallySelected)) {
                                    Text(stringResource(id = R.string.decompressor_info3), fontSize = 16.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 0.dp, bottom = 10.dp))
                                }
                                PlaceVerticallyFromStart {
                                    if (decompressorMode.contains(DECOMPRESSOR_MODE_TARBALL_IS_INFERRED) && (!selectedOrInferredFormat.isArchiveFormat())) { // Without tjis check, there will be strange options like ".tar.tar"
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = !assumesTarball,
                                                onCheckedChange = { checked ->
                                                    decompressorMode = if (checked) { // if checked, decompress only (and produce an uncompressed unextracted archive); if not checked, decompress and extract
                                                        decompressorMode.withBitsUnset(DECOMPRESSOR_MODE_ASSUME_TARBALL)
                                                    } else {
                                                        decompressorMode.withBitsSet(DECOMPRESSOR_MODE_ASSUME_TARBALL)
                                                    }
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !isRunningTask,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(stringResource(id = R.string.decompressor_option_decompress_only),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp
                                            )
                                        }
                                        Text(stringResource(id = R.string.decompressor_info2), fontSize = 16.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 0.dp, bottom = 10.dp))
                                    }
                                }
                                PlaceVerticallyFromStart {
                                    val shouldAllowOverridingFileNameEncoding by remember {
                                        derivedStateOf {
                                            if (assumesTarball) {
                                                true
                                            } else if (autoMode) {
                                                false
                                            } else {
                                                when (selectedOrInferredFormat) {
                                                    CompressionOrArchiveFormat.TAR, CompressionOrArchiveFormat.ZIP -> true
                                                    else -> false
                                                }
                                            }
                                        }
                                    }
                                    if (shouldAllowOverridingFileNameEncoding) {
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = shouldOverrideFileNameEncoding,
                                                onCheckedChange = {
                                                    shouldOverrideFileNameEncoding = it
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !isRunningTask,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(
                                                stringResource(id = R.string.decompressor_option_override_encoding),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp
                                            )
                                        }
                                        if (shouldOverrideFileNameEncoding) {
                                            var menuExpanded by remember { mutableStateOf(false) }
                                            ExposedDropdownMenuBox(
                                                expanded = menuExpanded,
                                                onExpandedChange = { menuExpanded = it },
                                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 10.dp)
                                            ) {
                                                OutlinedTextField(
                                                    selectedEncodingForOverriding.displayName(),
                                                    readOnly = true,
                                                    onValueChange = { /* Empty */ },
                                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                                    label = { Text(stringResource(id = R.string.encoding)) },
                                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                                )
                                                ExposedDropdownMenu(
                                                    expanded = menuExpanded,
                                                    onDismissRequest = { menuExpanded = false }
                                                ) {
                                                    Charset.availableCharsets().forEach {
                                                        val name = it.key
                                                        val encoding = it.value
                                                        DropdownMenuItem(
                                                            text = { Text(name, fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(5.dp)) },
                                                            onClick = {
                                                                selectedEncodingForOverriding = encoding
                                                                menuExpanded = false
                                                                if (jobIsCompletedOrCancelled(currentTask)) {
                                                                    currentTask = null
                                                                    taskStatus.intValue = 0
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                PlaceVerticallyFromStart {
                                    if (decompressorMode.bitsNotSet(DECOMPRESSOR_MODE_AUTO) && (selectedOrInferredFormat == CompressionOrArchiveFormat.SEVEN_Z)) {
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = (shouldUsePassword || requiresPassword),
                                                onCheckedChange = { checked ->
                                                    password.clearText()
                                                    shouldUsePassword = checked
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !(isRunningTask || requiresPassword),
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(
                                                stringResource(id = R.string.decompressor_option_use_password),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp,
                                            )
                                        }
                                        if (shouldUsePassword || requiresPassword) {
                                            PasswordInputField(password)
                                        }
                                    }
                                }
                                PlaceVerticallyFromStart {
                                    if (assumesTarball) {
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = (linkStrategy == LinkingStrategy.UsePlaceholders),
                                                onCheckedChange = { checked ->
                                                    linkStrategy = if (checked) { LinkingStrategy.UsePlaceholders } else { LinkingStrategy.Ignore }
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !isRunningTask,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(
                                                stringResource(id = R.string.decompressor_option_replace_links),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp
                                            )
                                        }
                                        if (linkStrategy == LinkingStrategy.Ignore) {
                                            Text(stringResource(id = R.string.decompressor_info4), fontSize = 16.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 0.dp, bottom = 10.dp))
                                        }
                                    }
                                }
                                if (!formatWasInferred) {
                                    Text(stringResource(id = R.string.decompressor_info5), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 0.dp, bottom = 10.dp))
                                }
                            }
                            if (shouldOverrideFileNameEncoding || (decompressorMode.bitsNotSet(DECOMPRESSOR_MODE_AUTO) && (selectedOrInferredFormat == CompressionOrArchiveFormat.ZIP))) { // i.e. show this only if overriding is enabled, or if the format is zip.
                                CardWithTitle(stringResource(id = R.string.note)) {
                                    Text(
                                        stringResource(
                                            if (shouldOverrideFileNameEncoding) {
                                                R.string.decompressor_info7
                                            } else {
                                                R.string.decompressor_info6
                                            }
                                        ), fontSize = 24.sp,
                                        lineHeight = 26.sp,
                                        modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 15.dp)
                                    )
                                }
                            }
                            if (!(isRunningTask || taskIsCompleted)) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                ) {
                                    PlaceVerticallyCentrally {
                                        val outputFileName by remember {
                                            derivedStateOf {
                                                if (shouldCreateNoMoreThanOneFile) {
                                                    inputFileName.let { inputFileNameLocal ->
                                                        if (autoMode) {
                                                            null
                                                        } else {
                                                            val i = inputFileNameLocal.lastIndexOf('.')
                                                            if (i > 0 /* && i == -1 */) { // If the input file is named ".xz" (starts with a dot which is the last dot in the string), we can't name the output file as an empty string or a dot. But if it is named ".tar.xz", then we can name it ".tar" (exactly, starting with a dot and ends with 'r'); ".tar" would be a valid file name (of a hidden file).
                                                                if (inputFileNameLocal.endsWith(".${selectedOrInferredFormat.fileExtension}")) {
                                                                    inputFileNameLocal.slice(0 .. (i - 1))
                                                                } else if (inputFileNameLocal.length > i + 1) {
                                                                    when (inputFileNameLocal.slice((i + 1) .. (inputFileNameLocal.length - 1))) {
                                                                        "taz", "tgz", "tlz", "txz", "tzst" -> inputFileNameLocal.slice(0 .. i) + selectedOrInferredFormat.fileExtension
                                                                        else -> null
                                                                    }
                                                                } else {
                                                                    null
                                                                }
                                                            } else {
                                                                null
                                                            }
                                                        } ?: "" // Let the user choose the name. (In this case, they must enter a name for the new file, unless the system allows empty file name)
                                                    }
                                                } else {
                                                    "" // Not used
                                                }
                                            }
                                        }
                                        Text(
                                            stringResource(
                                                if (shouldCreateNoMoreThanOneFile) {
                                                    R.string.decompression_will_start1
                                                } else {
                                                    R.string.decompressor_info8
                                                }
                                            ),
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp)
                                        )
                                        Button(
                                            onClick = {
                                                if (shouldCreateNoMoreThanOneFile) {
                                                    createOutputFile.launch(outputFileName)
                                                } else {
                                                    selectOutputDirAndCreateOutputFiles.launch(null)
                                                }
                                            },
                                            modifier = Modifier.padding(15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.choose_output_path), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                        if (!shouldCreateNoMoreThanOneFile) {
                                            Text(
                                                stringResource(id = R.string.decompressor_info9) + stringResource(
                                                    if (decompressorMode.bitsNotSet(DECOMPRESSOR_MODE_AUTO) && (selectedOrInferredFormat == CompressionOrArchiveFormat.TAR)) {
                                                        R.string.unarchiving_will_start
                                                    } else {
                                                        R.string.decompression_will_start2
                                                    }
                                                ),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp,
                                                modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } ?: PlaceVerticallyCentrally {
                            Button(
                                onClick = {
                                    selectInputFile.launch("application/*")
                                },
                                modifier = Modifier.padding(15.dp)
                            ) {
                                Text(stringResource(id = R.string.select_input_file), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(3)
        super.onDestroy()
    }

    private inline fun guessFormatByExt(fileExtension: String): CompressionOrArchiveFormat? {
        for (format in CompressionOrArchiveFormat.entries) {
            if (format.fileExtension == fileExtension) {
                return format
            }
        }
        return when (fileExtension) {
            "apk", "jar", "ZIP" -> CompressionOrArchiveFormat.ZIP
            else -> null
        }
    }

    private fun inferFileFormat(fileUri: Uri): FileFormatInferenceResult? {
        var inferredFormat: CompressionOrArchiveFormat? = null
        var isTarball = false
        DocumentFile.fromSingleUri(this, fileUri)?.also { file ->
            (file.getType()?.let { mime ->
                when (mime) {
                    "application/gzip" -> CompressionOrArchiveFormat.GZIP
                    "application/zip" -> CompressionOrArchiveFormat.ZIP
                    "application/zstd" -> CompressionOrArchiveFormat.ZSTD
                    else -> null
                }
            })?.also {
                inferredFormat = it
            }
            file.name?.also { fileName ->
                val i = fileName.lastIndexOf('.')
                if (i >= 0 && i < fileName.length - 1) {
                    val fileExt1 = fileName.slice((i + 1) .. (fileName.length - 1))
                    when (fileExt1) {
                        "taz", "tbz2", "tgz", "tlz", "txz", "tzst" -> run {
                            if (inferredFormat == null) {
                                inferredFormat = when (fileExt1) {
                                    "tbz2", "tgz", "txz", "tzst" -> guessFormatByExt(fileExt1.slice(1 .. (fileExt1.length - 1)))
                                    "taz" -> CompressionOrArchiveFormat.Z
                                    "tlz" -> CompressionOrArchiveFormat.LZMA
                                    else -> null // impossible
                                }
                            }
                            isTarball = true
                        }
                        else -> Unit
                    }
                    if (inferredFormat == null || (!isTarball)) {
                        guessFormatByExt(fileExt1)?.also { format ->
                            if ((!format.isArchiveFormat()) && i > 0) {
                                val j = fileName.slice(0 .. (i - 1)).lastIndexOf('.')
                                if (j >= 0 && j < i - 1) {
                                    guessFormatByExt(
                                        fileName.slice((j + 1) .. (i - 1))
                                    )?.also {
                                        if (it == CompressionOrArchiveFormat.TAR) {
                                            isTarball = true
                                        }
                                    }
                                }
                            }
                            if (inferredFormat == null) {
                                inferredFormat = format
                            }
                        }
                    }
                }
            }
        }
        return inferredFormat?.let { format -> FileFormatInferenceResult(format, isTarball) } // ?: null
    }

    private inline fun decompressOrUnarchive(inputUri: Uri, outputUri: Uri, progressRef: MutableFloatState, resultCodeRef: MutableIntState, format: CompressionOrArchiveFormat? = null, password: String? = null, fileNameEncoding: Charset? = null, extractFromTarball: Boolean = false, linkStrategy: LinkingStrategy = LinkingStrategy.Ignore): Job {
        return lifecycleScope.launch(Dispatchers.Main) {
            val cr = contentResolver
            val wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:decompressor") // :? null
            val inputFileName = inferFileNameFromContentUri(inputUri, this@DecompressorActivity).orEmpty()
            var errorFlag = false
            var errorHandled = false
            var cancellationFlag = false
            progressRef.floatValue = 0.0f
            resultCodeRef.intValue = 1
            val (formatIsTar, formatIsZip) = format?.let {
                when (it) {
                    CompressionOrArchiveFormat.TAR -> Pair(true, false)
                    CompressionOrArchiveFormat.ZIP -> Pair(false, true)
                    else -> Pair(false, false)
                }
            } ?: Pair(false, false)
            progressRef.floatValue = 0.0f
            resultCodeRef.intValue = 1
            if (extractFromTarball || formatIsTar || formatIsZip) {
                val fileLog = ArrayList<String>()
                try {
                    DocumentFile.fromTreeUri(this@DecompressorActivity, outputUri)?.also { dir ->
                        cr.openInputStream(inputUri)?.also { inputStream ->
                            val total = inputStream.available().toDouble()
                            CallbackInvokerInputStream(BufferedInputStream(inputStream), 2, 5) {
                                val n = it.available()
                                lifecycleScope.launch(Dispatchers.Main) {
                                    val progress = withContext(Dispatchers.Default) { 1.0 - (n.toDouble() / total) }
                                    val fullText = omitBeforeLastFewRecordsConcurrently(fileLog)
                                    val lastPath = fileLog.getOrNull(fileLog.size - 1).orEmpty()
                                    showNotificationInLogChannel(
                                        this@DecompressorActivity,
                                        3,
                                        progress,
                                        getString(R.string.extracting),
                                        lastPath,
                                        fullText,
                                        Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                                    )
                                    progressRef.floatValue = progress.toFloat()
                                }
                            }.let({
                                if (format == null) {
                                    CompressorStreamFactory().createCompressorInputStream(it)
                                } else {
                                    when (format) {
                                        CompressionOrArchiveFormat.BR -> BrotliCompressorInputStream(it)
                                        CompressionOrArchiveFormat.BZIP2 -> BZip2CompressorInputStream(it, true)
                                        CompressionOrArchiveFormat.GZIP -> GZIPInputStream(it)
                                        CompressionOrArchiveFormat.LZ4 -> FramedLZ4CompressorInputStream(it, true)
                                        CompressionOrArchiveFormat.LZMA -> LZMACompressorInputStream(it)
                                        CompressionOrArchiveFormat.XZ -> XZCompressorInputStream.Builder().setInputStream(it).setDecompressConcatenated(true).get()
                                        CompressionOrArchiveFormat.Z -> ZCompressorInputStream(it)
                                        CompressionOrArchiveFormat.ZSTD -> ZstdCompressorInputStream(it)
                                        CompressionOrArchiveFormat.TAR, CompressionOrArchiveFormat.ZIP -> it // use original, without any filter at this step
                                        else -> null
                                    }
                                }
                            })?.also {
                                (if (formatIsZip) { // this implies that format != null (but this information isn't relevant here)
                                    (if (fileNameEncoding == null) {
                                        ZipInputStream(it)
                                    } else {
                                        ZipInputStream(it, fileNameEncoding)
                                    }).use { stream ->
                                        wakeLock?.acquire(43200L)
                                        showMsg(this@DecompressorActivity, getString(R.string.decompressing_and_extracting_with_ellipsis))
                                        extractDirsAndFilesFromZipStream(stream, dir, fileLog)
                                    }
                                } else { // tar archive or tarball; format can be null, or TAR, or a compression format.
                                    (if (fileNameEncoding == null) {
                                        TarArchiveInputStream(it)
                                    } else {
                                        TarArchiveInputStream(it, fileNameEncoding.displayName())
                                    }).use { stream ->
                                        wakeLock?.acquire(43200L)
                                        showMsg(
                                            this@DecompressorActivity, getString(
                                                if (formatIsTar) {
                                                    R.string.extracting_with_ellipsis // This is obvious; if the input format is tar archive and not tarball (i.e. compressed tar archive), you don't decompress, you extract.
                                                } else { // tarball
                                                    R.string.decompressing_and_extracting_with_ellipsis
                                                }
                                            )
                                        )
                                        extractDirsAndFilesFromTarStream(stream, dir, fileLog, linkStrategy)
                                    }
                                }).also {
                                    if (it != 0) {
                                        errorFlag = true
                                    }
                                }
                            } ?: run {
                                errorFlag = true
                            }
                        } ?: run {
                            errorFlag = true
                        }
                    } ?: run {
                        errorFlag = true
                    }
                } catch (_: CancellationException) {
                    cancellationFlag = true
                } catch (_: Exception) {
                    errorFlag = true
                } finally {
                    if (cancellationFlag) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            showNotificationAboutCancelledTask(this@DecompressorActivity, 3, Intent(this@DecompressorActivity, DecompressorActivity::class.java))
                            resultCodeRef.intValue = -1
                        }
                    } else {
                        yield()
                        if (errorFlag) {
                            showErrorMsgAndNotifyUserAboutUnknownError(this@DecompressorActivity, 3)
                            resultCodeRef.intValue = -2
                            errorHandled = true
                        } else {
                            showNotificationInLogChannel(
                                this@DecompressorActivity,
                                3,
                                -1.0,
                                getString(R.string.completed),
                                inputFileName,
                                omitAfterFirstFewRecordsConcurrently(fileLog),
                                Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                            )
                            resultCodeRef.intValue = 2
                        }
                    }
                    wakeLock?.apply {
                        if (isHeld()) {
                            release()
                        }
                    }
                }
            } else if (!(format?.isArchiveFormat() ?: false)) { // i.e. format == null || !format.isArchiveFormat()
                val outputFileName = inferFileNameFromContentUri(outputUri, this@DecompressorActivity).orEmpty()
                try {
                    cr.openInputStream(inputUri)?.also { inputStream ->
                        val total = inputStream.available().toDouble()
                        CallbackInvokerInputStream(
                            if (format == null) {
                                BufferedInputStream(inputStream)
                            } else {
                                inputStream
                            }, 2, 5
                        ) {
                            ensureActive()
                            val n = it.available()
                            lifecycleScope.launch(Dispatchers.Main) {
                                val progress = withContext(Dispatchers.Default) { 1.0 - (n.toDouble() / total) }
                                showNotificationInLogChannel(
                                    this@DecompressorActivity,
                                    3,
                                    progress,
                                    getString(R.string.decompressing),
                                    inputFileName,
                                    "",
                                    Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                                )
                                progressRef.floatValue = progress.toFloat()
                            }
                        }.let({
                            if (format == null) {
                                CompressorStreamFactory().createCompressorInputStream(it)
                            } else {
                                when (format) {
                                    CompressionOrArchiveFormat.BR -> BrotliCompressorInputStream(it)
                                    CompressionOrArchiveFormat.BZIP2 -> BZip2CompressorInputStream(it, true)
                                    CompressionOrArchiveFormat.GZIP -> GZIPInputStream(it)
                                    CompressionOrArchiveFormat.LZ4 -> FramedLZ4CompressorInputStream(it, true)
                                    CompressionOrArchiveFormat.LZMA -> LZMACompressorInputStream(it)
                                    CompressionOrArchiveFormat.XZ -> XZCompressorInputStream.Builder().setInputStream(it).setDecompressConcatenated(true).get()
                                    CompressionOrArchiveFormat.Z -> ZCompressorInputStream(it)
                                    CompressionOrArchiveFormat.ZSTD -> ZstdCompressorInputStream(it)
                                    else -> null
                                }
                            }
                        })?.use { src ->
                            cr.openOutputStream(outputUri)?.use { dst ->
                                wakeLock?.acquire(43200L)
                                lifecycleScope.launch(Dispatchers.Main) {
                                    showMsg(this@DecompressorActivity, getString(R.string.decompressing_with_ellipsis))
                                }
                                withContext(Dispatchers.IO) {
                                    src.transferTo(dst)
                                }
                            } ?: run {
                                errorFlag = true
                            }
                        } ?: run {
                            errorFlag = true
                        }
                    } ?: run {
                        errorFlag = true
                    }
                } catch (_: CancellationException) {
                    cancellationFlag = true
                } catch (_: Exception) {
                    errorFlag = true
                } finally {
                    if (cancellationFlag) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            showNotificationAboutCancelledTask(this@DecompressorActivity, 3, Intent(this@DecompressorActivity, DecompressorActivity::class.java))
                            resultCodeRef.intValue = -1
                        }
                    } else {
                        yield()
                        if (errorFlag) {
                            showErrorMsgAndNotifyUserAboutUnknownError(this@DecompressorActivity, 3)
                            resultCodeRef.intValue = -2
                            errorHandled = true
                        } else {
                            showNotificationInLogChannel(
                                this@DecompressorActivity,
                                3,
                                -1.0,
                                getString(R.string.completed),
                                outputFileName,
                                "",
                                Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                            )
                            resultCodeRef.intValue = 2
                        }
                    }
                    wakeLock?.apply {
                        if (isHeld()) {
                            release()
                        }
                    }
                }
            } else if (format == CompressionOrArchiveFormat.SEVEN_Z) {
                DocumentFile.fromTreeUri(this@DecompressorActivity, outputUri)?.also { dir ->
                    val fileLog = ArrayList<String>()
                    val tempFile = File(cacheDir, "temp3.7z").also {
                        if (it.exists()) {
                            it.delete()
                        }
                    }
                    try {
                        cr.openInputStream(inputUri)?.use { src ->
                            tempFile.outputStream().use { dst ->
                                withContext(Dispatchers.IO) {
                                    src.copyTo(dst) // we use `copyTo` here instead of `transferTo` as this only involves simple streams.
                                }
                            }
                            SevenZFile.Builder().setFile(tempFile).let({
                                if (password != null) {
                                    it.setPassword(password)
                                } else {
                                    it
                                }
                            }).get().use { inputFile ->
                                wakeLock?.acquire(43200L)
                                lifecycleScope.launch(Dispatchers.Main) {
                                    showMsg(this@DecompressorActivity, getString(R.string.decompressing_and_extracting_with_ellipsis))
                                    showNotificationInLogChannel(
                                        this@DecompressorActivity,
                                        3,
                                        2.0,
                                        getString(R.string.extracting),
                                        inputFileName,
                                        "",
                                        Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                                    )
                                }
                                extractDirsAndFilesFrom7zFile(inputFile, dir, fileLog)
                            }.also {
                                if (it != 0) {
                                    errorFlag = true
                                }
                            }
                        } ?: run {
                            errorFlag = true
                        }
                    } catch (_: CancellationException) {
                        cancellationFlag = true
                    } catch (_: PasswordRequiredException) {
                        errorFlag = true
                        errorHandled = true
                        yield()
                        showMsg(this@DecompressorActivity, getString(R.string.error2, inputFileName))
                        showNotificationInLogChannel(
                            this@DecompressorActivity,
                            3,
                            -1.0,
                            getString(R.string.error),
                            getString(R.string.error2, inputFileName),
                            "",
                            Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                        )
                        resultCodeRef.intValue = -3
                    } catch (_: Exception) {
                        errorFlag = true
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                        if (cancellationFlag) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                showNotificationAboutCancelledTask(this@DecompressorActivity, 3, Intent(this@DecompressorActivity, DecompressorActivity::class.java))
                                resultCodeRef.intValue = -1
                            }
                        } else {
                            yield()
                            if (errorFlag) {
                                if (!errorHandled) {
                                    showErrorMsgAndNotifyUserAboutUnknownError(this@DecompressorActivity, 3)
                                    resultCodeRef.intValue = -2
                                    errorHandled = true
                                }
                            } else {
                                showNotificationInLogChannel(
                                    this@DecompressorActivity,
                                    3,
                                    -1.0,
                                    getString(R.string.completed),
                                    inputFileName,
                                    omitAfterFirstFewRecordsConcurrently(fileLog),
                                    Intent(this@DecompressorActivity, DecompressorActivity::class.java)
                                )
                                resultCodeRef.intValue = 2
                            }
                        }
                        wakeLock?.apply {
                            if (isHeld()) {
                                release()
                            }
                        }
                    }
                } ?: run {
                    errorFlag = true
                }
            } else {
                errorFlag = true
            }
            if (errorFlag && (!errorHandled)) {
                showErrorMsgAndNotifyUserAboutUnknownError(this@DecompressorActivity, 3)
                progressRef.floatValue = -2.0f
            }
        }
    }

    private fun checkInputAndStartDecompressingOrUnarchiving(inputUri: Uri?, outputUri: Uri?, progressRef: MutableFloatState, resultCodeRef: MutableIntState, format: CompressionOrArchiveFormat? = null, password: String? = null, fileNameEncoding: Charset? = null, extractFromTarball: Boolean = false, linkStrategy: LinkingStrategy = LinkingStrategy.Ignore): Job? {
        return if (outputUri == null) {
            (null) // This is not an error. Do nothing
        } else if (inputUri == null || (!areOfSupportedScheme(inputUri, outputUri)) || (inputUri == outputUri) || (!noneOfThemIsFileUriOfMyFileOrDir(this, inputUri, outputUri))) {
            showErrorMsgAndNotifyUserAboutUnknownError(this, 3, Intent(this, DecompressorActivity::class.java))
            (null)
        } else {
            decompressOrUnarchive(
                inputUri,
                outputUri,
                progressRef,
                resultCodeRef,
                format,
                password,
                fileNameEncoding,
                extractFromTarball,
                linkStrategy
            )
        }
    }

    private inline fun checkInputAndStartDecompressing(inputUri: Uri?, outputUri: Uri?, progressRef: MutableFloatState, resultCodeRef: MutableIntState, format: CompressionOrArchiveFormat? = null, password: String? = null, fileNameEncoding: Charset? = null): Job? {
        return checkInputAndStartDecompressingOrUnarchiving(inputUri, outputUri, progressRef, resultCodeRef, format, password, fileNameEncoding, extractFromTarball = false)
    }

    private inline fun checkInputAndStartExtractingFromTarball(inputUri: Uri?, outputUri: Uri?, progressRef: MutableFloatState, resultCodeRef: MutableIntState, compressionFormat: CompressionOrArchiveFormat? = null, fileNameEncoding: Charset? = null, linkStrategy: LinkingStrategy = LinkingStrategy.Ignore): Job? {
        return checkInputAndStartDecompressingOrUnarchiving(
            inputUri,
            outputUri,
            progressRef,
            resultCodeRef,
            compressionFormat,
            fileNameEncoding = fileNameEncoding,
            extractFromTarball = true,
            linkStrategy = linkStrategy
        )
    }

    private suspend fun extractDirsAndFilesFromZipStream(stream: ZipInputStream, outputDir: DocumentFile, fileLog: ArrayList<String>): Int {
        /* Return code:
             0: success
             -1: error; absolute path found.
             1: error; something strange happened.
        */
        var resultCode = 0
        val cr = withContext(Dispatchers.Main) { contentResolver }
        withContext(Dispatchers.Default) {
            while (resultCode == 0) {
                val entry = withContext(Dispatchers.IO) {
                    stream.nextEntry
                }
                if (entry == null) {
                    break // success, resultCode = 0
                } else {
                    val entryIsDir = entry.isDirectory()
                    val path = Paths.get(entry.name).normalize()
                    if (path.isAbsolute()) {
                        resultCode = -1
                        break
                    } else {
                        withContext(Dispatchers.Main) {
                            fileLog.add(path.toString())
                        }
                    }
                    var dir = outputDir
                    val n = path.nameCount
                    if (n > 0) {
                        for (i in 0 .. (n - 1)) {
                            val name = path.getName(i).toString()
                            if (Regex("/?\\.\\./?").containsMatchIn(name) || (('/' in name) && (name.indexOf('/') != name.length - 1 /* && name.length > 0 */))) {
                                resultCode = 1
                                break
                            } else {
                                val nameWithNoSlash = name.trim('/')
                                if ((i < (n - 1)) || entryIsDir) {
                                    val subDir = withContext(Dispatchers.Main) {
                                        dir.findFile(nameWithNoSlash) /* find dir */ ?: dir.createDirectory(nameWithNoSlash) // ?: null
                                    }
                                    if (subDir == null) {
                                        resultCode = 1
                                        break
                                    } else {
                                        dir = subDir
                                        if (dir.isDirectory()) {
                                            continue
                                        } else {
                                            resultCode = 1
                                            break
                                        }
                                    }
                                } else { // i >= n - 1 && !entryIsDir
                                    withContext(Dispatchers.Main) {
                                        val file = dir.createFile("application/octet-stream", nameWithNoSlash)
                                        if (file == null) {
                                            resultCode = 1
                                        } else {
                                            val outputStream = cr.openOutputStream(file.uri)
                                            if (outputStream == null) {
                                                resultCode = 1
                                            } else {
                                                outputStream.use { dst ->
                                                    withContext(Dispatchers.IO) {
                                                        stream.transferTo(dst)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (resultCode != 0) {
                                break // break for loop
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        stream.closeEntry()
                    }
                }
            }
        }
        return resultCode
    }

    private suspend fun extractDirsAndFilesFrom7zFile(inputFile: SevenZFile, outputDir: DocumentFile, fileLog: ArrayList<String>): Int {
        /* Return code:
             0: success
             -1: error; absolute path found.
             1: error; something strange happened.
        */
        var resultCode = 0
        val cr = withContext(Dispatchers.Main) { contentResolver }
        withContext(Dispatchers.IO) {
            for (entry in inputFile.entries) {
                withContext(Dispatchers.Default) {
                    val entryIsDir = entry.isDirectory()
                    val path = Paths.get(entry.name).normalize()
                    if (path.isAbsolute()) {
                        resultCode = -1
                        return@withContext
                    } else {
                        withContext(Dispatchers.Main) {
                            fileLog.add(path.toString())
                        }
                    }
                    var dir = outputDir
                    val n = path.nameCount
                    if (n > 0) {
                        for (i in 0 .. (n - 1)) {
                            val name = path.getName(i).toString()
                            if (Regex("/?\\.\\./?").containsMatchIn(name) || (('/' in name) && (name.indexOf('/') != name.length - 1 /* && name.length > 0 */))) {
                                resultCode = 1
                                break
                            } else {
                                val nameWithNoSlash = name.trim('/')
                                if ((i < (n - 1)) || entryIsDir) {
                                    val subDir = withContext(Dispatchers.Main) {
                                        dir.findFile(nameWithNoSlash) /* find dir */ ?: dir.createDirectory(nameWithNoSlash) // ?: null
                                    }
                                    if (subDir == null) {
                                        resultCode = 1
                                        break
                                    } else {
                                        dir = subDir
                                        if (dir.isDirectory()) {
                                            continue
                                        } else {
                                            resultCode = 1
                                            break
                                        }
                                    }
                                } else { // i >= n - 1 && !entryIsDir
                                    withContext(Dispatchers.Main) {
                                        val file = dir.createFile("application/octet-stream", nameWithNoSlash)
                                        if (file == null) {
                                            resultCode = 1
                                        } else if (entry.hasStream()) {
                                            inputFile.getInputStream(entry).use { src ->
                                                cr.openOutputStream(file.uri).use { dst ->
                                                    withContext(Dispatchers.IO) {
                                                        src.transferTo(dst)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (resultCode != 0) {
                                break // break for loop
                            }
                        }
                    }
                }
                if (resultCode != 0) {
                    break
                }
            }
        }
        return resultCode
    }

    private suspend fun extractDirsAndFilesFromTarStream(stream: TarArchiveInputStream, outputDir: DocumentFile, fileLog: ArrayList<String>, linkStrategy: LinkingStrategy): Int {
        /* Return code:
             0: success
             -1: error; absolute path found.
             1: error; something strange happened.
        */
        var resultCode = 0
        val cr = withContext(Dispatchers.Main) { contentResolver }
        withContext(Dispatchers.Default) {
            while (resultCode == 0) {
                val entry = withContext(Dispatchers.IO) { stream.nextEntry } // If cancelled, throws error, and the loop is ended.
                if (entry == null) {
                    break // success
                } else if (!entry.isCheckSumOK()) {
                    resultCode = 1
                    break
                } else {
                    val entryIsDir = entry.isDirectory()
                    val entryIsFile = entry.isFile()
                    val entryIsLink = entry.isLink()
                    val entryIsSymlink = entry.isSymbolicLink()
                    val path = Paths.get(entry.name).normalize()
                    if (path.isAbsolute()) {
                        resultCode = -1
                        break
                    } else {
                        val pathStr = path.toString()
                        if (entryIsLink || entryIsSymlink) { // Should check link first. See below.
                            withContext(Dispatchers.Main) {
                                fileLog.add(
                                    if (linkStrategy == LinkingStrategy.Ignore) {
                                        concatenateSmallNumberOfStringsOnAnotherThread(pathStr, getString(R.string.space_parentheses_ignored))
                                    } else {
                                        pathStr
                                    }
                                )
                            }
                        } else if (entryIsFile || entryIsDir) {
                            withContext(Dispatchers.Main) {
                                fileLog.add(pathStr)
                            }
                        }
                    }
                    var dir = outputDir
                    val n = path.nameCount
                    if (n > 0) {
                        for (i in 0 .. (n - 1)) {
                            val name = path.getName(i).toString()
                            if (Regex("/?\\.\\./?").containsMatchIn(name) || (('/' in name) && (name.indexOf('/') != name.length - 1 /* && name.length > 0 */))) {
                                resultCode = 1
                                break
                            } else {
                                val isLast = (i == (n - 1))
                                val nameWithNoSlash = name.trim('/')
                                if (isLast && (entryIsLink || entryIsSymlink)) { // should check link first, as isFile and isSymbolicLink can both be true due to unknown reason.
                                    withContext(Dispatchers.Main) {
                                        when (linkStrategy) {
                                            LinkingStrategy.UsePlaceholders -> run {
                                                val file = dir.createFile("text/plain", "${nameWithNoSlash}.txt") // This may create something like "*.txt.txt" It is intended.
                                                if (file == null) {
                                                    resultCode = 1
                                                } else {
                                                    val outputStream = cr.openOutputStream(file.uri)
                                                    if (outputStream == null) {
                                                        resultCode = 1
                                                    } else {
                                                        outputStream.use {
                                                            withContext(Dispatchers.IO) {
                                                                it.write(entry.linkName.toByteArray())
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                } else if ((!isLast) || entryIsDir) {
                                    val subDir = withContext(Dispatchers.Main) {
                                        dir.findFile(nameWithNoSlash) /* find dir */ ?: dir.createDirectory(nameWithNoSlash) // ?: null
                                    }
                                    if (subDir == null) {
                                        resultCode = 1
                                        break
                                    } else {
                                        dir = subDir
                                        if (dir.isDirectory()) {
                                            continue
                                        } else {
                                            resultCode = 1
                                            break
                                        }
                                    }
                                } else if (entryIsFile) {
                                    withContext(Dispatchers.Main) {
                                        val file = dir.createFile("application/octet-stream", nameWithNoSlash)
                                        if (file == null) {
                                            resultCode = 1
                                        } else {
                                            val outputStream = cr.openOutputStream(file.uri)
                                            if (outputStream == null) {
                                                resultCode = 1
                                            } else {
                                                outputStream.use { dst ->
                                                    withContext(Dispatchers.IO) {
                                                        stream.transferTo(dst)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (resultCode != 0) {
                                break // break for loop
                            }
                        }
                    }
                }
            }
        }
        return resultCode
    }
}

