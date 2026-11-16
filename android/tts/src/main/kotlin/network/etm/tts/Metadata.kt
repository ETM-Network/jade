package network.etm.tts

/**
 * The label saved files carry: a plain comment any player shows, and XMP with the IPTC fields for AI-generated media.
 * It names the model, its settings, the voice, the language and the watermark; never the text that was read, a time
 * or anything about the user.
 */
internal class Label(val voice: String, val language: String, val steps: Int, val variant: String) {
    private val version get() = "$MODEL_VERSION · $steps steps · $variant"

    fun comment() = "$STATEMENT $SYSTEM $MODEL_VERSION by $DEVELOPER, $WEBSITE"

    fun xmp(): String = XMP
        .replace("{statement}", STATEMENT)
        .replace("{language}", language)
        .replace("{source_type}", quoteAttr(SOURCE_TYPE))
        .replace("{system}", quoteAttr(SYSTEM))
        .replace("{version}", quoteAttr(version))
        .replace("{developer}", quoteAttr(DEVELOPER))
        .replace("{website}", quoteAttr(WEBSITE))
        .replace("{voice}", quoteAttr(voice))
        .replace("{watermark}", quoteAttr(Watermark.label()))

    private companion object {
        const val SYSTEM = "Jade"
        const val MODEL_VERSION = "1.1"
        const val DEVELOPER = "ETM-Network"
        const val WEBSITE = "https://etmnetwork.de"
        const val STATEMENT = "AI-generated speech (text-to-speech)."
        const val SOURCE_TYPE = "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"

        val XMP = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
    xmlns:etm="https://etmnetwork.de/ns/speech/1.0/"
    Iptc4xmpExt:DigitalSourceType={source_type}
    Iptc4xmpExt:AISystemUsed={system}
    Iptc4xmpExt:AISystemVersionUsed={version}
    etm:ModelDeveloper={developer}
    etm:ModelDeveloperWebsite={website}
    etm:Voice={voice}
    etm:Watermark={watermark}>
   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">{statement}</rdf:li></rdf:Alt></dc:description>
   <dc:language><rdf:Bag><rdf:li>{language}</rdf:li></rdf:Bag></dc:language>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="r"?>""".replace("\\uFEFF", "﻿")

        /** An XML attribute value with its quotes, escaped as Python's xml.sax.saxutils.quoteattr does. */
        fun quoteAttr(value: String): String {
            var v = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;")
            if ('"' !in v) return "\"$v\""
            if ('\'' !in v) return "'$v'"
            v = v.replace("\"", "&quot;")
            return "\"$v\""
        }
    }
}
