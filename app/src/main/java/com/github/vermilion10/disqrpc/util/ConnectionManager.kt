package com.github.vermilion10.disqrpc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun updateState(newState: State) {
        _state.value = newState
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
