package com.bigslushie.burrow

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

const val pairingCodeLength = 10
private const val maxHostAddressLength = 15
private const val pairingCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val hostCheckInterval = 10.seconds
private val modelChangeCheckInterval = 2.seconds

enum class Screen { Loading, Pairing, Paired }

data class AppUiState(
    val screen: Screen = Screen.Loading,
    val hostAddress: String = "",
    val code: String = "",
    val isPairing: Boolean = false,
    @StringRes val error: Int? = null,
    val host: HostStatus = HostStatus.Offline,
    val models: ModelCatalog? = null,
    @StringRes val modelError: Int? = null,
)

private fun ModelCommand.error(): Int? = when (this) {
    ModelCommand.Started -> null
    ModelCommand.Busy -> R.string.model_error_busy
    ModelCommand.LmStudioClosed -> R.string.model_error_lm_studio
    ModelCommand.Failed -> R.string.model_error_failed
}

class AppViewModel(
    private val repository: PairingRepository,
    private val hostClient: HostClient,
    private val deviceName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val screen = if (repository.current().isPaired) Screen.Paired else Screen.Pairing
            _state.update { it.copy(screen = screen) }
        }
    }

    suspend fun watchHost() {
        while (true) {
            state.first { it.screen == Screen.Paired }
            refreshHost()
            val changing = state.value.host.let { it is HostStatus.Loading || it is HostStatus.Unloading }
            delay(if (changing) modelChangeCheckInterval else hostCheckInterval)
        }
    }

    suspend fun refreshHost() {
        val host = hostClient.status(repository.current())
        if (host == HostStatus.Unpaired) {
            repository.clear()
            _state.update { AppUiState(screen = Screen.Pairing) }
            return
        }
        _state.update { it.copy(host = host) }
    }

    fun fetchModels() {
        _state.update { it.copy(models = null, modelError = null) }
        viewModelScope.launch {
            val models = hostClient.models(repository.current())
            _state.update { it.copy(models = models) }
        }
    }

    fun loadModel(model: ModelOption, contextLength: Int) {
        runModelCommand { record -> hostClient.load(record, model, contextLength) }
    }

    fun unloadModel() {
        runModelCommand { record -> hostClient.unload(record) }
    }

    private fun runModelCommand(command: suspend (PairingRecord) -> ModelCommand) {
        _state.update { it.copy(modelError = null) }
        viewModelScope.launch {
            val outcome = command(repository.current())
            _state.update { it.copy(modelError = outcome.error()) }
            refreshHost()
        }
    }

    fun unpair() {
        viewModelScope.launch {
            repository.clear()
            _state.update { AppUiState(screen = Screen.Pairing) }
        }
    }

    fun onHostAddressChange(input: String) {
        val hostAddress = input.replace(',', '.').filter { it in '0'..'9' || it == '.' }.take(maxHostAddressLength)
        _state.update { it.copy(hostAddress = hostAddress, error = null) }
    }

    fun onCodeChange(input: String) {
        val code = input.uppercase().filter { it in pairingCodeAlphabet }.take(pairingCodeLength)
        _state.update { it.copy(code = code, error = null) }
    }

    fun pair() {
        val code = _state.value.code
        val hostAddress = _state.value.hostAddress
        if (code.length != pairingCodeLength || !isHostAddress(hostAddress) || _state.value.isPairing) return
        _state.update { it.copy(isPairing = true, error = null) }
        viewModelScope.launch {
            when (val outcome = hostClient.pair(hostAddress, code, deviceName)) {
                is PairingOutcome.Paired -> {
                    repository.save(outcome.record)
                    _state.update { AppUiState(screen = Screen.Paired) }
                }
                PairingOutcome.Rejected -> _state.update { it.copy(isPairing = false, error = R.string.pair_error_rejected) }
                PairingOutcome.Unreachable -> _state.update { it.copy(isPairing = false, error = R.string.pair_error_unreachable) }
            }
        }
    }

    companion object {
        val factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as BurrowApplication
                AppViewModel(application.pairingRepository, application.hostClient, application.deviceName)
            }
        }
    }
}
