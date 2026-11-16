package network.etm.tts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The front end produces the reference implementation's pieces (tokens and pauses) for 86 texts in 23 languages. */
class FrontendTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)

    @Test
    fun matchesReference() {
        val builder = PieceBuilder(ModelConfig(JSONObject(resource("config.json"))))
        val cases = JSONArray(resource("frontend_reference.json"))
        val mismatches = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val want = case.getJSONArray("pieces").let { a ->
                List(a.length()) { a.getJSONObject(it).let { p -> p.getJSONArray("tokens").let { t -> List(t.length()) { t.getString(it) } } to p.getDouble("pause") } }
            }
            val got = builder.tokens(case.getString("text"), case.getString("lang")).map { it.tokens to it.pause }
            if (got != want) mismatches.add("${case.getString("lang")}: ${case.getString("text").take(60)}")
        }
        assertEquals("texts whose pieces differ: $mismatches", 0, mismatches.size)
    }
}
