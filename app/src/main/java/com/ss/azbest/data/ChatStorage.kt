package com.ss.azbest.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ss.azbest.domain.ChatMessage

/**
 * Хранит сообщения по chatId в SharedPreferences через Gson.
 * Каждый чат = отдельный ключ "chat_<chatId>".
 * Максимум 200 сообщений на чат (удаляем старые).
 */
class ChatStorage(context: Context) {

    private val prefs = context.getSharedPreferences("azbest_chats", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val messageType = object : TypeToken<List<ChatMessage>>() {}.type

    companion object {
        private const val MAX_MESSAGES_PER_CHAT = 200
        private const val PREFIX = "chat_"
        private const val KEY_CHAT_IDS = "known_chat_ids"
    }

    fun saveMessages(chatId: String, messages: List<ChatMessage>) {
        // Ограничиваем количество — берём последние MAX_MESSAGES_PER_CHAT
        val toSave = if (messages.size > MAX_MESSAGES_PER_CHAT) {
            messages.takeLast(MAX_MESSAGES_PER_CHAT)
        } else {
            messages
        }
        prefs.edit()
            .putString("$PREFIX$chatId", gson.toJson(toSave))
            .apply()

        // Запоминаем chatId в индексе
        val ids = getKnownChatIds().toMutableSet()
        if (ids.add(chatId)) {
            prefs.edit().putString(KEY_CHAT_IDS, gson.toJson(ids.toList())).apply()
        }
    }

    fun loadMessages(chatId: String): List<ChatMessage> {
        val json = prefs.getString("$PREFIX$chatId", null) ?: return emptyList()
        return try {
            gson.fromJson(json, messageType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getKnownChatIds(): List<String> {
        val json = prefs.getString(KEY_CHAT_IDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearChat(chatId: String) {
        prefs.edit().remove("$PREFIX$chatId").apply()
        val ids = getKnownChatIds().toMutableSet()
        ids.remove(chatId)
        prefs.edit().putString(KEY_CHAT_IDS, gson.toJson(ids.toList())).apply()
    }
}
