package test.sls1005.projects.fundamentalcompressor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme

class CompressorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FundamentalCompressorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    ) {
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@CompressorActivity, FileCompressorActivity::class.java)) },
                            modifier = Modifier
                                .sizeIn(minWidth = 400.dp, minHeight = 100.dp)
                                .padding(10.dp)
                        ) {
                            Text("Compress a file", fontSize = 24.sp)
                        }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@CompressorActivity, DirCompressorActivity::class.java)) },
                            modifier = Modifier
                                .sizeIn(minWidth = 400.dp, minHeight = 100.dp)
                                .padding(10.dp)
                        ) {
                            Text("Compress a folder", fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}