package com.bigslushie.burrow

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val maxNameLength = 40
private const val maxGreetingLength = 2_000
private const val maxPersonalityLength = 4_000
private const val maxScenarioLength = 3_000
private const val maxExamplesLength = 4_000
private const val maxSystemPromptLength = 4_000
private const val previewSide = 512
private const val stepCount = 5

@Composable
fun CharacterEditor(existing: Character?, onSave: suspend (CharacterDraft) -> Unit, onClose: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var greeting by rememberSaveable { mutableStateOf(existing?.greeting.orEmpty()) }
    var personality by rememberSaveable { mutableStateOf(existing?.personality.orEmpty()) }
    var scenario by rememberSaveable { mutableStateOf(existing?.scenario.orEmpty()) }
    var exampleDialogue by rememberSaveable { mutableStateOf(existing?.exampleDialogue.orEmpty()) }
    var replyLength by rememberSaveable { mutableStateOf(existing?.replyLength ?: ReplyLength.Medium) }
    var creativity by rememberSaveable { mutableFloatStateOf(existing?.creativity ?: defaultCreativity) }
    var systemPrompt by rememberSaveable { mutableStateOf(existing?.systemPrompt.orEmpty()) }
    var picture by rememberSaveable { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val goBack = { if (step > 0) step -= 1 else onClose() }
    BackHandler(enabled = !saving) { goBack() }

    val stepValid = when (step) {
        0 -> name.isNotBlank()
        1 -> greeting.isNotBlank()
        2 -> personality.isNotBlank()
        else -> true
    }
    val lastStep = step == stepCount - 1
    val finishLabel = if (existing == null) R.string.create_button else R.string.save

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack, enabled = !saving) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
            }
            StepBar(step, Modifier.weight(1f).padding(end = 20.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            when (step) {
                0 -> NameAndPicture(existing, name, picture, onNameChange = { name = it.take(maxNameLength) }, onPicture = { picture = it })
                1 -> LongTextStep(R.string.step_greeting, greeting, minLines = 5) { greeting = it.take(maxGreetingLength) }
                2 -> LongTextStep(R.string.step_personality, personality, minLines = 9) { personality = it.take(maxPersonalityLength) }
                3 -> {
                    StepTitle(R.string.step_scenario)
                    LabeledField(R.string.field_scenario, scenario, minLines = 4) { scenario = it.take(maxScenarioLength) }
                    Spacer(Modifier.height(16.dp))
                    LabeledField(R.string.field_examples, exampleDialogue, minLines = 6) { exampleDialogue = it.take(maxExamplesLength) }
                    Spacer(Modifier.height(16.dp))
                    LabeledField(R.string.field_system_prompt, systemPrompt, minLines = 4) { systemPrompt = it.take(maxSystemPromptLength) }
                }
                else -> StyleStep(replyLength, creativity, onLength = { replyLength = it }, onCreativity = { creativity = it })
            }
        }
        Box(Modifier.padding(24.dp)) {
            PillButton(
                text = stringResource(if (lastStep) finishLabel else R.string.next),
                enabled = stepValid && !saving,
                onClick = {
                    if (!lastStep) {
                        step += 1
                        return@PillButton
                    }
                    saving = true
                    scope.launch {
                        onSave(CharacterDraft(name, greeting, personality, scenario, exampleDialogue, replyLength, creativity, systemPrompt, picture))
                        saving = false
                    }
                },
            )
        }
    }
}

@Composable
private fun StepBar(step: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(stepCount) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index <= step) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

@Composable
private fun StepTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 20.dp),
    )
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.onBackground,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.onBackground,
    focusedLabelColor = MaterialTheme.colorScheme.onBackground,
)

@Composable
private fun ColumnScope.NameAndPicture(existing: Character?, name: String, picture: Uri?, onNameChange: (String) -> Unit, onPicture: (Uri) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { chosen -> chosen?.let(onPicture) }
    val preview = rememberPreview(picture)
    StepTitle(R.string.step_name)
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            preview != null -> Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            existing != null && LocalCharacterPictures.current.containsKey(existing.id) -> CharacterPicture(existing, Modifier.fillMaxSize(), RoundedCornerShape(20.dp), 168.dp)
            else -> Icon(painterResource(R.drawable.ic_add_photo), contentDescription = stringResource(R.string.choose_picture), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
    }
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.field_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        colors = editorFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun liveFormatting() = LiveFormatting(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.outline)

@Composable
private fun LabeledField(label: Int, value: String, minLines: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        minLines = minLines,
        visualTransformation = liveFormatting(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = editorFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StyleStep(replyLength: ReplyLength, creativity: Float, onLength: (ReplyLength) -> Unit, onCreativity: (Float) -> Unit) {
    StepTitle(R.string.step_style)
    Text(stringResource(R.string.field_reply_length), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(10.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ReplyLength.entries.forEachIndexed { index, length ->
            SegmentedButton(
                selected = replyLength == length,
                onClick = { onLength(length) },
                shape = SegmentedButtonDefaults.itemShape(index, ReplyLength.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.background,
                    inactiveContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                icon = {},
            ) {
                Text(stringResource(length.label()))
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.field_creativity), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Slider(
        value = creativity,
        onValueChange = onCreativity,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.onBackground,
            activeTrackColor = MaterialTheme.colorScheme.onBackground,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.creativity_low), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.creativity_high), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ReplyLength.label(): Int = when (this) {
    ReplyLength.Short -> R.string.length_short
    ReplyLength.Medium -> R.string.length_medium
    ReplyLength.Long -> R.string.length_long
}

@Composable
private fun LongTextStep(title: Int, value: String, minLines: Int, onChange: (String) -> Unit) {
    StepTitle(title)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        minLines = minLines,
        visualTransformation = liveFormatting(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = editorFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
    )
}

@Composable
private fun rememberPreview(picture: Uri?): ImageBitmap? {
    val context = LocalContext.current
    var preview by remember(picture) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(picture) {
        val source = picture ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                    val longestSide = maxOf(info.size.width, info.size.height)
                    if (longestSide > previewSide) {
                        val scale = previewSide.toFloat() / longestSide
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }.asImageBitmap()
            } catch (e: IOException) {
                null
            }
        }
    }
    return preview
}
