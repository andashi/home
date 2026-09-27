package de.mm20.launcher2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.mm20.launcher2.database.entities.CustomAttributeEntity
import de.mm20.launcher2.database.entities.SavedSearchableEntity
import de.mm20.launcher2.database.entities.SearchActionEntity

@Dao
interface BackupRestoreDao {

    @Query("DELETE FROM Searchable")
    suspend fun wipeFavorites()

    @Query("SELECT * FROM Searchable LIMIT :limit OFFSET :offset")
    suspend fun exportFavorites(limit: Int, offset: Int): List<SavedSearchableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importFavorites(items: List<SavedSearchableEntity>)

    @Query("DELETE FROM SearchAction")
    suspend fun wipeSearchActions()

    @Query("SELECT * FROM SearchAction LIMIT :limit OFFSET :offset")
    suspend fun exportSearchActions(limit: Int, offset: Int): List<SearchActionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importSearchActions(items: List<SearchActionEntity>)

    @Query("DELETE FROM CustomAttributes")
    suspend fun wipeCustomAttributes()

    @Query("SELECT * FROM CustomAttributes LIMIT :limit OFFSET :offset")
    suspend fun exportCustomAttributes(limit: Int, offset: Int): List<CustomAttributeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importCustomAttributes(items: List<CustomAttributeEntity>)

    /**
     * Customizations of items that no longer have a row. Icons were left out,
     * so orphaned icon rows piled up forever (#3 slice 4); they go too now,
     * except a tag's icon, keyed `tag://<name>`, which stays as long as some
     * item still carries the tag - a tag nobody pinned has no row of its own.
     */
    @Query(
        "DELETE FROM CustomAttributes WHERE (type = 'tag' OR type = 'label' OR type = 'icon') " +
            "AND NOT EXISTS(SELECT 1 FROM Searchable WHERE CustomAttributes.key = Searchable.key) " +
            "AND NOT (type = 'icon' AND `key` LIKE 'tag://%' AND EXISTS(" +
            "SELECT 1 FROM CustomAttributes AS t WHERE t.type = 'tag' AND 'tag://' || t.value = CustomAttributes.key))"
    )
    suspend fun cleanUp(): Int
}