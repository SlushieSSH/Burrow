package com.bigslushie.burrow

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun StorageGate(content: @Composable () -> Unit) {
    val application = LocalContext.current.applicationContext as BurrowApplication
    val keys by application.keyState.collectAsStateWithLifecycle()
    val problems by application.storageHealth.problems.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val act: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    action()
                } finally {
                    busy = false
                }
            }
        }
    }
    LaunchedEffect(application) { application.openStorage() }

    when (keys) {
        null -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        is KeyState.Ready -> Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = if (problems.isEmpty()) Modifier.fillMaxSize() else Modifier.fillMaxSize().clearAndSetSemantics {}) { content() }
            if (problems.isNotEmpty()) {
                UnreadableDataScreen(
                    problems = problems,
                    busy = busy,
                    onTryAgain = { act { application.checkStorageAgain() } },
                    onReset = { kind -> act { application.resetStorage(kind) } },
                )
            }
        }
        KeyState.KeysetUnreadable -> KeysProblemScreen(
            body = R.string.keys_unreadable_body,
            busy = busy,
            onTryAgain = { act { application.openStorage() } },
            onReset = {
                act {
                    application.eraseEverything()
                    application.openStorage()
                }
            },
        )
        KeyState.KeystoreUnavailable -> KeysProblemScreen(
            body = R.string.keystore_unavailable_body,
            busy = busy,
            onTryAgain = { act { application.openStorage() } },
            onReset = null,
        )
    }
}

@Composable
private fun UnreadableDataScreen(problems: Set<StoredData>, busy: Boolean, onTryAgain: () -> Unit, onReset: (StoredData) -> Unit) {
    var confirming by rememberSaveable { mutableStateOf<StoredData?>(null) }
    ProblemLayout(title = R.string.storage_problem_title, body = R.string.storage_problem_body, busy = busy, onTryAgain = onTryAgain) {
        StoredData.entries.filter { it in problems }.forEach { kind ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(kind.label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirming = kind }, enabled = !busy) { Text(stringResource(R.string.storage_reset)) }
            }
        }
    }
    val pending = confirming
    if (pending != null) {
        ConfirmReset(
            question = pending.resetQuestion,
            detail = pending.resetDetail,
            onConfirm = {
                confirming = null
                onReset(pending)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun KeysProblemScreen(@StringRes body: Int, busy: Boolean, onTryAgain: () -> Unit, onReset: (() -> Unit)?) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ProblemLayout(title = R.string.keys_problem_title, body = body, busy = busy, onTryAgain = onTryAgain) {
        if (onReset != null) {
            TextButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.keys_reset))
            }
        }
    }
    if (confirming && onReset != null) {
        ConfirmReset(
            question = R.string.keys_reset_question,
            detail = R.string.keys_reset_detail,
            onConfirm = {
                confirming = false
                onReset()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun ProblemLayout(@StringRes title: Int, @StringRes body: Int, busy: Boolean, onTryAgain: () -> Unit, actions: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            actions()
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onTryAgain,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.storage_try_again))
            }
        }
    }
}

@Composable
private fun ConfirmReset(@StringRes question: Int, @StringRes detail: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(question)) },
        text = { Text(stringResource(detail)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
