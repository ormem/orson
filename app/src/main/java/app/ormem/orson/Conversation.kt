package app.ormem.orson

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Questions and answers, kept on the phone across restarts. */
class Conversation(context: Context) {

    class Turn(val question: String, val answer: String)

    private val file = File(context.filesDir, "conversation.json")

    init {
        // A conversation pushed to the app's external folder over adb is restored once.
        val pushed = context.getExternalFilesDir(null)?.let { File(it, "conversation.json") }
        if (pushed != null && pushed.exists() && !file.exists()) runCatching { pushed.copyTo(file); pushed.delete() }
    }

    val turns: MutableList<Turn> = load()

    fun add(question: String, answer: String) {
        turns += Turn(question, answer)
        while (turns.size > MAX_TURNS) turns.removeAt(0)
        save()
    }

    /** Picks up changes made from Settings. */
    fun reload() {
        turns.clear()
        turns += load()
    }

    fun clear() {
        turns.clear()
        file.delete()
    }

    private fun load(): MutableList<Turn> = runCatching {
        val list = JSONArray(file.readText())
        MutableList(list.length()) { i -> list.getJSONObject(i).let { Turn(it.getString("q"), it.getString("a")) } }
    }.getOrElse { mutableListOf() }

    private fun save() {
        val list = JSONArray()
        turns.forEach { list.put(JSONObject().put("q", it.question).put("a", it.answer)) }
        file.writeText(list.toString())
    }

    private companion object {
        const val MAX_TURNS = 500
    }
}
