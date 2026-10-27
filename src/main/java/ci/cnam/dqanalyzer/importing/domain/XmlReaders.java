package ci.cnam.dqanalyzer.importing.domain;

import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecteurs XML des formats qui en contiennent (.xlsx, .ods, .xml). Toujours sans DTD ni entités
 * externes : un fichier reçu d'un service n'a pas à faire lire un fichier du serveur ni à le faire
 * sortir sur le réseau (SEC-03).
 */
final class XmlReaders {

    private XmlReaders() {}

    static XMLStreamReader create(InputStream in) throws XMLStreamException {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(in);
    }
}
