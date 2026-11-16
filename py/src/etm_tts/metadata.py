"""The label saved files carry: a plain comment any player shows, and XMP with the IPTC fields for AI-generated media.

It names the model, its settings, the voice, the language and the watermark. It never contains the text that was read,
a time or anything about the user.
"""
from dataclasses import dataclass
from xml.sax.saxutils import quoteattr

from . import watermark
from ._release import DEVELOPER, DEVELOPER_URL, MODEL_NAME, MODEL_VERSION

STATEMENT = "AI-generated speech (text-to-speech)."
SOURCE_TYPE = "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"

_XMP = """<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>
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
<?xpacket end="r"?>"""


@dataclass(frozen=True)
class Label:
    voice: str
    language: str
    steps: int
    variant: str
    system: str = MODEL_NAME
    developer: str = DEVELOPER
    website: str = DEVELOPER_URL

    @property
    def version(self):
        return f"{MODEL_VERSION} · {self.steps} steps · {self.variant}"

    def comment(self):
        return f"{STATEMENT} {self.system} {MODEL_VERSION} by {self.developer}, {self.website}"

    def xmp(self):
        attributes = dict(source_type=SOURCE_TYPE, system=self.system, version=self.version, developer=self.developer,
                          website=self.website, voice=self.voice, watermark=watermark.label())
        return _XMP.format(statement=STATEMENT, language=self.language,
                           **{name: quoteattr(value) for name, value in attributes.items()})
