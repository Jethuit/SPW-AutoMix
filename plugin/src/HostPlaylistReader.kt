package automix

import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.Modifier

internal data class HostPlaylistTrack(val id: String?, val path: String?, val syncTrackId: String)

internal data class HostPlaylist(
    val id: String,
    val title: String,
    val sort: Int,
    val descending: Boolean,
    val tracks: List<HostPlaylistTrack>
)

/** Reads library playlists through the host's existing Room reader connection. */
internal class HostPlaylistReader(symbols: HostSymbols) {
    private val repo = symbols.named("com.xuncorp.voxzen.data.repo.PlaylistRepo")
    private val databaseField = symbols.field(repo.field("歌单数据库") { field, _ ->
        field.descriptor == "Lcom/xuncorp/voxzen/data/AppDatabase;"
    })
    private val companion = symbols.named("com.xuncorp.voxzen.data.repo.PlaylistRepo\$Companion")
    private val getter = symbols.method(companion.method("歌单仓库实例") {
        it.isPublic && it.symbol.descriptor == "()${repo.descriptor}"
    }.symbol)
    private val instance = if (Modifier.isStatic(getter.modifiers)) null else symbols.field(
        repo.field("歌单仓库 Companion") { field, _ -> field.descriptor == companion.descriptor }
    )

    suspend fun read(): List<HostPlaylist> {
        val repository = getter.invoke(instance?.get(null))
        return readDatabase(databaseField.get(repository) as RoomDatabase)
    }

    companion object {
        suspend fun readDatabase(database: RoomDatabase): List<HostPlaylist> = database.useReaderConnection { connection ->
            val headers = connection.usePrepared(
                """SELECT id, title, trackSort, trackSortDescending FROM Playlist ORDER BY "order" DESC, createdTime DESC"""
            ) { statement ->
                buildList {
                    while (statement.step()) {
                        val id = statement.text(0) ?: continue
                        val title = statement.text(1) ?: continue
                        add(HostPlaylist(id, title, statement.getInt(2), statement.getBoolean(3), emptyList()))
                    }
                }
            }
            headers.map { header ->
                val tracks = connection.usePrepared(
                    """
                    SELECT t.id, t.path, pt.syncTrackId,
                           COALESCE(NULLIF(t.title, ''), st.title),
                           COALESCE(NULLIF(t.artist, ''), st.artist),
                           COALESCE(NULLIF(t.album, ''), st.album),
                           t.size, COALESCE(NULLIF(t.year, 0), st.year), t.playCount,
                           COALESCE(NULLIF(t.duration, 0), st.duration), t.modifiedTime, t.addedTime,
                           pt."order", pt.addedTime
                    FROM PlaylistTrack pt
                    LEFT JOIN SyncTrack st ON st.id = pt.syncTrackId
                    LEFT JOIN Track playback ON playback.id = NULLIF(st.playbackTrackId, '')
                    LEFT JOIN Track t ON t.id = COALESCE(playback.id, NULLIF(st.primaryTrackId, ''))
                    WHERE pt.playlistId = ? AND pt.isRemoved = 0
                    """.trimIndent()
                ) { statement -> members(statement, header.id, header.sort, header.descending) }
                header.copy(tracks = tracks)
            }
        }

        private fun members(statement: SQLiteStatement, id: String, sort: Int, descending: Boolean): List<HostPlaylistTrack> {
            data class Row(val track: HostPlaylistTrack, val textKey: String, val numericKey: Long, val order: Long)
            statement.bindText(1, id)
            val rows = buildList {
                while (statement.step()) {
                    val sync = statement.text(2)?.takeIf { it.isNotEmpty() } ?: continue
                    val path = statement.text(1)
                    val normalized = path.orEmpty().replace('\\', '/')
                    val text = when (sort) {
                        1 -> statement.text(3).orEmpty()
                        2 -> normalized.substringAfterLast('/')
                        3 -> statement.text(5).orEmpty()
                        4 -> statement.text(4).orEmpty()
                        7 -> normalized.substringBeforeLast('/', "")
                        else -> ""
                    }
                    val number = when (sort) {
                        5 -> 6  // file size
                        6 -> 7  // year
                        8 -> 8  // play count
                        9 -> 9  // duration
                        10 -> 10  // modification time
                        11 -> 11  // library addition time
                        12 -> 13  // addition time in this playlist
                        else -> 12  // custom order
                    }
                    val track = HostPlaylistTrack(statement.text(0), path, sync)
                    val playlistOrder = statement.getLong(12)
                    add(Row(track, text.lowercase(), statement.getLong(number), playlistOrder))
                }
            }
            val primary = if (sort in listOf(1, 2, 3, 4, 7)) compareBy(Row::textKey) else compareBy(Row::numericKey)
            val order = (if (descending) primary.reversed() else primary).thenBy(Row::order).thenBy { it.track.syncTrackId }
            return rows.sortedWith(order).map(Row::track)
        }

        private fun SQLiteStatement.text(index: Int) = if (isNull(index)) null else getText(index)
    }
}
