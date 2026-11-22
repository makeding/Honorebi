package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

data class GGuideCollection(val status: String, val assets: List<GGuideAsset>)

data class GGuideAsset(
    val id: String,
    val width: Int,
    val height: Int,
    val programs: List<GGuideProgram>,
    val advertisements: List<GGuideAdvertisement>,
)

data class GGuideProgram(
    @SerializedName("network_id") val networkId: Int,
    @SerializedName("service_id") val serviceId: Int,
    @SerializedName("event_id") val eventId: Int,
    @SerializedName("start_at") val startAt: Long,
)

data class GGuideAdvertisement(val text: String)
