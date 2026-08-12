package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.remote.HonomiRemoteControlClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    val client: HonomiRemoteControlClient,
) : ViewModel() {
    init {
        client.start(viewModelScope)
    }
}
