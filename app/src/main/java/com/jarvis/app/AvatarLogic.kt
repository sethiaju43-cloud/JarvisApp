package com.jarvis.app

/*
 * 3D avatar ki list aur "avatar ninja lagao" jaise commands. Is file mein Android ka koi code nahi hai,
 * isliye ye JVM unit tests mein seedha test hota hai.
 */

class AvatarInfo(val id: String, val label: String, val keywords: List<String>)

sealed class AvatarCommand {
    class Set(val id: String) : AvatarCommand()
    object Reset : AvatarCommand()
    object ShowList : AvatarCommand()
}

object AvatarLogic {

    val all: List<AvatarInfo> = listOf(
        AvatarInfo("robot", "Robot", listOf("robot")),
        AvatarInfo("astronaut", "Astronaut", listOf("astronaut", "antariksh")),
        AvatarInfo("ninja", "Ninja", listOf("ninja")),
        AvatarInfo("person_genie", "Jinn", listOf("jinn", "genie", "jin")),
        AvatarInfo("brain", "Dimaag", listOf("dimaag", "dimag", "brain")),
        AvatarInfo("cat_with_wry_smile", "Billi", listOf("billi", "cat")),
        AvatarInfo("alien", "Alien", listOf("alien")),
        AvatarInfo("artist", "Kalakaar", listOf("kalakaar", "artist")),
        AvatarInfo("cook", "Rasoiya", listOf("rasoiya", "cook", "chef")),
        AvatarInfo("crystal_ball", "Jaadui gola", listOf("jaadui gola", "crystal")),
        AvatarInfo("ghost", "Bhoot", listOf("bhoot", "ghost")),
        AvatarInfo("guard", "Pehredaar", listOf("pehredaar", "guard")),
        AvatarInfo("health_worker", "Doctor", listOf("doctor", "nurse", "health")),
        AvatarInfo("judge", "Judge", listOf("judge")),
        AvatarInfo("man_detective", "Jasoos", listOf("jasoos", "detective")),
        AvatarInfo("mechanic", "Mechanic", listOf("mechanic")),
        AvatarInfo("money_mouth_face", "Paisa", listOf("paisa", "money")),
        AvatarInfo("nerd_face", "Padhaku", listOf("padhaku", "nerd")),
        AvatarInfo("office_worker", "Office wala", listOf("office")),
        AvatarInfo("person_mage", "Jaadugar", listOf("jaadugar", "mage", "wizard")),
        AvatarInfo("person_superhero", "Superhero", listOf("superhero", "hero")),
        AvatarInfo("pilot", "Pilot", listOf("pilot")),
        AvatarInfo("scientist", "Vaigyanik", listOf("vaigyanik", "scientist")),
        AvatarInfo("singer", "Gayak", listOf("gayak", "singer")),
        AvatarInfo("student", "Student", listOf("student")),
        AvatarInfo("teacher", "Teacher", listOf("teacher")),
        AvatarInfo("woman_technologist", "Techie (ladki)", listOf("techie ladki", "ladki techie", "woman techie", "woman technologist")),
        AvatarInfo("technologist", "Techie", listOf("techie", "technologist"))
    )

    private val I = RegexOption.IGNORE_CASE
    private val avatarWord = Regex("(?<![a-z])(?:avatar|chehra)(?![a-z])", I)
    private val resetWords = Regex("(?<![a-z])(?:wapas|hata|hatao|reset|purana|default|original)(?![a-z])", I)
    private val setWords = Regex("(?<![a-z])(?:lagao|laga|chuno|badlo|badal|set|rakho|kar\\s+do)(?![a-z])", I)
    private val listWords = Regex("(?<![a-z])(?:list|options|dikhao|batao|kaun\\s+kaun|kitne)(?![a-z])", I)

    fun labelOf(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    /** Naam ke shabd se avatar dhundo ("ninja", "billi", "jaadugar"...) */
    fun find(query: String): AvatarInfo? {
        val q = query.lowercase()
        for (a in all) {
            for (k in a.keywords) {
                if (Regex("(?<![a-z])" + Regex.escape(k) + "(?![a-z])").containsMatchIn(q)) return a
            }
        }
        return null
    }

    fun parse(text: String): AvatarCommand? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 120) return null
        if (!avatarWord.containsMatchIn(t)) return null
        if (resetWords.containsMatchIn(t)) return AvatarCommand.Reset
        val found = find(avatarWord.replace(t, " "))
        if (found != null && setWords.containsMatchIn(t)) return AvatarCommand.Set(found.id)
        if (listWords.containsMatchIn(t) || setWords.containsMatchIn(t)) return AvatarCommand.ShowList
        return null
    }
}
