package com.pixels.enhancer.data.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything Pixels keeps on the device — edits, previews, presets, imported and captured photos,
 * temporary share files — all of it app-private. Shown on the Settings screen.
 */
class AppStorage(private val context: Context) {

    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        listOf(context.filesDir, context.cacheDir).sumOf(::sizeOf)
    }

    private fun sizeOf(directory: File): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
