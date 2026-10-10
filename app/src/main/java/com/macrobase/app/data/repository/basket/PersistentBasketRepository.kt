package com.macrobase.app.data.repository.basket

import android.util.Log
import com.macrobase.app.domain.repository.basket.BasketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Basket that survives the app being closed (BUG-015).
 *
 * The basket is loaded from [file] when created, so items are available synchronously, and
 * every change is written back on [scope]. Writes go to a temporary file that then replaces
 * the basket file, so an interrupted write never corrupts the saved basket.
 */
class PersistentBasketRepository private constructor(
    private val file: File,
    scope: CoroutineScope,
    private val delegate: InMemoryBasketRepository
) : BasketRepository by delegate {

    constructor(file: File, scope: CoroutineScope) : this(file, scope, InMemoryBasketRepository(load(file)))

    init {
        // Read before launching: changes made before the collector starts must still be saved
        val loaded = delegate.items.value
        scope.launch {
            // What the file holds now. Comparing contents (not instances) means a basket that is
            // emptied again is still written, and a value equal to the file is never rewritten.
            var onDisk = loaded
            delegate.items.collect { items ->
                if (items != onDisk && save(items)) onDisk = items
            }
        }
    }

    /** Returns false if the write failed; the next change then tries again. */
    private fun save(items: List<com.macrobase.app.domain.model.basket.BasketItem>): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(BasketItemsJson.encode(items).toByteArray(Charsets.UTF_8))
                // On disk before the rename, so a power cut cannot leave an empty basket file
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            // Disk full or similar: the in-memory basket stays intact and the next change retries.
            Log.e(TAG, "Could not save basket: ${e.message}", e)
            false
        }
    }

    private companion object {
        const val TAG = "PersistentBasket"

        fun load(file: File) = try {
            if (file.exists()) BasketItemsJson.decode(file.readText(Charsets.UTF_8)) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read saved basket: ${e.message}")
            emptyList()
        }
    }
}
