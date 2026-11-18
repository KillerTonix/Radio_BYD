package dev.wheelradio

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Station(val id: String, val name: String, val url: String) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setStation(name)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}
