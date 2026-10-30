package io.droyagent.app.nativeui.media

import io.droyagent.app.media.MediaInputPolicy
import java.io.File
import java.io.IOException
import java.util.UUID

/** Temporary readable copies stay inside the existing narrow FileProvider subtree. */
internal class NativeMediaCache(private val root: File) {
    @Synchronized fun write(bytes: ByteArray, name: String, pinned: Set<String> = emptySet()): File {
        if (bytes.isEmpty() || bytes.size > NativeMediaPolicy.MAX_BYTES) throw NativeMediaProblem(NativeMediaError.TOO_LARGE)
        if (!root.isDirectory && !root.mkdirs()) throw NativeMediaProblem(NativeMediaError.STORAGE)
        val directories = root.listFiles()?.filter { it.isDirectory }?.sortedBy(File::lastModified) ?: emptyList()
        var total = directories.sumOf { directory -> directory.walkTopDown().filter(File::isFile).sumOf(File::length) }
        for (directory in directories) {
            if (total + bytes.size <= NativeMediaPolicy.CACHE_BYTES) break
            if (directory.walkTopDown().any { it.absolutePath in pinned }) continue
            val size = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
            if (!directory.deleteRecursively()) throw NativeMediaProblem(NativeMediaError.STORAGE)
            total -= size
        }
        if (total + bytes.size > NativeMediaPolicy.CACHE_BYTES) throw NativeMediaProblem(NativeMediaError.STORAGE)
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdir()) throw NativeMediaProblem(NativeMediaError.STORAGE)
        val target = File(directory, MediaInputPolicy.filename(name))
        try { target.outputStream().use { it.write(bytes) } }
        catch (error: IOException) { directory.deleteRecursively(); throw NativeMediaProblem(NativeMediaError.STORAGE, error) }
        return target
    }
}
