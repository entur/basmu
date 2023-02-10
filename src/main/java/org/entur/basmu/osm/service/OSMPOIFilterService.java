package org.entur.basmu.osm.service;

import org.entur.basmu.osm.domain.PointOfInterestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;

@Service
public class OSMPOIFilterService {

    private static final Logger logger = LoggerFactory.getLogger(OSMPOIFilterService.class);

    @Value("${osmpoifilters.service.url}")
    private String osmPoiFiltersServiceUrl;

    public List<PointOfInterestFilter> getFilters() {
        WebClient client = WebClient.create(osmPoiFiltersServiceUrl);
        PointOfInterestFilter[] pointOfInterestFilter = client.get()
                .retrieve()
                .bodyToMono(PointOfInterestFilter[].class)
                .doOnError(err -> logger.info("Failed to load poi filters."))
                .block();
        if (pointOfInterestFilter != null) {
            return Arrays.stream(pointOfInterestFilter).toList();
        } else {
            throw new RuntimeException("Did not received any POI filters");
        }
    }
}
