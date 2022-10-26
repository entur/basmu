package org.entur.basmu.osm.service;

import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service("osmPoiFilterService")
public class OSMPOIFilterServiceImpl implements OSMPOIFilterService {

    @Override
    public List<OSMPOIFilter> getFilters() {
        return Stream.of(
                        new OSMPOIFilter("amenity", "cinema", 1),
                        new OSMPOIFilter("amenity", "clinic", 1),
                        new OSMPOIFilter("amenity", "college", 1),
                        new OSMPOIFilter("amenity", "doctors", 1),
                        new OSMPOIFilter("amenity", "embassy", 1),
                        new OSMPOIFilter("amenity", "exhibition_center", 1),
                        new OSMPOIFilter("amenity", "hospital", 1),
                        new OSMPOIFilter("amenity", "kindergarten", 1),
                        new OSMPOIFilter("amenity", "nursing_home", 1),
                        new OSMPOIFilter("amenity", "place_of_worship", 1),
                        new OSMPOIFilter("amenity", "prison", 1),
                        new OSMPOIFilter("amenity", "school", 1),
                        new OSMPOIFilter("amenity", "theatre", 1),
                        new OSMPOIFilter("amenity", "university", 1),
                        new OSMPOIFilter("landuse", "cemetery", 1),
                        new OSMPOIFilter("leisure", "park", 1),
                        new OSMPOIFilter("leisure", "sports_centre", 1),
                        new OSMPOIFilter("leisure", "stadium", 1),
                        new OSMPOIFilter("office", "government", 1),
                        new OSMPOIFilter("shop", "mall", 1),
                        new OSMPOIFilter("social_facility", "nursing_home", 1),
                        new OSMPOIFilter("tourism", "museum", 1),
                        new OSMPOIFilter("name", "Entur AS", 1),
                        new OSMPOIFilter("name", "Kristiansand Dyrepark", 1),
                        new OSMPOIFilter("tourism", "event", 1),
                        new OSMPOIFilter("amenity", "golf_course", 1),
                        new OSMPOIFilter("amenity", "library", 1))
                .toList();
    }
}
