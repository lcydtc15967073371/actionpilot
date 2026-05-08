package com.operit.actionpilot.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.operit.actionpilot.model.OpMap
import java.io.File

class MapRepository(private val context: Context) {

    private val file = File(context.filesDir, "actionpilot_map.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun save(map: OpMap) {
        val tmp = File(context.filesDir, "actionpilot_map.json.tmp")
        tmp.writeText(gson.toJson(map))
        tmp.renameTo(file)
    }

    fun load(): OpMap? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), OpMap::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun exportText(): String? {
        if (!file.exists()) return null
        return file.readText()
    }

    fun clear() {
        file.delete()
    }
}
