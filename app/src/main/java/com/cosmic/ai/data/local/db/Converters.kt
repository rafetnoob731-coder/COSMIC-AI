// language: kotlin, file: Converters.kt, runtime: Room, target: type conversion
package com.cosmic.ai.data.local.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>?): String = value?.joinToString("|") ?: ""

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split("|")
}
