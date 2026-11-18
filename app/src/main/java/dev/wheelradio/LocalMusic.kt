package dev.wheelradio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

/** Finds audio files on the head unit's storage and on USB sticks. Call from a background thread. */
object LocalMusic {
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "ape")

    /** Everything Android's media scanner has indexed, on internal storage and every mounted USB volume. */
    fun scanMediaStore(context: Context): List<Station> {
        val result = ArrayList<Station>()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val source = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Device" else "USB"
            val collection = MediaStore.Audio.Media.getContentUri(volume)
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DISPLAY_NAME,
            )
            runCatching {
                context.contentResolver.query(
                    collection, projection, null, null, MediaStore.Audio.Media.TITLE + " ASC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: c.getString(nameCol) ?: "?"
                        val artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: ""
                        result += Station(
                            id = "local:$uri", name = title, url = uri.toString(),
                            subtitle = listOf(source, artist).filter { it.isNotBlank() }.joinToString(" · "),
                            local = true,
                        )
                    }
                }
            }
        }
        return result
    }

    /** Fallback when the media scanner doesn't index the USB stick: walk a folder the user picked. */
    fun scanFolder(context: Context, tree: Uri): List<Station> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val result = ArrayList<Station>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 6 || result.size >= 3000) return
            for (f in dir.listFiles().sortedBy { it.name?.lowercase() }) {
                if (f.isDirectory) walk(f, depth + 1)
                else {
                    val name = f.name ?: continue
                    val isAudio = f.type?.startsWith("audio/") == true ||
                        name.substringAfterLast('.', "").lowercase() in audioExt
                    if (isAudio) result += Station(
                        id = "local:${f.uri}", name = name.substringBeforeLast('.'), url = f.uri.toString(),
                        subtitle = "Folder · " + (dir.name ?: ""), local = true,
                    )
                }
            }
        }
        walk(root, 0)
        return result
    }
}
