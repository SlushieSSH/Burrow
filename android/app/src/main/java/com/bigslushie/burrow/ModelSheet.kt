package com.bigslushie.burrow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val contextChoices = listOf(4_096, 8_192, 16_384, 32_768, 65_536, 131_072, 262_144)
private const val defaultContextLength = 8_192

val LocalOpenModels = compositionLocalOf<() -> Unit> { {} }

class ModelSheetActions(
    val onRefresh: () -> Unit,
    val onLoad: (ModelOption, Int) -> Unit,
    val onUnload: () -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(host: HostStatus, catalog: ModelCatalog?, error: Int?, actions: ModelSheetActions) {
    LaunchedEffect(Unit) { actions.onRefresh() }
    ModalBottomSheet(onDismissRequest = actions.onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.models_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            HostStatusLine(host, Modifier.padding(top = 6.dp), opensModels = false)
            Spacer(Modifier.height(20.dp))
            when (catalog) {
                null -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
                }
                ModelCatalog.LmStudioClosed -> SheetMessage(R.string.model_error_lm_studio)
                ModelCatalog.PortTaken -> SheetMessage(R.string.model_error_port)
                ModelCatalog.LmStudioOpenToNetwork -> SheetMessage(R.string.model_error_open_to_network)
                ModelCatalog.Offline -> SheetMessage(R.string.status_offline)
                is ModelCatalog.Available -> ModelChoices(host, catalog.models, error, actions)
            }
        }
    }
}

@Composable
private fun SheetMessage(message: Int) {
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChoices(host: HostStatus, models: List<ModelOption>, error: Int?, actions: ModelSheetActions) {
    if (models.isEmpty()) {
        SheetMessage(R.string.models_empty)
        return
    }
    val loadedName = (host as? HostStatus.Ready)?.modelName
    var selectedKey by rememberSaveable { mutableStateOf(models.firstOrNull { it.name == loadedName }?.key ?: models.first().key) }
    val selected = models.firstOrNull { it.key == selectedKey } ?: models.first()
    val choices = contextChoices.filter { selected.maxContextLength <= 0 || it <= selected.maxContextLength }
    val loadedContext = (host as? HostStatus.Ready)?.contextLength?.takeIf { it in choices }
    var contextLength by rememberSaveable { mutableIntStateOf(loadedContext ?: defaultContextLength) }
    if (contextLength !in choices) contextLength = choices.last()
    val changing = host is HostStatus.Loading || host is HostStatus.Unloading

    models.forEach { model ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { selectedKey = model.key }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = model.key == selected.key,
                onClick = { selectedKey = model.key },
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onBackground),
            )
            Text(model.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.context_window), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == contextLength,
                onClick = { contextLength = choice },
                label = { Text(stringResource(R.string.context_size, choice / 1_024)) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    labelColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    error?.let { message ->
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
    PillButton(
        text = stringResource(if (host is HostStatus.Loading) R.string.model_loading else R.string.model_load),
        enabled = !changing,
        onClick = { actions.onLoad(selected, contextLength) },
    )
    if (host is HostStatus.Ready) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = actions.onUnload, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.model_unload), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
