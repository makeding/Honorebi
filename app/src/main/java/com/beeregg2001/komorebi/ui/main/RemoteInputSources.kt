package com.beeregg2001.komorebi.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.data.repository.tif.TifRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class RemoteInputs(val json: JsonArray, val channels: Map<String, Channel>)

internal fun showRemoteTuner(showTuners: Boolean, permitted: Boolean, hideEmpty: Boolean, channelCount: Int): Boolean =
    showTuners && (!permitted || !hideEmpty || channelCount > 0)

internal suspend fun remoteInputs(context: Context, hideEmpty: Boolean, showTuners: Boolean, selectedId: String?): RemoteInputs {
    val inputs = TifInput.inputs(context)
    val repository = TifRepository(context)
    val permitted = context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    val tuners = if (showTuners && permitted) repository.tunerInputChoices(inputs) else emptyMap()
    val ports = repository.passthroughChannels(inputs).associateBy { it.id }
    val channels = ports.toMutableMap()
    val manager = context.getSystemService(TvInputManager::class.java)
    val json = JsonArray()
    for (input in inputs) {
        val choices = tuners[input.id].orEmpty()
        if (!input.isPassthroughInput && !showRemoteTuner(showTuners, permitted, hideEmpty, choices.size)) continue
        val id = if (input.isPassthroughInput) TifInput.passthroughId(input.id) else input.id
        choices.forEach { channels[it.id] = it }
        json.add(JsonObject().apply {
            addProperty("id", id)
            addProperty("label", TifInput.label(context, input))
            addProperty("type", if (!input.isPassthroughInput) "Tuner" else if (input.type == TvInputInfo.TYPE_HDMI) "HDMI" else "Input")
            addProperty("connection_state", when (manager.getInputState(input.id)) {
                TvInputManager.INPUT_STATE_CONNECTED -> "Connected"
                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Standby"
                TvInputManager.INPUT_STATE_DISCONNECTED -> "Disconnected"
                else -> "Unknown"
            })
            addProperty("selectable", input.isPassthroughInput)
            addProperty("selected", id == selectedId || choices.any { it.id == selectedId })
            if (!input.isPassthroughInput && !permitted) addProperty("unavailable_reason", "TV_LISTINGS_PERMISSION_REQUIRED")
            add("channels", JsonArray().apply { choices.forEach { channel -> add(JsonObject().apply {
                addProperty("id", channel.id)
                addProperty("label", channel.name)
            }) } })
        })
    }
    return RemoteInputs(json, channels)
}
