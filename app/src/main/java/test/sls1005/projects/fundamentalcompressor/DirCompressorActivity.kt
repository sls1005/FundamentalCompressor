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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.text.toCharArray
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme
import test.sls1005.projects.fundamentalcompressor.ui.CardWithTitle
import test.sls1005.projects.fundamentalcompressor.ui.PasswordSettingFields
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyCentrally
import test.sls1005.projects.fundamentalcompressor.ui.PlaceVerticallyFromStart
import test.sls1005.projects.fundamentalcompressor.util.functions.concatenateSmallNumberOfStringsOnAnotherThread
import test.sls1005.projects.fundamentalcompressor.util.functions.jobIsCompletedOrCancelled
import test.sls1005.projects.fundamentalcompressor.util.functions.omitAfterFirstFewRecordsConcurrently
import test.sls1005.projects.fundamentalcompressor.util.functions.omitBeforeLastFewRecordsConcurrently
import test.sls1005.projects.fundamentalcompressor.util.CallbackInvokerInputStream

class DirCompressorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createLogChannelIfApplicable(this)
        setContent {
            FundamentalCompressorTheme {
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }
                var selectedArchiveFormat by remember { mutableStateOf(CompressionOrArchiveFormat.ZIP) }
                var selectedAdditionalCompressionFormat by remember { mutableStateOf(CompressionOrArchiveFormat.XZ) }
                var shouldCreateTarball by remember { mutableStateOf(true) }
                val mimeType by remember {
                    derivedStateOf {
                        (if ((selectedArchiveFormat == CompressionOrArchiveFormat.TAR) && shouldCreateTarball) {
                            selectedAdditionalCompressionFormat
                        } else {
                            selectedArchiveFormat
                        }).mimeType
                    }
                }
                var showsAdvancedOptions by remember { mutableStateOf(false) }
                var shouldUsePassword by remember { mutableStateOf(false) }
                var compressionLevel by remember { mutableIntStateOf(5) }
                var blockSize by remember { mutableIntStateOf(5) }
                val password1 = rememberTextFieldState()
                val password2 = rememberTextFieldState()
                var currentTask by remember { mutableStateOf<Job?>(null) }
                val taskStatus = remember { mutableIntStateOf(0) } // If 1: running, 2: completed, -1: cancelled, -2: the last result was error.
                val isRunningTask by remember {
                    derivedStateOf { taskStatus.intValue == 1 }
                }
                val taskIsCompleted by remember {
                    derivedStateOf { taskStatus.intValue == 2 }
                }
                var inputDirUri by remember { mutableStateOf<Uri?>(null) }
                val inputDirName by remember {
                    derivedStateOf {
                        inputDirUri?.let {
                            DocumentFile.fromTreeUri(this@DirCompressorActivity, it)?.name ?: ""
                        } ?: ""
                    }
                }
                val inputDirSize by remember {
                    derivedStateOf {
                        inputDirUri?.let {
                            DocumentFile.fromTreeUri(this@DirCompressorActivity, it)?.length() // ?: null
                        } ?: -1L
                    }
                }
                val inputDirSizeStr by remember {
                    derivedStateOf {
                        if (inputDirSize == -1L) {
                            getString(R.string.unknown)
                        } else {
                            Formatter.formatShortFileSize(this@DirCompressorActivity, inputDirSize)
                        }
                    }
                }
                val selectInputDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                    if (it != null) {
                        if (jobIsCompletedOrCancelled(currentTask)) {
                            currentTask = null
                            taskStatus.intValue = 0
                        }
                        inputDirUri = it
                    }
                }
                val createOutputFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType),
                    when (selectedArchiveFormat) {
                        CompressionOrArchiveFormat.TAR -> (
                            if (shouldCreateTarball) {
                                when (selectedAdditionalCompressionFormat) {
                                    CompressionOrArchiveFormat.BZIP2 -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(
                                            inputDirUri,
                                            outputFileUri,
                                            taskStatus,
                                            CompressionOrArchiveFormat.BZIP2,
                                            blockSize = if (showsAdvancedOptions) { blockSize } else { -1 }
                                        )
                                    }
                                    CompressionOrArchiveFormat.GZIP -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(
                                            inputDirUri,
                                            outputFileUri,
                                            taskStatus,
                                            CompressionOrArchiveFormat.GZIP,
                                            compressionLevel = if (showsAdvancedOptions) { compressionLevel } else { -1 }
                                        )
                                    }
                                    CompressionOrArchiveFormat.LZMA -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(inputDirUri, outputFileUri, taskStatus, CompressionOrArchiveFormat.LZMA)
                                    }
                                    CompressionOrArchiveFormat.LZ4 -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(inputDirUri, outputFileUri, taskStatus, CompressionOrArchiveFormat.LZ4)
                                    }
                                    CompressionOrArchiveFormat.XZ -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(inputDirUri, outputFileUri, taskStatus, CompressionOrArchiveFormat.XZ)
                                    }
                                    CompressionOrArchiveFormat.ZSTD -> { outputFileUri ->
                                        currentTask = checkInputAndStartCreatingTarball(inputDirUri, outputFileUri, taskStatus, CompressionOrArchiveFormat.ZSTD)
                                    }
                                    else -> { _ -> }
                                }
                            } else { // archive only
                                { outputFileUri ->
                                    currentTask = checkInputAndStartArchiving(inputDirUri, outputFileUri, taskStatus, CompressionOrArchiveFormat.TAR)
                                }
                            }
                        )
                        CompressionOrArchiveFormat.SEVEN_Z -> { outputFileUri ->
                            currentTask = checkInputAndStartArchiving(
                                inputDirUri,
                                outputFileUri,
                                taskStatus,
                                CompressionOrArchiveFormat.SEVEN_Z,
                                password = if (shouldUsePassword) { password1.text.toString() } else { null }
                            )
                        }
                        CompressionOrArchiveFormat.ZIP -> { outputFileUri ->
                            currentTask = checkInputAndStartArchiving(
                                inputDirUri,
                                outputFileUri,
                                taskStatus,
                                CompressionOrArchiveFormat.ZIP,
                                compressionLevel = if (showsAdvancedOptions) { compressionLevel } else { -1 }
                            )
                        }
                        else -> { _ -> }
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
                        inputDirUri?.also { inputUriLocal ->
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                            ) {
                                PlaceVerticallyCentrally {
                                    if (isRunningTask) {
                                        Text(stringResource(id = R.string.folder_is_being_archived), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        Text(
                                            inputDirName,
                                            fontSize = 24.sp,
                                            lineHeight = 26.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 5.dp)
                                        )
                                        LinearProgressIndicator(modifier = Modifier.padding(start = 25.dp, end = 25.dp, top = 5.dp, bottom = 5.dp))
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
                                        Text(stringResource(id = R.string.folder_has_been_selected), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp))
                                        TextButton(
                                            onClick = {
                                                selectInputDir.launch(null)
                                            },
                                            shape = RectangleShape,
                                            modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = if (taskIsCompleted) { 15.dp } else { 2.dp })
                                        ) {
                                            Text(stringResource(R.string.file_or_dir_name_and_size, inputDirName, inputDirSizeStr), fontSize = 24.sp, lineHeight = 26.sp)
                                        }
                                        if (!taskIsCompleted) {
                                            Text(stringResource(id = R.string.dir_compressor_info1), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 15.dp))
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
                                            onClick = { (this@DirCompressorActivity).finish() },
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 15.dp)
                                        ) {
                                            Text(stringResource(id = R.string.exit), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    }
                                }
                            }
                            CardWithTitle(stringResource(id = R.string.options)) {
                                val nameOfCurrentlySelectedArchiveFormat by remember { derivedStateOf { toDisplayName(selectedArchiveFormat) } }
                                var menuExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = menuExpanded,
                                    onExpandedChange = { menuExpanded = it },
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp, bottom = 10.dp)
                                ) {
                                    OutlinedTextField(
                                        nameOfCurrentlySelectedArchiveFormat,
                                        readOnly = true,
                                        onValueChange = { /* Empty */ },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                        label = { Text(stringResource(id = R.string.compression_or_archive_format)) },
                                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isRunningTask)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        (remember {
                                            arrayOf(
                                                CompressionOrArchiveFormat.SEVEN_Z,
                                                CompressionOrArchiveFormat.TAR,
                                                CompressionOrArchiveFormat.ZIP
                                            )
                                        }).forEach { format ->
                                            DropdownMenuItem(
                                                text = { Text(toDisplayName(format), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                onClick = {
                                                    selectedArchiveFormat = format
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
                                when (selectedArchiveFormat) {
                                    CompressionOrArchiveFormat.TAR -> PlaceVerticallyFromStart {
                                        Row(
                                            horizontalArrangement = Arrangement.Start,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 15.dp, top = 0.dp, bottom = 10.dp)
                                        ) {
                                            Checkbox(
                                                checked = shouldCreateTarball,
                                                onCheckedChange = {
                                                    shouldCreateTarball = it
                                                    if (jobIsCompletedOrCancelled(currentTask)) {
                                                        currentTask = null
                                                        taskStatus.intValue = 0
                                                    }
                                                },
                                                enabled = !isRunningTask,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                            Text(
                                                stringResource(id = R.string.dir_compressor_option_create_tarball),
                                                fontSize = 24.sp,
                                                lineHeight = 26.sp
                                            )
                                        }
                                        if (shouldCreateTarball) {
                                            val nameOfCurrentlySelectedCompressionFormat by remember { derivedStateOf { toDisplayName(selectedAdditionalCompressionFormat) } }
                                            var menuExpanded by remember { mutableStateOf(false) }
                                            ExposedDropdownMenuBox(
                                                expanded = menuExpanded,
                                                onExpandedChange = { menuExpanded = it },
                                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 10.dp)
                                            ) {
                                                OutlinedTextField(
                                                    nameOfCurrentlySelectedCompressionFormat,
                                                    readOnly = true,
                                                    onValueChange = { /* Empty */ },
                                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                                    label = { Text(stringResource(id = R.string.additional_compression_format)) },
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
                                                            CompressionOrArchiveFormat.ZSTD
                                                        )
                                                    }).forEach { format ->
                                                        DropdownMenuItem(
                                                            text = { Text(toDisplayName(format), fontSize = 20.sp, lineHeight = 22.sp, modifier = Modifier.padding(10.dp)) },
                                                            onClick = {
                                                                selectedAdditionalCompressionFormat = format
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
                                    CompressionOrArchiveFormat.SEVEN_Z -> PlaceVerticallyFromStart {
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
                                    else -> Unit
                                }
                                PlaceVerticallyFromStart {
                                    val hasAdvancedOptions by remember {
                                        derivedStateOf {
                                            when (selectedArchiveFormat) {
                                                CompressionOrArchiveFormat.BZIP2, CompressionOrArchiveFormat.GZIP, CompressionOrArchiveFormat.ZIP -> true
                                                CompressionOrArchiveFormat.TAR -> when (selectedAdditionalCompressionFormat) {
                                                    CompressionOrArchiveFormat.BZIP2, CompressionOrArchiveFormat.GZIP -> true
                                                    else -> false
                                                }
                                                else -> false
                                            }
                                        }
                                    }
                                    if (hasAdvancedOptions) {
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
                                            if (selectedArchiveFormat == CompressionOrArchiveFormat.ZIP || selectedAdditionalCompressionFormat == CompressionOrArchiveFormat.GZIP) {
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
                                                        label = { Text(stringResource(R.string.compression_level)) },
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
                                            if (selectedAdditionalCompressionFormat == CompressionOrArchiveFormat.BZIP2) {
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
                                                        label = { Text(stringResource(R.string.block_size)) },
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
                                        }
                                    }
                                }
                            }
                            if (!(isRunningTask || taskIsCompleted)) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                ) {
                                    PlaceVerticallyCentrally {
                                        val outputFileName by remember {
                                            derivedStateOf {
                                                inputDirName.let { inputDirNameLocal ->
                                                    val newExt = selectedArchiveFormat.let { archiveFormat ->
                                                        if ((archiveFormat == CompressionOrArchiveFormat.TAR) && shouldCreateTarball) {
                                                            "${archiveFormat.fileExtension}.${selectedAdditionalCompressionFormat.fileExtension}"
                                                        } else {
                                                            archiveFormat.fileExtension
                                                        }
                                                    }
                                                    if (inputDirNameLocal.isEmpty()) {
                                                        "output.$newExt"
                                                    } else {
                                                        "$inputDirNameLocal.$newExt"
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            stringResource(
                                                if ((selectedArchiveFormat == CompressionOrArchiveFormat.TAR) && (!shouldCreateTarball)) {
                                                    R.string.archiving_will_start
                                                } else {
                                                    R.string.compression_will_start
                                                }
                                            ),
                                            fontSize = 24.sp, lineHeight = 26.sp,
                                            modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 5.dp)
                                        )
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
                                            Text(stringResource(R.string.choose_output_path), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                                        }
                                    }
                                }
                            }
                        } ?: PlaceVerticallyCentrally {
                            Button(
                                onClick = {
                                    selectInputDir.launch(null)
                                },
                                modifier = Modifier.padding(15.dp)
                            ) {
                                Text(stringResource(R.string.select_input_folder), fontSize = 24.sp, lineHeight = 26.sp, modifier = Modifier.padding(5.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(2)
        super.onDestroy()
    }

    private suspend fun writeDirsAndFilesInto7zFile(dir: DocumentFile, dirPath: String, outputFile: SevenZOutputFile, fileLog: ArrayList<String>) {
        withContext(Dispatchers.Default) {
            if (dirPath.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileLog.add(dirPath)
                    val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                    showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.compressing), dirPath, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                }
                withContext(Dispatchers.IO) {
                    outputFile.putArchiveEntry(
                        SevenZArchiveEntry().also { entry ->
                            entry.name = dirPath
                            entry.setDirectory(true)
                            entry.setHasLastModifiedDate(true)
                            withContext(Dispatchers.Main) {
                                entry.setLastModifiedTime(FileTime.fromMillis(dir.lastModified()))
                            }
                        }
                    )
                    outputFile.closeArchiveEntry()
                }
            }
        }
        withContext(Dispatchers.Main) {
            dir.listFiles().forEach {
                ensureActive()
                if (it.isFile()) {
                    it.name?.also { fileName ->
                        val path = concatenateSmallNumberOfStringsOnAnotherThread(dirPath, fileName)
                        lifecycleScope.launch(Dispatchers.Main) {
                            fileLog.add(path)
                            val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                            showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.compressing), path, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                        }
                        withContext(Dispatchers.IO) {
                            outputFile.putArchiveEntry(
                                SevenZArchiveEntry().also { entry ->
                                    entry.name = path
                                    entry.setDirectory(false)
                                    entry.setHasLastModifiedDate(true)
                                    withContext(Dispatchers.Main) {
                                        entry.setLastModifiedTime(FileTime.fromMillis(it.lastModified()))
                                    }
                                }
                            )
                        }
                        contentResolver.openInputStream(it.uri)?.use { inputStream ->
                            withContext(Dispatchers.IO) {
                                outputFile.write(inputStream)
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        outputFile.closeArchiveEntry()
                    }
                } else if (it.isDirectory()) {
                    it.name?.also { name ->
                        writeDirsAndFilesInto7zFile(it, concatenateSmallNumberOfStringsOnAnotherThread(dirPath, name, "/"), outputFile, fileLog)
                    }
                }
            }
        }
    }

    private suspend fun writeDirsAndFilesIntoTarStream(dir: DocumentFile, dirPath: String, stream: TarArchiveOutputStream, fileLog: ArrayList<String>) {
        withContext(Dispatchers.Default) {
            if (dirPath.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileLog.add(dirPath)
                    val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                    showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.archiving), dirPath, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                }
                withContext(Dispatchers.IO) {
                    stream.putArchiveEntry(
                        TarArchiveEntry(dirPath).also { entry ->
                            withContext(Dispatchers.Main) {
                                entry.setModTime(dir.lastModified())
                            }
                        }
                    )
                    stream.closeArchiveEntry()
                }
            }
        }
        withContext(Dispatchers.Main) {
            dir.listFiles().forEach {
                ensureActive()
                ensureActive()
                if (it.isFile()) {
                    it.name?.also { fileName ->
                        val path = concatenateSmallNumberOfStringsOnAnotherThread(dirPath, fileName)
                        lifecycleScope.launch(Dispatchers.Main) {
                            fileLog.add(path)
                            val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                            showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.archiving), path, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                        }
                        withContext(Dispatchers.IO) {
                            stream.putArchiveEntry(
                                TarArchiveEntry(path).also { entry ->
                                    withContext(Dispatchers.Main) {
                                        entry.setModTime(it.lastModified())
                                        entry.size = it.length()
                                    }
                                }
                            )
                        }
                        contentResolver.openInputStream(it.uri)?.use { inputStream ->
                            withContext(Dispatchers.IO) {
                                inputStream.transferTo(stream)
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        stream.closeArchiveEntry()
                    }
                } else if (it.isDirectory()) {
                    it.name?.also { name ->
                        writeDirsAndFilesIntoTarStream(it, concatenateSmallNumberOfStringsOnAnotherThread(dirPath, name, "/"), stream, fileLog)
                    }
                }
            }
        }
    }

    private suspend fun writeDirsAndFilesIntoZipStream(dir: DocumentFile, dirPath: String, stream: ZipOutputStream, fileLog: ArrayList<String>) {
        withContext(Dispatchers.Default) {
            if (dirPath.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileLog.add(dirPath)
                    val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                    showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.compressing), dirPath, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                }
                withContext(Dispatchers.IO) {
                    stream.putNextEntry(
                        ZipEntry(dirPath).also { entry ->
                            withContext(Dispatchers.Main) {
                                entry.time = dir.lastModified()
                            }
                        }
                    )
                    stream.closeEntry()
                }
            }
        }
        withContext(Dispatchers.Main) {
            dir.listFiles().forEach {
                ensureActive()
                if (it.isFile()) {
                    it.name?.also { fileName ->
                        val path = concatenateSmallNumberOfStringsOnAnotherThread(dirPath, fileName)
                        lifecycleScope.launch(Dispatchers.Main) {
                            fileLog.add(path)
                            val records = omitBeforeLastFewRecordsConcurrently(fileLog)
                            showNotificationInLogChannel(this@DirCompressorActivity, 2, 2.0, getString(R.string.compressing), path, records, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                        }
                        withContext(Dispatchers.IO) {
                            stream.putNextEntry(
                                ZipEntry(path).also { entry ->
                                    withContext(Dispatchers.Main) {
                                        entry.time = it.lastModified()
                                    }
                                }
                            )
                        }
                        contentResolver.openInputStream(it.uri)?.use { inputStream ->
                            withContext(Dispatchers.IO) {
                                inputStream.transferTo(stream)
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        stream.closeEntry()
                    }
                } else if (it.isDirectory()) {
                    it.name?.also { name ->
                        writeDirsAndFilesIntoZipStream(it, concatenateSmallNumberOfStringsOnAnotherThread(dirPath, name, "/"), stream, fileLog)
                    }
                }
            }
        }
    }

    private inline fun archive(inputUri: Uri, outputUri: Uri, resultCodeRef: MutableIntState, archiveFormat: CompressionOrArchiveFormat, additionalCompressionFormat: CompressionOrArchiveFormat? = null, compressionLevel: Int = -1, blockSize: Int = -1, password: String? = null): Job {
        return lifecycleScope.launch(Dispatchers.Main) {
            val cr = contentResolver
            val wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:archiver") // :? null
            val outputFileName = inferFileNameFromContentUri(outputUri, this@DirCompressorActivity).orEmpty()
            var errorFlag = false
            var cancellationFlag = false
            resultCodeRef.intValue = 1 // initialize and inform caller
            when (archiveFormat) {
                CompressionOrArchiveFormat.TAR -> run {
                    val fileLog = ArrayList<String>()
                    try {
                        cr.openOutputStream(outputUri)?.also { outputStream ->
                            withContext(Dispatchers.Default) {
                                BufferedOutputStream(outputStream).also { buffered ->
                                    TarArchiveOutputStream(
                                        if (additionalCompressionFormat == null) {
                                            buffered
                                        } else {
                                            when (additionalCompressionFormat) {
                                                CompressionOrArchiveFormat.BZIP2 -> run {
                                                    if (blockSize < 1 || blockSize > 9) {
                                                        BZip2CompressorOutputStream(buffered)
                                                    } else {
                                                        BZip2CompressorOutputStream(buffered, blockSize)
                                                    }
                                                }
                                                CompressionOrArchiveFormat.GZIP -> run {
                                                    if (compressionLevel < 1 || compressionLevel > 9) {
                                                        GZIPOutputStream(buffered)
                                                    } else {
                                                        object : GZIPOutputStream(buffered) {
                                                            init { this.def.setLevel(compressionLevel) }
                                                        }
                                                    }
                                                }
                                                CompressionOrArchiveFormat.LZ4 -> FramedLZ4CompressorOutputStream(buffered)
                                                CompressionOrArchiveFormat.LZMA -> LZMACompressorOutputStream(buffered)
                                                CompressionOrArchiveFormat.XZ -> XZCompressorOutputStream(buffered)
                                                CompressionOrArchiveFormat.ZSTD -> ZstdCompressorOutputStream(buffered)
                                                else -> buffered
                                            }
                                        }
                                    ).also { s ->
                                        withContext(Dispatchers.Main) {
                                            s.use { stream ->
                                                DocumentFile.fromTreeUri(this@DirCompressorActivity, inputUri)?.also { dir ->
                                                    wakeLock?.acquire(43200L)
                                                    lifecycleScope.launch(Dispatchers.Main) {
                                                        showMsg(this@DirCompressorActivity, getString(
                                                            if (archiveFormat == CompressionOrArchiveFormat.TAR) {
                                                                R.string.archiving_with_ellipsis
                                                            } else {
                                                                R.string.compressing_with_ellipsis
                                                            }
                                                        ))
                                                    }
                                                    val path = dir.name?.let({ dirName ->
                                                        concatenateSmallNumberOfStringsOnAnotherThread(dirName, "/")
                                                    }).orEmpty()
                                                    writeDirsAndFilesIntoTarStream(dir, path, stream, fileLog)
                                                }
                                            }
                                        }
                                    }
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
                                showNotificationAboutCancelledTask(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -1
                            }
                        } else {
                            yield()
                            if (errorFlag) {
                                showErrorMsgAndNotifyUserAboutUnknownError(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -2
                            } else {
                                showNotificationInLogChannel(this@DirCompressorActivity, 2, -1.0, getString(R.string.completed), outputFileName, omitAfterFirstFewRecordsConcurrently(fileLog), Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
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
                CompressionOrArchiveFormat.SEVEN_Z -> run {
                    val fileLog = ArrayList<String>()
                    val tempFile = File(cacheDir, "temp2.7z").also {
                        if (it.exists()) {
                            it.delete()
                        }
                    }
                    try {
                        (if (password == null) {
                            SevenZOutputFile(tempFile)
                        } else {
                            SevenZOutputFile(tempFile, password.toCharArray())
                        }).use { file ->
                            DocumentFile.fromTreeUri(this@DirCompressorActivity, inputUri)?.also { dir ->
                                wakeLock?.acquire(43200L)
                                lifecycleScope.launch(Dispatchers.Main) {
                                    showMsg(this@DirCompressorActivity, getString(R.string.compressing_with_ellipsis))
                                }
                                val path = dir.name?.let({ dirName ->
                                    concatenateSmallNumberOfStringsOnAnotherThread(dirName, "/")
                                }).orEmpty()
                                writeDirsAndFilesInto7zFile(dir, path, file, fileLog)
                            }
                        }
                        cr.openOutputStream(outputUri)?.use { dst ->
                            CallbackInvokerInputStream(tempFile.inputStream(), 0, 5, { ensureActive() }).use { src ->
                                withContext(Dispatchers.IO) {
                                    src.copyTo(dst)
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
                                showNotificationAboutCancelledTask(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -1
                            }
                        } else {
                            yield()
                            if (errorFlag) {
                                showErrorMsgAndNotifyUserAboutUnknownError(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -2
                            } else {
                                showNotificationInLogChannel(this@DirCompressorActivity, 2, -1.0, getString(R.string.completed), outputFileName, omitAfterFirstFewRecordsConcurrently(fileLog), Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
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
                    val fileLog = ArrayList<String>()
                    try {
                        cr.openOutputStream(outputUri)?.also { outputStream ->
                            ZipOutputStream(
                                BufferedOutputStream(outputStream)
                            ).use { stream ->
                                if (compressionLevel >= 1 && compressionLevel <= 9) {
                                    stream.setLevel(compressionLevel)
                                }
                                DocumentFile.fromTreeUri(this@DirCompressorActivity, inputUri)?.also { dir ->
                                    wakeLock?.acquire(43200L)
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        showMsg(this@DirCompressorActivity, getString(R.string.compressing_with_ellipsis))
                                    }
                                    val path = dir.name?.let({ dirName ->
                                        concatenateSmallNumberOfStringsOnAnotherThread(dirName, "/")
                                    }).orEmpty()
                                    writeDirsAndFilesIntoZipStream(dir, path, stream, fileLog)
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
                                showNotificationAboutCancelledTask(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -1
                            }
                        } else {
                            yield()
                            if (errorFlag) {
                                showErrorMsgAndNotifyUserAboutUnknownError(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                                resultCodeRef.intValue = -2
                            } else {
                                showNotificationInLogChannel(this@DirCompressorActivity, 2, -1.0, getString(R.string.completed), outputFileName, omitAfterFirstFewRecordsConcurrently(fileLog), Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
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
                    showErrorMsgAndNotifyUserAboutUnknownError(this@DirCompressorActivity, 2, Intent(this@DirCompressorActivity, DirCompressorActivity::class.java))
                    resultCodeRef.intValue = -2
                }
            }
        }
    }

    private fun checkInputAndStartArchiving(inputUri: Uri?, outputUri: Uri?, resultCodeRef: MutableIntState, archiveFormat: CompressionOrArchiveFormat, additionalCompressionFormat: CompressionOrArchiveFormat? = null, compressionLevel: Int = -1, blockSize: Int = -1, password: String? = null): Job? {
        return if (outputUri == null) {
            (null)
        } else if (inputUri == null || (!areOfSupportedScheme(inputUri, outputUri)) || (!noneOfThemIsFileUriOfMyFileOrDir(this, inputUri, outputUri))) {
            showErrorMsgAndNotifyUserAboutUnknownError(this, 2, Intent(this, DirCompressorActivity::class.java))
            (null)
        } else {
            archive(
                inputUri,
                outputUri,
                resultCodeRef,
                archiveFormat,
                additionalCompressionFormat,
                compressionLevel,
                blockSize,
                password
            )
        }
    }

    private inline fun checkInputAndStartCreatingTarball(inputUri: Uri?, outputUri: Uri?, resultCodeRef: MutableIntState, compressionFormat: CompressionOrArchiveFormat, compressionLevel: Int = -1, blockSize: Int = -1): Job? {
        return checkInputAndStartArchiving(
            inputUri,
            outputUri,
            resultCodeRef,
            archiveFormat = CompressionOrArchiveFormat.TAR,
            additionalCompressionFormat = compressionFormat,
            compressionLevel = compressionLevel,
            blockSize = blockSize
        )
    }
}
