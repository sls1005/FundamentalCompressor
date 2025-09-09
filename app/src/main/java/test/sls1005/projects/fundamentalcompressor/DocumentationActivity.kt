package test.sls1005.projects.fundamentalcompressor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.sls1005.projects.fundamentalcompressor.ui.theme.FundamentalCompressorTheme
import test.sls1005.projects.fundamentalcompressor.ui.DocTitle
import test.sls1005.projects.fundamentalcompressor.ui.DocSubtitle
import test.sls1005.projects.fundamentalcompressor.ui.DocParagraph

class DocumentationActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FundamentalCompressorTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(stringResource(id = R.string.documentation), fontWeight = FontWeight.Bold)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())
                    ) {
                        (remember {
                            arrayOf( /*type: Array<Pair<Int, Array<Pair<Int, IntArray>>>>*/
                                R.string.doc_title1 to arrayOf(
                                    R.string.doc_title1_1 to intArrayOf(R.string.doc_text1_1_1),
                                    R.string.doc_title1_2 to intArrayOf(R.string.doc_text1_2_1)
                                ),
                                R.string.doc_title2 to arrayOf(
                                    R.string.doc_title2_1 to intArrayOf(R.string.doc_text2_1_1),
                                    R.string.doc_title2_2 to intArrayOf(R.string.doc_text2_2_1),
                                    R.string.doc_title2_3 to intArrayOf(R.string.doc_text2_3_1)
                                )
                            )
                        }).forEach {
                            val (titleId, subtree) = it
                            DocTitle(titleId)
                            if (titleId == R.string.doc_title1) {
                                DocParagraph(R.string.doc_text1_1)
                            }
                            subtree.forEach {
                                val (subtitleId, ids) = it
                                DocSubtitle(subtitleId)
                                for (id in ids) {
                                    DocParagraph(id)
                                }
                            }
                        }
                        DocTitle(R.string.doc_title_appendix_1)
                        DocParagraph(R.string.doc_text_appendix_1_1)
                        DocTitle(R.string.about)
                        Text(
                            buildAnnotatedString {
                                val url = "https://github.com/sls1005/FundamentalCompressor"
                                append(getString(R.string.license_info_part1))
                                withLink(
                                    LinkAnnotation.Url(url, styles = TextLinkStyles(SpanStyle(color = Color(0xFF3792FA)))),
                                ) { append(url) }
                                append(getString(R.string.license_info_part2))
                            },
                            fontSize = 24.sp,
                            lineHeight = 26.sp,
                            modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 5.dp, bottom = 5.dp)
                        )
                        TextButton(
                            onClick = {
                                startActivity(
                                    Intent(this@DocumentationActivity, ShowOpenSourceLibrariesActivity::class.java)
                                )
                            }, modifier = Modifier.fillMaxWidth().padding(10.dp)
                        ) {
                            Text(stringResource(id = R.string.doc_activity_button_show_libraries), fontSize = 20.sp)
                        }
                    }
                }
            }
        }
    }
}