package com.macrobase.app.feature.rank

import android.content.res.Resources
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded rank pictures, kept between visits so the Rank tab and the ladder draw them at once
 * instead of decoding them again. 6 MB holds the current rank's picture, a few others and every
 * ladder thumbnail.
 */
private object RankPictureCache {
    private val cache = object : LruCache<Int, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(@DrawableRes id: Int): ImageBitmap? = cache.get(id)

    /** Decodes one picture at a time, so ladder rows sharing a picture decode it once. */
    @Synchronized
    fun load(resources: Resources, @DrawableRes id: Int): ImageBitmap =
        cache.get(id) ?: ImageBitmap.imageResource(resources, id).also {
            // Ready for the GPU before it is first drawn
            it.prepareToDraw()
            cache.put(id, it)
        }
}

/**
 * A rank picture, fitted into [modifier]'s bounds. One already decoded draws in the same frame;
 * otherwise it is decoded off the main thread, so opening Rank or the ladder never waits for it,
 * and it appears as soon as it is ready. When the rank changes, the old picture stays until the
 * new one is ready.
 */
@Composable
internal fun RankPicture(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val resources = LocalContext.current.resources
    var bitmap by remember { mutableStateOf(RankPictureCache.get(id)) }
    LaunchedEffect(id) {
        bitmap = RankPictureCache.get(id) ?: withContext(Dispatchers.IO) { RankPictureCache.load(resources, id) }
    }
    Box(
        modifier = modifier.semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
            role = Role.Image
        }
    ) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
