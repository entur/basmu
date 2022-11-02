package org.entur.basmu.osm.model;

public record OSMRelationMember(String type, long ref, String role) {

    @Override
    public String toString() {
        return "osm rel " + type + ":" + role + ":" + ref;
    }
}
