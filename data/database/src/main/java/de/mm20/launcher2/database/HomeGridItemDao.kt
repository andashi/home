package de.mm20.launcher2.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import de.mm20.launcher2.database.entities.HomeGridItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Access to the home grid rows. An abstract class rather than an interface
 * because [replaceLayout] is a transaction with a body of its own.
 */
@Dao
abstract class HomeGridItemDao {

    @Query("SELECT * FROM HomeGridItem WHERE layout = :layout ORDER BY position ASC")
    abstract fun queryLayout(layout: String): Flow<List<HomeGridItemEntity>>

    @Insert
    abstract suspend fun insert(items: List<HomeGridItemEntity>)

    @Query("DELETE FROM HomeGridItem WHERE layout = :layout")
    abstract suspend fun deleteLayout(layout: String)

    /**
     * Replaces every row of [layout] with [items] in one transaction. The rows
     * are stamped with [layout] regardless of what they carry, so a caller
     * cannot accidentally write one layout's items into another.
     */
    @Transaction
    open suspend fun replaceLayout(layout: String, items: List<HomeGridItemEntity>) {
        deleteLayout(layout)
        insert(items.map { if (it.layout == layout) it else it.copy(layout = layout) })
    }

    @Query(
        "UPDATE HomeGridItem SET x = :x, y = :y, w = :w, h = :h " +
                "WHERE layout = :layout AND id = :id"
    )
    abstract suspend fun patchGeometry(layout: String, id: String, x: Int, y: Int, w: Int, h: Int)

    @Query("UPDATE HomeGridItem SET appWidgetId = :appWidgetId WHERE layout = :layout AND id = :id")
    abstract suspend fun setAppWidgetId(layout: String, id: String, appWidgetId: Int?)

    @Query("DELETE FROM HomeGridItem WHERE layout = :layout AND id = :id")
    abstract suspend fun deleteItem(layout: String, id: String)
}
