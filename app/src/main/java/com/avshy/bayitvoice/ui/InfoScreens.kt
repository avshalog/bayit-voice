package com.avshy.bayitvoice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.avshy.bayitvoice.BuildConfig
import com.avshy.bayitvoice.R

/** Step-by-step guide to training a custom openWakeWord model (SPEC 6.1). */
@Composable
fun TrainGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notebookUrl = stringResource(R.string.guide_notebook_url)
    InfoScaffold(title = R.string.guide_title, onBack = onBack) {
        Text(stringResource(R.string.guide_intro))
        listOf(
            R.string.guide_step_1,
            R.string.guide_step_2,
            R.string.guide_step_3,
            R.string.guide_step_4,
            R.string.guide_step_5,
        ).forEachIndexed { index, step ->
            Text("${index + 1}. ${stringResource(step)}")
        }
        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notebookUrl))) }) {
            Text(stringResource(R.string.guide_open_notebook))
        }
        Text(stringResource(R.string.guide_tips_title), style = MaterialTheme.typography.titleMedium)
        listOf(
            R.string.guide_tip_phonetic,
            R.string.guide_tip_sounds,
            R.string.guide_tip_syllables,
            R.string.guide_tip_listen,
        ).forEach { tip -> Text("• ${stringResource(tip)}") }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    InfoScaffold(title = R.string.about_title, onBack = onBack) {
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.about_models_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_models_license))
        Text(stringResource(R.string.about_libraries_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_libraries))
    }
}

@Composable
private fun InfoScaffold(title: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}
