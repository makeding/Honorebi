@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.media.tv.TvContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TifDebugChannel(
    val name: String, val number: String, val onid: String, val tsid: String, val sid: String,
)

@Composable
internal fun TifChannelDebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val result by produceState<Result<List<TifDebugChannel>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val columns = arrayOf(TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                    TvContract.Channels.COLUMN_SERVICE_ID)
                context.contentResolver.query(TvContract.Channels.CONTENT_URI, columns, null, null, null)!!.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            fun field(index: Int) = if (cursor.isNull(index)) "NULL" else cursor.getString(index)
                            add(TifDebugChannel(field(0), field(1), field(2), field(3), field(4)))
                        }
                    }
                }
            }
        }
    }
    var page by remember { mutableIntStateOf(0) }
    val channels = result?.getOrNull().orEmpty()
    val pageCount = maxOf(1, (channels.size + 4) / 5)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.85f).background(colors.surface1).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TV入力 チャンネル診断  ${page + 1} / $pageCount", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            when {
                result == null -> Text("読み込み中…", color = colors.textSecondary)
                result!!.isFailure -> Text("取得できません: ${result!!.exceptionOrNull()?.message}", color = colors.danger)
                channels.isEmpty() -> Text("チャンネルがありません", color = colors.textSecondary)
                else -> channels.drop(page * 5).take(5).forEach { channel ->
                    Text("${channel.number}  ${channel.name}\nONID: ${channel.onid}    TSID: ${channel.tsid}    SID: ${channel.sid}",
                        style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { page-- }, enabled = page > 0) { Text("前へ") }
                Button(onClick = { page++ }, enabled = page + 1 < pageCount) { Text("次へ") }
                Button(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}
