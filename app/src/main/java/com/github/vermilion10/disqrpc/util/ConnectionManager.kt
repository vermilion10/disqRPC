package com.github.vermilion10.disqrpc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection state shared between the service and the UI (same process). */
object ConnectionManager {
    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state = _state.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username = _username.asStateFlow()

    private val _currentPresence = MutableStateFlow<String?>(null)
    val currentPresence = _currentPresence.asStateFlow()

    /** Why the last session failed, shown in the UI; cleared on the next connect attempt. */
    private val _failureReason = MutableStateFlow<String?>(null)
    val failureReason = _failureReason.asStateFlow()

    fun updateState(newState: State, reason: String? = null) {
        _state.value = newState
        when (newState) {
            State.FAILED -> _failureReason.value = reason
            State.CONNECTING, State.CONNECTED -> _failureReason.value = null
            State.DISCONNECTED -> Unit
        }
        if (newState == State.DISCONNECTED || newState == State.FAILED) {
            _username.value = null
            _currentPresence.value = null
        }
    }

    fun setUsername(name: String) {
        _username.value = name
    }

    fun setCurrentPresence(payload: String?) {
        _currentPresence.value = payload
    }
}
