package org.entur.basmu.osm.pbf;

import crosby.binary.file.BlockInputStream;
import org.apache.commons.io.IOUtils;
import org.entur.basmu.osm.BinaryOpenStreetMapParser;
import org.entur.basmu.osm.OpenStreetMapContentHandler;
import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.service.OSMPOIFilterService;
import org.entur.geocoder.model.PeliasDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

@Service
public class PbfToElasticsearchCommands {

    public static final Logger logger = LoggerFactory.getLogger(PbfToElasticsearchCommands.class);

    private final OSMPOIFilterService osmpoiFilterService;

    private final long poiBoost;

    private final List<String> poiFilter;

    public PbfToElasticsearchCommands(@Autowired OSMPOIFilterService osmpoiFilterService,
                                      @Value("${pelias.poi.boost:1}") long poiBoost,
                                      @Value("#{'${pelias.poi.filter:}'.split(',')}") List<String> poiFilter) {
        this.osmpoiFilterService = osmpoiFilterService;
        this.poiBoost = poiBoost;
        if (poiFilter != null) {
            this.poiFilter = poiFilter.stream()
                    .filter(filter -> !ObjectUtils.isEmpty(filter))
                    .collect(Collectors.toList());
            logger.info("pelias poiFilter is set to: {}", poiFilter);
        } else {
            this.poiFilter = new ArrayList<>();
            logger.info("No pelias poiFilter found");
        }
    }

    public Collection<PeliasDocument> transform(InputStream poiStream) {
        try {
            List<OSMPOIFilter> osmPoiFilters = osmpoiFilterService.getFilters();
            File tmpPoiFile = getFile(poiStream);
            BlockingQueue<PeliasDocument> queue = new LinkedBlockingDeque<>();
            addToQueue(queue, tmpPoiFile, osmPoiFilters);
            return new ArrayList<>(queue);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void addToQueue(BlockingQueue<PeliasDocument> queue, File file, List<OSMPOIFilter> osmPoiFilters) throws IOException {
        OpenStreetMapContentHandler contentHandler = new OsmContentHandler(
                queue,
                osmPoiFilters,
                poiBoost,
                poiFilter);
        BinaryOpenStreetMapParser parser = new BinaryOpenStreetMapParser(contentHandler);

        //Parse relations to collect ways first
        parser.setParseWays(false);
        parser.setParseNodes(false);

        new BlockInputStream(new FileInputStream(file), parser).process();
        parser.setParseRelations(false);

        // Parse ways to collect nodes first
        parser.setParseWays(true);
        new BlockInputStream(new FileInputStream(file), parser).process();
        contentHandler.doneSecondPhaseWays();

        // Parse nodes and ways
        parser.setParseNodes(true);
        new BlockInputStream(new FileInputStream(file), parser).process();
        contentHandler.doneThirdPhaseNodes();
    }

    private File getFile(InputStream poiStream) throws IOException {
        File tmpPoiFile = File.createTempFile("tmp", "poi");
        tmpPoiFile.deleteOnExit();
        var out = new FileOutputStream(tmpPoiFile);
        IOUtils.copy(poiStream, out);
        return tmpPoiFile;
    }
}

