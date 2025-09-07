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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.toCharArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme
import test.sls1005.projects.fundamentalcompressor.ui.CardWithTitle
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyCentrally
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyFromStart
import test.sls1005.projects.fundamentalcompressor.ui.PasswordSettingFields
import test.sls1005.projects.fundamentalcompressor.util.functions.jobIsCompletedOrCancelled
import test.sls1005.projects.fundamentalcompressor.util.CallbackInvokerInputStream

class FileCompressorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createLogChannelIfApplicable(this)
        setContent {
            FundamentalCompressorTheme {
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }
                var selectedFormat by remember { mutableStateOf(CompressionOrArchiveFormat.XZ) }
                var showsAdvancedOptions by remember { mutableStateOf(false) }
                var shouldUsePassword by remember { mutableStateOf(false) }
                var compressionLevel by remember { mutableIntStateOf(5) }
                var blockSize by remember { mutableIntStateOf(5) }
                val password1 = rememberTextFieldState()
                val password2 = rememberTextFieldState()
                var currentTask by remember { mutableStateOf<Job?>(null) }
                val taskStatus = remember { mutableIntStateOf(0) } // No "by" here. If 1: running, 2: completed, -1: cancelled, -2: the last result was error. 0 means initial state, but it is not checked for.
                val currentProgress = remember { mutableFloatStateOf(-1.0f) } 
                val isRunningTask by remember {
                    derivedStateOf { taskStatus.intValue == 1 }
                }
                val taskIsCompleted by remember {
                    derivedStateOf { taskStatus.intValue == 2 }
                }
                var inputFileUri by remember { mutableStateOf<Uri?>(null) }
                val inputFileName by remember {
                    derivedStateOf {
                        inputFileUri?.let { uri ->
                            inferFileNameFromContentUri(uri, this@FileCompressorActivity) // ?: null
                        } ?: ""
                    }
                }
                val inputFileSize by remember {
                    derivedStateOf {
                        inputFileUri?.let {
                            DocumentFile.fromSingleUri(this@FileCompressorActivity, it)?.length() // ?: null
                        } ?: -1L
                    }
                }
                val inputFileSizeStr by remember {
                    derivedStateOf {
                        if (inputFileSize == -1L) {
                            getString(R.string.unknown)
                        } else {
                            Formatter.formatShortFileSize(this@FileCompressorActivity, inputFileSize)
                        }
                    }
                }
                val selectInputFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                    if (it != null) {
                        if (jobIsCompletedOrCancelled(currentTask)) {
                            currentTask = null
                            taskStatus.intValue = 0
                        }
                        inputFileUri = it
                    }
                }
                val createOutputFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(selectedFormat.mimeType),
                    when(selectedFormat) {
                        CompressionOrArchiveFormat.BZIP2 -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(
                                inputFileUri,
                                outputFileUri,
                                CompressionOrArchiveFormat.BZIP2,
                                progressRef = currentProgress,
                                resultCodeRef = taskStatus,
                                blockSize = if (showsAdvancedOptions) { blockSize } else { -1 }
                            )
                        }
                        CompressionOrArchiveFormat.GZIP -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(
                                inputFileUri,
                                outputFileUri,
                                CompressionOrArchiveFormat.GZIP,
                                progressRef = currentProgress,
                                resultCodeRef = taskStatus,
                                compressionLevel = if (showsAdvancedOptions) { compressionLevel } else { -1 }
                            )
                        }
                        CompressionOrArchiveFormat.LZMA -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(inputFileUri, outputFileUri, CompressionOrArchiveFormat.LZMA, progressRef = currentProgress, resultCodeRef = taskStatus)
                        }
                        CompressionOrArchiveFormat.LZ4 -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(inputFileUri, outputFileUri, CompressionOrArchiveFormat.LZ4, progressRef = currentProgress, resultCodeRef = taskStatus)
                        }
                        CompressionOrArchiveFormat.XZ -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(inputFileUri, outputFileUri, CompressionOrArchiveFormat.XZ, progressRef = currentProgress, resultCodeRef = taskStatus)
                        }
                        CompressionOrArchiveFormat.ZSTD -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(inputFileUri, outputFileUri, CompressionOrArchiveFormat.ZSTD, progressRef = currentProgress, resultCodeRef = taskStatus)
                        }
                        CompressionOrArchiveFormat.SEVEN_Z -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(
                                inputFileUri,
                                outputFileUri,
                                CompressionOrArchiveFormat.SEVEN_Z,
                                progressRef = currentProgress,
                                resultCodeRef = taskStatus,
                                password = if (shouldUsePassword) { password1.text.toString() } else { null }
                            )
                        }
                        CompressionOrArchiveFormat.ZIP -> { outputFileUri ->
                            currentTask = checkInputAndCompressFile(
                                inputFileUri,
                                outputFileUri,
                                CompressionOrArchiveFormat.ZIP,
                                progressRef = currentProgress,
                                resultCodeRef = taskStatus,
                                compressionLevel = if (showsAdvancedOptions) { compressionLevel } else { -1 }
                            )
                        }
                        else -> { _ -> } // no tar support here
                    }
                )
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
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
                                        Text(stringResource(id = R.string.file_is_being_compressed), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        Text(
                                            inputFileName,
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 5.dp)
                                        )
                                        if (currentProgress.floatValue.let { (0.0f <= it) && (it <= 1.0f) }) {
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
                                        Text(stringResource(id = R.string.file_has_been_selected), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        TextButton(
                                            onClick = {
                                                selectInputFile.launch("*/*")
                                            },
                                            shape = RectangleShape,
                                            modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = if (taskIsCompleted) { 15.dp } else { 2.dp })
                                        ) {
                                            Text(stringResource(R.string.file_or_dir_name_and_size, inputFileName, inputFileSizeStr), fontSize = 24.sp, lineHeight = 26.sp)
                                        }
                                        if (!taskIsCompleted) {
                                            Text(
                                                stringResource(id = R.string.file_compressor_info1),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp,
                                                modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 15.dp)
                                            )
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
                                            onClick = { (this@FileCompressorActivity).finish() },
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.exit), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    }
                                }
                            }
                            CardWithTitle(stringResource(id = R.string.options)) {
                                val formatDisplayName by remember { derivedStateOf { toDisplayName(selectedFormat) } }
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
                                        label = { Text(stringResource(id = R.string.compression_format)) },
                                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        (remember {
                                            arrayOf(
                                                CompressionOrArchiveFormat.BZIP2,
                                                CompressionOrArchiveFormat.GZIP,
                                                CompressionOrArchiveFormat.LZMA,
                                                CompressionOrArchiveFormat.LZ4,
                                                CompressionOrArchiveFormat.XZ,
                                                CompressionOrArchiveFormat.ZSTD,
                                                // Above: compression formats; below: archive formats
                                                CompressionOrArchiveFormat.SEVEN_Z,
                                                CompressionOrArchiveFormat.ZIP
                                            )
                                        }).forEach { format ->
                                            DropdownMenuItem(
                                                text = { Text(toDisplayName(format), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    selectedFormat = format
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
                                if (selectedFormat == CompressionOrArchiveFormat.SEVEN_Z) {
                                    Row(
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                    ) {
                                        Checkbox(
                                            checked = shouldUsePassword,
                                            onCheckedChange = { checked ->
                                                password1.clearText()
                                                password2.clearText()
                                                shouldUsePassword = checked
                                                if (jobIsCompletedOrCancelled(currentTask)) {
                                                    currentTask = null
                                                    taskStatus.intValue = 0
                                                }
                                            },
                                            enabled = !isRunningTask,
                                            modifier = Modifier.padding(2.dp)
                                        )
                                        Text(
                                            stringResource(id = R.string.compressor_option_set_password),
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp
                                        )
                                    }
                                    if (shouldUsePassword) {
                                        PasswordSettingFields(password1, password2)
                                    }
                                }
                                when (selectedFormat) {
                                    CompressionOrArchiveFormat.BZIP2, CompressionOrArchiveFormat.GZIP, CompressionOrArchiveFormat.ZIP -> PlaceVerticallyFromStart {
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = showsAdvancedOptions,
                                                onCheckedChange = { checked ->
                                                    showsAdvancedOptions = checked
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !isRunningTask,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(
                                                stringResource(id = R.string.show_advanced_options),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp
                                            )
                                        }
                                        if (showsAdvancedOptions) {
                                            when (selectedFormat) {
                                                CompressionOrArchiveFormat.ZIP, CompressionOrArchiveFormat.GZIP -> PlaceVerticallyFromStart {
                                                    var menuExpanded by remember { mutableStateOf(false) }
                                                    ExposedDropdownMenuBox(
                                                        expanded = menuExpanded,
                                                        onExpandedChange = { menuExpanded = it },
                                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 10.dp)
                                                    ) {
                                                        OutlinedTextField(
                                                            "$compressionLevel",
                                                            readOnly = true,
                                                            onValueChange = { /* Empty */ },
                                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                                            label = { Text(stringResource(id = R.string.compression_level)) },
                                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                                        )
                                                        ExposedDropdownMenu(
                                                            expanded = menuExpanded,
                                                            onDismissRequest = { menuExpanded = false }
                                                        ) {
                                                            for (level in 1 .. 9) {
                                                                DropdownMenuItem(
                                                                    text = { Text("$level", fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                                    onClick = {
                                                                        compressionLevel = level
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
                                                CompressionOrArchiveFormat.BZIP2 -> PlaceVerticallyFromStart {
                                                    var menuExpanded by remember { mutableStateOf(false) }
                                                    ExposedDropdownMenuBox(
                                                        expanded = menuExpanded,
                                                        onExpandedChange = { menuExpanded = it },
                                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 10.dp)
                                                    ) {
                                                        OutlinedTextField(
                                                            "${100 * blockSize} kB",
                                                            readOnly = true,
                                                            onValueChange = { /* Empty */ },
                                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                                            label = { Text(stringResource(id = R.string.block_size)) },
                                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                                        )
                                                        ExposedDropdownMenu(
                                                            expanded = menuExpanded,
                                                            onDismissRequest = { menuExpanded = false }
                                                        ) {
                                                            for (k in 1 .. 9) {
                                                                DropdownMenuItem(
                                                                    text = { Text("${100 * k}", fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                                    onClick = {
                                                                        blockSize = k
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
                                                else -> Unit
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            CardWithTitle(stringResource(id = R.string.note)) {
                                Text(
                                    stringResource(id = R.string.file_compressor_warning1),
                                    fontSize = 24.sp,
                                    lineHeight = 26.sp,
                                    modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 5.dp)
                                )
                                Text(
                                    stringResource(id = R.string.file_compressor_clickable_text1),
                                    fontSize = 24.sp,
                                    lineHeight = 26.sp,
                                    modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 10.dp).clickable(enabled = !isRunningTask) {
                                        startActivity(Intent(this@FileCompressorActivity, DirCompressorActivity::class.java))
                                    }
                                )
                            }
                            if (!(isRunningTask || taskIsCompleted)) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                ) {
                                    PlaceVerticallyCentrally {
                                        val outputFileName by remember {
                                            derivedStateOf {
                                                inputFileName.let { inputFileNameLocal ->
                                                    val newExt = selectedFormat.fileExtension
                                                    if (inputFileNameLocal.isEmpty()) {
                                                        "output.$newExt"
                                                    } else {
                                                        when (selectedFormat) {
                                                            CompressionOrArchiveFormat.ZIP, CompressionOrArchiveFormat.SEVEN_Z -> run {
                                                                val i = inputFileNameLocal.lastIndexOf('.')
                                                                if (i == -1) {
                                                                    "$inputFileNameLocal.$newExt"
                                                                } else {
                                                                    inputFileNameLocal.slice(0 .. i) + newExt
                                                                }
                                                            }
                                                            else -> "$inputFileNameLocal.$newExt"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Text(stringResource(id = R.string.compression_will_start), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        Button(
                                            onClick = {
                                                if (shouldUsePassword && (password1.text.toString() != password2.text.toString())) {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(getString(R.string.error3))
                                                    }
                                                } else {
                                                    createOutputFile.launch(outputFileName)
                                                }
                                            },
                                            modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.choose_output_path), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    }
                                }
                            }
                        } ?: PlaceVerticallyCentrally {
                            Button(
                                onClick = {
                                    selectInputFile.launch("*/*")
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
        NotificationManagerCompat.from(this).cancel(1)
        super.onDestroy()
    }

    private inline fun compressFile(inputUri: Uri, outputUri: Uri, format: CompressionOrArchiveFormat, progressRef: MutableFloatState, resultCodeRef: MutableIntState, blockSize: Int = -1, compressionLevel: Int = -1, password: String? = null): Job {
        return lifecycleScope.launch(Dispatchers.Main) {
            val cr = contentResolver
            val wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:compressor") // :? null
            val inputFileName = inferFileNameFromContentUri(inputUri, this@FileCompressorActivity).orEmpty()
            val outputFileName = inferFileNameFromContentUri(outputUri, this@FileCompressorActivity).orEmpty()
            var errorFlag = false
            var cancellationFlag = false
            progressRef.floatValue = 0.0f
            resultCodeRef.intValue = 1 // initialize and inform caller
            if (format.isArchiveFormat()) {
                when (format) {
                    CompressionOrArchiveFormat.SEVEN_Z -> run {
                        val lastModifiedTime = DocumentFile.fromSingleUri(this@FileCompressorActivity, inputUri)?.lastModified() ?: -1L
                        val tempFile = File(cacheDir, "temp1.7z").also {
                            if (it.exists()) {
                                it.delete()
                            }
                        }
                        try {
                            cr.openInputStream(inputUri)?.also { inputStream ->
                                val total = inputStream.available().toDouble()
                                CallbackInvokerInputStream(inputStream, 5, 8, {
                                    ensureActive()
                                    val n = it.available()
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        val progress = withContext(Dispatchers.Default) { (1.0 - (n.toDouble() / total)) / 2.0 }
                                        showNotificationInLogChannel(
                                            this@FileCompressorActivity,
                                            1,
                                            progress,
                                            getString(R.string.compressing),
                                            inputFileName,
                                            "",
                                            Intent(this@FileCompressorActivity, FileCompressorActivity::class.java)
                                        )
                                        progressRef.floatValue = progress.toFloat()
                                    }
                                }).use { ci ->
                                    (if (password == null) {
                                        SevenZOutputFile(tempFile)
                                    } else {
                                        SevenZOutputFile(tempFile, password.toCharArray())
                                    }).use { outputFile ->
                                        withContext(Dispatchers.IO) {
                                            outputFile.putArchiveEntry(
                                                SevenZArchiveEntry().also { entry ->
                                                    entry.name = inputFileName
                                                    entry.setDirectory(false)
                                                    if (lastModifiedTime != -1L) {
                                                        entry.setHasLastModifiedDate(true)
                                                        withContext(Dispatchers.Main) {
                                                            entry.setLastModifiedTime(FileTime.fromMillis(lastModifiedTime))
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        wakeLock?.acquire(43200L)
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            showMsg(this@FileCompressorActivity, getString(R.string.compressing_with_ellipsis))
                                        }
                                        withContext(Dispatchers.IO) {
                                            outputFile.write(ci)
                                            outputFile.closeArchiveEntry()
                                        }
                                    }
                                    cr.openOutputStream(outputUri)?.use { dst ->
                                        tempFile.inputStream().also {
                                            val total = it.available()
                                            CallbackInvokerInputStream(it, 5, 8, {
                                                ensureActive()
                                                val n = it.available()
                                                lifecycleScope.launch(Dispatchers.Main) {
                                                    val progress = withContext(Dispatchers.Default) { 0.5 + (1.0 - (n.toDouble() / total)) / 2.0 }
                                                    showNotificationInLogChannel(
                                                        this@FileCompressorActivity,
                                                        1,
                                                        progress,
                                                        getString(R.string.compressing),
                                                        inputFileName,
                                                        "",
                                                        Intent(this@FileCompressorActivity, FileCompressorActivity::class.java)
                                                    )
                                                    progressRef.floatValue = progress.toFloat()
                                                }
                                            }).use { src ->
                                                withContext(Dispatchers.IO) {
                                                    src.copyTo(dst)
                                                }
                                            }
                                        }
                                    } ?: run {
                                        errorFlag = true
                                    }
                                }
                            } ?: run {
                                errorFlag = true
                            }
                        } catch (_: CancellationException) {
                            cancellationFlag = true
                        } catch (_: Exception) {
                            errorFlag = true
                        } finally {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                            if (cancellationFlag) {
                                lifecycleScope.launch(Dispatchers.Main) {
                                    showNotificationAboutCancelledTask(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = -1
                                }
                            } else {
                                yield()
                                if (errorFlag) {
                                    showErrorMsgAndNotifyUserAboutUnknownError(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = -2
                                } else {
                                    showNotificationInLogChannel(this@FileCompressorActivity, 1, -1.0, getString(R.string.completed), outputFileName, "", Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = 2
                                }
                            }
                            wakeLock?.apply {
                                if (isHeld()) {
                                    release()
                                }
                            }
                        }
                    }
                    CompressionOrArchiveFormat.ZIP -> run {
                        try {
                            cr.openInputStream(inputUri)?.also {
                                val total = it.available().toDouble()
                                CallbackInvokerInputStream(it, 5, 8, {
                                    ensureActive()
                                    val n = it.available()
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        val progress = withContext(Dispatchers.Default) { 1.0 - (n.toDouble() / total) }
                                        showNotificationInLogChannel(
                                            this@FileCompressorActivity,
                                            1,
                                            progress,
                                            getString(R.string.compressing),
                                            inputFileName,
                                            "",
                                            Intent(this@FileCompressorActivity, FileCompressorActivity::class.java)
                                        )
                                        progressRef.floatValue = progress.toFloat()
                                    }
                                }).use { src ->
                                    cr.openOutputStream(outputUri)?.also { outputStream ->
                                        ZipOutputStream(outputStream).use { dst ->
                                            val lastModifiedTime = DocumentFile.fromSingleUri(this@FileCompressorActivity, inputUri)?.lastModified() ?: -1L
                                            if (compressionLevel >= 1 && compressionLevel <= 9) {
                                                dst.setLevel(compressionLevel)
                                            }
                                            withContext(Dispatchers.IO) {
                                                dst.putNextEntry(
                                                    ZipEntry(inputFileName).also { entry ->
                                                        withContext(Dispatchers.Main) {
                                                            if (lastModifiedTime != -1L) {
                                                                entry.time = lastModifiedTime
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            wakeLock?.acquire(43200L)
                                            lifecycleScope.launch(Dispatchers.Main) {
                                                showMsg(this@FileCompressorActivity, getString(R.string.compressing_with_ellipsis))
                                            }
                                            withContext(Dispatchers.IO) {
                                                src.transferTo(dst)
                                                dst.closeEntry()
                                            }
                                        }
                                    } ?: run {
                                        errorFlag = true
                                    }
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
                                    showNotificationAboutCancelledTask(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = -1
                                }
                            } else {
                                yield()
                                if (errorFlag) {
                                    showErrorMsgAndNotifyUserAboutUnknownError(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = -2
                                } else {
                                    showNotificationInLogChannel(this@FileCompressorActivity, 1, -1.0, getString(R.string.completed), outputFileName, "", Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                                    resultCodeRef.intValue = 2
                                }
                            }
                            wakeLock?.apply {
                                if (isHeld()) {
                                    release()
                                }
                            }
                        }
                    }
                    else -> run {
                        showErrorMsgAndNotifyUserAboutUnknownError(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                        resultCodeRef.intValue = -2
                    }
                }
            } else {
                try {
                    cr.openInputStream(inputUri)?.also {
                        val total = it.available().toDouble()
                        CallbackInvokerInputStream(it, 5, 8, {
                            ensureActive()
                            val n = it.available()
                            lifecycleScope.launch(Dispatchers.Main) {
                                val progress = withContext(Dispatchers.Default) { 1.0 - (n.toDouble() / total) }
                                showNotificationInLogChannel(
                                    this@FileCompressorActivity,
                                    1,
                                    progress,
                                    getString(R.string.compressing),
                                    inputFileName,
                                    "",
                                    Intent(this@FileCompressorActivity, FileCompressorActivity::class.java)
                                )
                                progressRef.floatValue = progress.toFloat()
                            }
                        }).use { src ->
                            cr.openOutputStream(outputUri)?.also { outputStream ->
                                withContext(Dispatchers.Default) {
                                    (when (format) {
                                        CompressionOrArchiveFormat.BZIP2 -> run {
                                            if (blockSize < 1 || blockSize > 9) {
                                                BZip2CompressorOutputStream(outputStream)
                                            } else {
                                                BZip2CompressorOutputStream(outputStream, blockSize)
                                            }
                                        }
                                        CompressionOrArchiveFormat.GZIP -> run {
                                            if (compressionLevel < 1 || compressionLevel > 9) {
                                                GZIPOutputStream(outputStream)
                                            } else {
                                                object : GZIPOutputStream(outputStream) {
                                                    init { this.def.setLevel(compressionLevel) }
                                                }
                                            }
                                        }
                                        CompressionOrArchiveFormat.LZ4 -> FramedLZ4CompressorOutputStream(outputStream)
                                        CompressionOrArchiveFormat.LZMA -> LZMACompressorOutputStream(outputStream)
                                        CompressionOrArchiveFormat.XZ -> XZCompressorOutputStream(outputStream)
                                        CompressionOrArchiveFormat.ZSTD -> ZstdCompressorOutputStream(outputStream)
                                        else -> null
                                    })?.also { s ->
                                        withContext(Dispatchers.Main) {
                                            s.use { dst ->
                                                wakeLock?.acquire(43200L)
                                                lifecycleScope.launch(Dispatchers.Main) {
                                                    showMsg(this@FileCompressorActivity, getString(R.string.compressing_with_ellipsis))
                                                }
                                                withContext(Dispatchers.IO) {
                                                    src.transferTo(dst)
                                                }
                                            }
                                        }
                                    } ?: run {
                                        errorFlag = true
                                    }
                                }
                            } ?: run {
                                errorFlag = true
                            }
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
                            showNotificationAboutCancelledTask(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                            resultCodeRef.intValue = -1
                        }
                    } else {
                        yield()
                        if (errorFlag) {
                            showErrorMsgAndNotifyUserAboutUnknownError(this@FileCompressorActivity, 1, Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                            resultCodeRef.intValue = -2
                        } else {
                            showNotificationInLogChannel(this@FileCompressorActivity, 1, -1.0, getString(R.string.completed), outputFileName, "", Intent(this@FileCompressorActivity, FileCompressorActivity::class.java))
                            resultCodeRef.intValue = 2
                        }
                    }
                    wakeLock?.apply {
                        if (isHeld()) {
                            release()
                        }
                    }
                }
            }
        }
    }

    private fun checkInputAndCompressFile(inputUri: Uri?, outputUri: Uri?, format: CompressionOrArchiveFormat, progressRef: MutableFloatState, resultCodeRef: MutableIntState, blockSize: Int = -1, compressionLevel: Int = -1, password: String? = null): Job? {
        return if (outputUri == null) {
            (null)
        } else if (inputUri == null || (!areOfSupportedScheme(inputUri, outputUri)) || (inputUri == outputUri) || (!noneOfThemIsFileUriOfMyFileOrDir(this, inputUri, outputUri))) {
            showErrorMsgAndNotifyUserAboutUnknownError(this, 1, Intent(this, FileCompressorActivity::class.java))
            (null)
        } else {
            compressFile(
                inputUri,
                outputUri,
                format,
                progressRef,
                resultCodeRef,
                blockSize,
                compressionLevel,
                password
            )
        }
    }
}