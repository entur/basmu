package org.entur.basmu.openstreetmap.impl;

import crosby.binary.file.BlockInputStream;
import org.entur.basmu.openstreetmap.services.OpenStreetMapContentHandler;
import org.entur.basmu.openstreetmap.services.OpenStreetMapProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parser for the OpenStreetMap PBF format. Parses files in three passes: First the relations, then
 * the ways, then the nodes are also loaded.
 *
 * @see http://wiki.openstreetmap.org/wiki/PBF_Format
 * @see OpenStreetMapContentHandler#biPhase
 * @since 0.4
 */
public class BinaryFileBasedOpenStreetMapProviderImpl implements OpenStreetMapProvider {

    private final File file;

    public BinaryFileBasedOpenStreetMapProviderImpl(File file) {
        this.file = file;
    }

    public void readOSM(OpenStreetMapContentHandler handler) {
        try {
            BinaryOpenStreetMapParser parser = new BinaryOpenStreetMapParser(handler);
            parseIteration(parser, 1);
            handler.doneFirstPhaseRelations();

            parseIteration(parser, 2);
            handler.doneSecondPhaseWays();

            parseIteration(parser, 3);
            handler.doneThirdPhaseNodes();
        }
        catch (Exception ex) {
            throw new IllegalStateException("error loading OSM", ex);
        }
    }

    private void parseIteration(BinaryOpenStreetMapParser parser, int iteration) throws IOException {
        parser.setParseRelations(iteration == 1);
        parser.setParseWays(iteration == 2);
        parser.setParseNodes(iteration == 3);
        try (InputStream in = new FileInputStream(file)) {
            new BlockInputStream(in, parser).process();
        }
    }

    @Override
    public void checkInputs() { }

    public String toString() {
        return "BinaryFileBasedOpenStreetMapProviderImpl()";
    }
}
