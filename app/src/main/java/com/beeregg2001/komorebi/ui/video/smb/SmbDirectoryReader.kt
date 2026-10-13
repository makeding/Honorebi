package com.beeregg2001.komorebi.ui.video.smb

import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.viewmodel.SmbServer
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject

/** Directory transport shared by browsing and recursive searches. */
open class SmbDirectoryReader @Inject constructor() {
    open suspend fun read(path: String, servers: List<SmbServer>): List<SmbItem> = withContext(Dispatchers.IO) {
        val server = servers.find {
            val host = it.ip.substringBefore('/')
            val port = it.port.ifEmpty { "445" }
            path.startsWith("smb://$host:$port/") || path.startsWith("smb://$host/")
        }
        val context = SmbContextBuilder.build(server?.user.orEmpty(), server?.password.orEmpty())
        SmbFile(path, context).use { directory ->
            if (!directory.exists()) throw FileNotFoundException()
            directory.listFiles().orEmpty().mapNotNull { child ->
                child.use {
                    if (child.name.startsWith('.')) null else SmbItem(
                        name = child.name.replace("/", ""),
                        path = child.url.toString(),
                        isDirectory = child.isDirectory,
                        size = runCatching { child.length() }.getOrDefault(0L),
                        lastModified = runCatching { child.lastModified() }.getOrDefault(0L),
                    )
                }
            }
        }
    }
}
