package automix

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private const val TEXT = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val CONT = "Lkotlin/coroutines/Continuation;"
private const val ANY = "Ljava/lang/Object;"
private const val PLAYLIST = "Lcom/xuncorp/voxzen/data/entity/Playlist;"
private const val MEMBERSHIP = "Lcom/xuncorp/voxzen/data/entity/PlaylistTrack;"
private const val DATABASE = "Lcom/xuncorp/voxzen/data/AppDatabase;"

/** Use SPW's local-playlist factory and membership writer inside one Room transaction. */
internal class HostPlaylistCreation(symbols: HostSymbols) {
    private val createSignature = "($TEXT$TEXT$CONT)$ANY"
    private val insertSignature = "([$MEMBERSHIP$CONT)$ANY"
    private val daoType = symbols.types.values.filter { type ->
        type.methods.any { it.isPublic && !it.isStatic && it.symbol.descriptor == createSignature &&
            it.signature?.contains("Continuation<-$PLAYLIST>") == true } &&
            type.methods.any { it.isPublic && !it.isStatic && it.symbol.descriptor == insertSignature } &&
            symbols.resolve(type).isInterface
    }.uniqueHost("新建歌单仓库")
    private val createPlaylist = symbols.method(daoType.method("新建本地歌单") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == createSignature &&
            it.signature?.contains("Continuation<-$PLAYLIST>") == true
    }.symbol)
    private val insertMembers = symbols.method(daoType.method("写入歌单曲目") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == insertSignature
    }.symbol)
    private val repo = symbols.types.values.filter { type ->
        type.fields.keys.any { it.descriptor == DATABASE } &&
            type.fields.keys.any { it.descriptor == daoType.descriptor } &&
            type.methods.any {
                it.isPublic && !it.isStatic &&
                    it.signature == "()Lkotlinx/coroutines/flow/Flow<Ljava/util/List<$PLAYLIST>;>;"
            }
    }.uniqueHost("活动歌单仓库")
    private val databaseField = symbols.field(repo.field("歌单数据库") { field, _ -> field.descriptor == DATABASE })
    private val daoField = symbols.field(repo.field("歌单数据接口") { field, _ -> field.descriptor == daoType.descriptor })
    private val singleton = symbols.resolve(repo).let { type ->
        type.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type.enclosingClass == type }
            .flatMap { field ->
                field.type.declaredMethods.filter { it.returnType == type && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
                    .map { method -> field to method }
            }.uniqueHost("歌单仓库实例").also { (field, method) ->
                check(field.trySetAccessible() && method.trySetAccessible())
            }
    }

    // Discover the same membership ID factory called by SPW's native playlist importer.
    private val makeSyncId = daoType.method("原生歌单导入") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "($TEXT$TEXT$LIST$CONT)$ANY" &&
            it.signature?.contains("Continuation<-$PLAYLIST>") == true
    }.calls.mapNotNull(symbols::body).filter { it.isStatic && it.symbol.owner == daoType.owner }
        .flatMap { it.calls }.distinct().filter {
            it.owner.startsWith("com/xuncorp/voxzen/sync/") && it.descriptor == "($TEXT$TEXT)$TEXT"
        }.uniqueHost("歌单曲目标识生成器").let(symbols::method).also {
            check(Modifier.isStatic(it.modifiers))
        }

    private val playlistClass = createPlaylist.declaringClass.classLoader
        .loadClass("com.xuncorp.voxzen.data.entity.Playlist")
    private val getId = playlistClass.getMethod("getId")
    private val getScopeId = playlistClass.getMethod("getScopeId")
    private val getSyncId = playlistClass.getMethod("getSyncId")
    private val memberType = insertMembers.parameterTypes.first().componentType
    private val member = memberType.getConstructor(
        String::class.java, String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        String::class.java, String::class.java, Boolean::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java
    )

    suspend fun create(sourceId: String, title: String, trackIds: List<String>): String {
        val (field, getter) = singleton
        val repository = getter.invoke(if (Modifier.isStatic(getter.modifiers)) null else field.get(null))
        return create(databaseField.get(repository) as RoomDatabase, daoField.get(repository), sourceId, title, trackIds)
    }

    internal suspend fun create(
        database: RoomDatabase, dao: Any, sourceId: String, title: String, trackIds: List<String>
    ): String = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            require(title.isNotBlank() && trackIds.size >= 2 && trackIds.toSet().size == trackIds.size)
            val sourceMembers = usePrepared(
                "SELECT pt.syncTrackId, st.syncId FROM PlaylistTrack pt JOIN SyncTrack st ON st.id = pt.syncTrackId " +
                    "WHERE pt.playlistId = ? AND pt.isRemoved = 0"
            ) { statement ->
                statement.bindText(1, sourceId)
                buildMap {
                    while (statement.step()) put(statement.getText(0), statement.getText(1))
                }
            }
            check(trackIds.all { !sourceMembers[it].isNullOrBlank() }) { "源歌单已变更或有歌曲无法复制，请刷新后重试" }
            val playlist = checkNotNull(createPlaylist.invokeSuspend(dao, title, "由 AutoMix 自动编排"))
            val id = getId.invoke(playlist) as String
            val scopeId = getScopeId.invoke(playlist) as String
            val syncId = getSyncId.invoke(playlist) as String
            check(scopeId == "local") { "宿主未创建本地歌单" }
            val now = System.currentTimeMillis()
            val rows = ReflectArray.newInstance(memberType, trackIds.size)
            trackIds.forEachIndexed { index, trackId ->
                val membershipId = makeSyncId.invoke(null, syncId, sourceMembers.getValue(trackId))
                ReflectArray.set(rows, index, member.newInstance(
                    id, trackId, now, index, scopeId, membershipId, false, ByteArray(0), ByteArray(0)
                ))
            }
            val inserted = insertMembers.invokeSuspend(dao, rows) as List<*>
            check(inserted.size == trackIds.size && inserted.all { it is Long && it >= 0L }) { "部分歌曲未能加入新歌单" }
            usePrepared("UPDATE Playlist SET trackSort = 0, trackSortDescending = 0 WHERE id = ?") { statement ->
                statement.bindText(1, id)
                statement.step()
            }
            id
        }
    }

    private suspend fun Method.invokeSuspend(target: Any, vararg args: Any): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation -> invoke(target, *args, continuation) }
}
