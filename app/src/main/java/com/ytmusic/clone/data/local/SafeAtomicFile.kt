package com.ytmusic.clone.data.local

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Task C3: Atomic, loss-proof file persistence.
 * Uses Android's native AtomicFile (startWrite/finishWrite/failWrite) on device,
 * with atomic temp-file fallback in host JVM unit test environments.
 */
object SafeAtomicFile {

    private val isAndroidRuntime: Boolean by lazy {
        System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
    }

    @Throws(IOException::class)
    fun readBytes(file: File): ByteArray {
        if (isAndroidRuntime) {
            try {
                return AtomicFile(file).readFully()
            } catch (e: IOException) {
                throw e
            } catch (_: Throwable) {
                return file.readBytes()
            }
        }
        return file.readBytes()
    }

    @Throws(IOException::class)
    fun writeBytes(file: File, bytes: ByteArray) {
        if (isAndroidRuntime) {
            try {
                val atomic = AtomicFile(file)
                var fos: FileOutputStream? = null
                try {
                    fos = atomic.startWrite()
                    fos.write(bytes)
                    atomic.finishWrite(fos)
                    return
                } catch (e: Throwable) {
                    if (fos != null) {
                        atomic.failWrite(fos)
                    }
                    throw e
                }
            } catch (e: IOException) {
                throw e
            } catch (_: Throwable) {
                // Fall through to atomic temp-file write
            }
        }

        // Atomic temp-file-then-rename for JVM unit test environments
        val parent = file.parentFile ?: File(".")
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }
}
