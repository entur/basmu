package org.entur.basmu.osm.domain;

public record OSMPOIFilter(String key, String value, Integer priority) {

    public static int sort(OSMPOIFilter a, OSMPOIFilter b) {
        if (a.priority() > b.priority()) {
            return 1;
        } else if (b.priority() > a.priority()) {
            return -1;
        }
        return 0;
    }
}
