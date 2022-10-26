package org.entur.basmu.osm.pbf;

import org.apache.commons.io.IOUtils;
import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.mapper.TopographicPlaceToPeliasMapper;
import org.entur.basmu.osm.service.OSMPOIFilterService;
import org.entur.geocoder.model.PeliasDocument;
import org.rutebanken.netex.model.IanaCountryTldEnumeration;
import org.rutebanken.netex.model.TopographicPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
            this.poiFilter = poiFilter.stream().filter(filter -> !ObjectUtils.isEmpty(filter)).collect(Collectors.toList());
            logger.info("pelias poiFilter is set to: {}", poiFilter);
        } else {
            this.poiFilter = new ArrayList<>();
            logger.info("No pelias poiFilter found");
        }
    }

    public Collection<PeliasDocument> transform(InputStream poiStream) {
        try {
            List<OSMPOIFilter> osmPoiFilter = osmpoiFilterService.getFilters();
            File tmpPoiFile = getFile(poiStream);
            var reader = new PbfTopographicPlaceReader(osmPoiFilter, IanaCountryTldEnumeration.NO, tmpPoiFile);
            BlockingQueue<TopographicPlace> queue = new LinkedBlockingDeque<>();
            reader.addToQueue(queue);
            List<TopographicPlace> topographicPlaceList = new ArrayList<>(queue);
            return new ArrayList<>(addTopographicPlaceCommands(topographicPlaceList));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private File getFile(InputStream poiStream) throws IOException {
        File tmpPoiFile = File.createTempFile("tmp", "poi");
        tmpPoiFile.deleteOnExit();
        var out = new FileOutputStream(tmpPoiFile);
        IOUtils.copy(poiStream, out);
        return tmpPoiFile;
    }

    private List<PeliasDocument> addTopographicPlaceCommands(List<TopographicPlace> places) {
        if (!CollectionUtils.isEmpty(places)) {
            logger.info("Total number of topographical places from osm: {}", places.size());

            TopographicPlaceToPeliasMapper mapper = new TopographicPlaceToPeliasMapper(poiBoost, poiFilter, osmpoiFilterService.getFilters());
            final List<PeliasDocument> collect = places.stream()
                    .map(mapper::toPeliasDocumentsForNames)
                    .flatMap(Collection::stream)
                    .sorted((p1, p2) -> -p1.getPopularity().compareTo(p2.getPopularity()))
                    .toList();

            logger.info("Total topographical places mapped forElasticsearchCommand: {}", collect.size());
            return collect;
        }
        return new ArrayList<>();
    }
}

