package org.entur.basmu.osm.model;

public class OSMNode extends OSMWithTags {

    private double lat;
    private double lon;

    public OSMNode(long id, double lat, double lon) {
        super(id);
        this.lat = lat;
        this.lon = lon;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public String toString() {
        return "osm node " + getId();
    }
}
