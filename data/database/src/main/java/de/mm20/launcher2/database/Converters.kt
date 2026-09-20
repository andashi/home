package de.mm20.launcher2.database

import android.content.ComponentName
import androidx.room.TypeConverter

class ComponentNameConverter {
    @TypeConverter
    fun toString(componentName: ComponentName?): String? {
        return componentName?.flattenToString()
    }

    @TypeConverter
    fun toComponentName(string: String?) : ComponentName? {
        string ?: return null
        return ComponentName.unflattenFromString(string)
    }

}
