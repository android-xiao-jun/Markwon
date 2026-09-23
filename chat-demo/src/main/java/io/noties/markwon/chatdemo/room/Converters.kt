package io.noties.markwon.chatdemo.room

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.noties.markwon.chatdemo.bean.Attachment

/**
 * Room TypeConverter
 * - List<Attachment> ↔ JSON String
 */
class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromAttachmentList(value: List<Attachment>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toAttachmentList(value: String): List<Attachment> {
        if (value.isEmpty()) return emptyList()
        val type = object : TypeToken<List<Attachment>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}