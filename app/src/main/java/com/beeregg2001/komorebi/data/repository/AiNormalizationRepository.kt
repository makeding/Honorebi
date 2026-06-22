package com.beeregg2001.komorebi.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiNormalizationRepository @Inject constructor() {
    suspend fun normalizeTitles(titles: List<String>): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            Result.failure(UnsupportedOperationException("AI normalization is disabled."))
        }
}
