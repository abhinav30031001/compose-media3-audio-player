package com.ytmusic.clone.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.ytmusic.clone.data.network.YoutubeRequests
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class YtHttpException(val code: Int) : IOException("HTTP $code")

/**
 * ExoPlayer data source that fetches YouTube streams with YoutubeRequests (POST, &rn=, correct
 * User-Agent). Non-YouTube URLs are fetched with a normal ranged GET.
 */
class YoutubeDataSource(
    private val client: OkHttpClient,
    private val userAgent: String? = null
) : BaseDataSource(true) {

    class Factory(
        private val client: OkHttpClient,
        private val userAgent: String? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeDataSource(client, userAgent)
    }

    private var response: Response? = null
    private var stream: InputStream? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        com.ytmusic.clone.data.network.UrlPolicy.verifyOrThrow(dataSpec.uri.toString(), com.ytmusic.clone.data.network.PolicyScope.STREAM)
        transferInitializing(dataSpec)

        val unset = C.LENGTH_UNSET.toLong()
        val request = YoutubeRequests.build(
            url = dataSpec.uri.toString(),
            position = dataSpec.position,
            length = dataSpec.length,
            userAgent = userAgent
        )
        val resp = client.newCall(request).execute()
        response = resp

        if (resp.code == 416) {
            // Asked for a position at/after the end: behave like an empty source.
            bytesRemaining = 0L
        } else {
            if (!resp.isSuccessful) {
                val code = resp.code
                resp.close()
                response = null
                throw YtHttpException(code)
            }
            val body = resp.body ?: throw IOException("Empty response body")
            val input = body.byteStream()
            stream = input

            // Server ignored the range and sent the whole file: skip to the wanted position.
            var skipped = 0L
            if (resp.code == 200 && dataSpec.position > 0L) {
                val discardBuffer = ByteArray(4096)
                while (skipped < dataSpec.position) {
                    val needed = dataSpec.position - skipped
                    val toRead = minOf(discardBuffer.size.toLong(), needed).toInt()
                    val n = input.read(discardBuffer, 0, toRead)
                    if (n == -1) {
                        break
                    }
                    skipped += n
                }
            }

            val contentLength = body.contentLength()
            bytesRemaining = when {
                dataSpec.length != unset -> dataSpec.length
                contentLength >= 0L -> (contentLength - skipped).coerceAtLeast(0L)
                else -> unset
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val unset = C.LENGTH_UNSET.toLong()
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val input = stream ?: return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == unset) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = input.read(buffer, offset, toRead)
        if (n == -1) {
            // Cleanly report end of input rather than throwing fatal EOFException
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != unset) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        try {
            stream?.close()
        } catch (e: IOException) {
        }
        response?.close()
        stream = null
        response = null
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
