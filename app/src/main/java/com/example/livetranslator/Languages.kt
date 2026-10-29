package com.example.livetranslator

/** A language the videos can be in. The translation is always into Arabic. */
class Lang(val code: String, val ar: String, val en: String)

object Languages {
    const val AUTO = "auto"

    val list = listOf(
        Lang(AUTO, "تلقائي (أي لغة)", "any language"),
        Lang("en", "الإنكليزية", "English"),
        Lang("tr", "التركية", "Turkish"),
        Lang("fa", "الفارسية", "Persian"),
        Lang("ur", "الأردية", "Urdu"),
        Lang("hi", "الهندية", "Hindi"),
        Lang("fr", "الفرنسية", "French"),
        Lang("es", "الإسبانية", "Spanish"),
        Lang("de", "الألمانية", "German"),
        Lang("it", "الإيطالية", "Italian"),
        Lang("pt", "البرتغالية", "Portuguese"),
        Lang("ru", "الروسية", "Russian"),
        Lang("ko", "الكورية", "Korean"),
        Lang("ja", "اليابانية", "Japanese"),
        Lang("zh", "الصينية", "Chinese")
    )

    fun byCode(code: String): Lang = list.firstOrNull { it.code == code } ?: list[1]

    /** For prompts: "English", or "the original language" when auto. */
    fun promptName(code: String): String =
        if (code == AUTO) "the original language" else byCode(code).en
}
