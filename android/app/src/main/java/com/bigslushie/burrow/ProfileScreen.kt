package com.bigslushie.burrow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val maxProfileNameLength = 40
private const val maxProfileDescriptionLength = 2_000

class ProfileViewModel(private val profileRepository: ProfileRepository, private val pictureStore: PictureStore) : ViewModel() {
    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _picture = MutableStateFlow<ImageBitmap?>(null)
    val picture: StateFlow<ImageBitmap?> = _picture.asStateFlow()

    init {
        viewModelScope.launch { _picture.value = pictureStore.load(profilePictureId) }
    }

    fun save(name: String, description: String) {
        viewModelScope.launch { profileRepository.save(Profile(name.trim(), description.trim())) }
    }

    fun setPicture(source: Uri) {
        viewModelScope.launch {
            if (pictureStore.save(profilePictureId, source)) _picture.value = pictureStore.load(profilePictureId)
        }
    }

    companion object {
        val factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as BurrowApplication
                ProfileViewModel(application.profileRepository, application.pictureStore)
            }
        }
    }
}

@Composable
fun ProfileTab(host: HostStatus, profile: Profile, onSave: (String, String) -> Unit, onPicture: (Uri) -> Unit, onUnpair: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { chosen -> chosen?.let(onPicture) }
    var name by rememberSaveable(profile) { mutableStateOf(profile.name) }
    var description by rememberSaveable(profile) { mutableStateOf(profile.description) }
    var confirmingUnpair by remember { mutableStateOf(false) }
    val changed = name.trim() != profile.name || description.trim() != profile.description
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.onBackground,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        cursorColor = MaterialTheme.colorScheme.onBackground,
        focusedLabelColor = MaterialTheme.colorScheme.onBackground,
    )

    Column(Modifier.fillMaxSize().imePadding()) {
        CenteredHeader(stringResource(R.string.tab_profile), host)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            UserAvatar(
                profile = profile,
                size = 112.dp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            Text(
                text = stringResource(R.string.choose_picture),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 16.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(maxProfileNameLength) },
                label = { Text(stringResource(R.string.field_your_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(maxProfileDescriptionLength) },
                label = { Text(stringResource(R.string.field_about_you)) },
                minLines = 5,
                visualTransformation = liveFormatting(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            PillButton(text = stringResource(R.string.save), enabled = changed, onClick = { onSave(name, description) })
            Spacer(Modifier.height(32.dp))
            TextButton(onClick = { confirmingUnpair = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.unpair_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    onUnpair()
                }) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
