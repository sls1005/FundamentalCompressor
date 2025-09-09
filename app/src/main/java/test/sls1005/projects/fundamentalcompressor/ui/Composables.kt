package test.sls1005.projects.fundamentalcompressor.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.sls1005.projects.fundamentalcompressor.R

@Composable
internal fun PlaceVerticallyCentrally(block: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(0.dp)
    ) { block() }
}

@Composable
internal fun PlaceVerticallyFromStart(block: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.padding(0.dp)
    ) { block() }
}

@Composable
internal inline fun CompressorOrDecompressorUILayout(topStart: @Composable ColumnScope.() -> Unit, topEnd: @Composable ColumnScope.() -> Unit, middle: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(0.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(0.5f).fillMaxHeight().padding(0.dp)
            ) {
                topStart()
                middle()
            }
            Column(
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(0.5f).fillMaxHeight().padding(0.dp)
            ) {
                topEnd()
            }
        }
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(0.dp)
        ) {
            bottom()
        }
    } else {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(0.dp)
        ) {
            topStart()
            topEnd()
            middle()
            bottom()
        }
    }
}

@Composable
internal fun CardWithTitle(title: String, modifier: Modifier, block: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = modifier) {
        Text(
            title,
            fontSize = 26.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 5.dp)
        )
        Column(modifier = Modifier.padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 5.dp)) {
            block()
        }
    }
}

@Composable
internal fun CardWithTitle(title: String, block: @Composable ColumnScope.() -> Unit) {
    CardWithTitle(title, Modifier.fillMaxWidth().padding(10.dp), block)
}

@Composable
internal inline fun PasswordInputField(passwordState: TextFieldState, label: String, modifier: Modifier) {
    OutlinedSecureTextField(
        passwordState,
        textStyle = TextStyle(fontSize = 18.sp),
        label = { Text(label) },
        textObfuscationMode = TextObfuscationMode.Hidden,
        modifier = modifier
    )
}

@Composable
internal inline fun PasswordInputField(passwordState: TextFieldState, label: String) {
    PasswordInputField(passwordState, label, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp))
}

@Composable
internal inline fun PasswordInputField(passwordState: TextFieldState) {
    PasswordInputField(passwordState, stringResource(id = R.string.password))
}

@Composable
internal fun PasswordSettingFields(passwordState: TextFieldState, passwordConfirmationState: TextFieldState) {
    PasswordInputField(passwordState)
    PasswordInputField(passwordConfirmationState, stringResource(R.string.confirm_password), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 10.dp))
}

@Composable
internal inline fun DocTitle(id: Int, modifier: Modifier) {
    Text(
        stringResource(id = id),
        fontSize = 25.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
internal inline fun DocTitle(id: Int) {
    DocTitle(id, modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 5.dp))
}

@Composable
internal inline fun DocSubtitle(id: Int, modifier: Modifier) {
    Text(
        stringResource(id = id),
        fontSize = 24.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
internal inline fun DocSubtitle(id: Int) {
    DocSubtitle(id, modifier = Modifier.padding(start = 25.dp, end = 25.dp, top = 10.dp, bottom = 5.dp))
}

@Composable
internal inline fun DocParagraph(id: Int, modifier: Modifier) {
    Text(
        stringResource(id = id),
        fontSize = 24.sp,
        lineHeight = 26.sp,
        modifier = modifier
    )
}

@Composable
internal inline fun DocParagraph(id: Int) {
    DocParagraph(id, modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 5.dp, bottom = 5.dp))
}
