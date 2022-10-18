package org.entur.basmu.osm.service;

import org.entur.basmu.osm.domain.OSMPOIFilter;

import java.util.List;

public interface OSMPOIFilterService {
    List<OSMPOIFilter> getFilters();

    void updateFilters(List<OSMPOIFilter> filters);
}
