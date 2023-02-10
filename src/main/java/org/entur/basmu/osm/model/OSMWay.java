package org.entur.basmu.osm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OSMWay extends OSMWithTags {

    private final List<Long> nodeRefs = new ArrayList<>();

    OSMWay(OSMWay osmWay) {
        super(osmWay.getId());
        osmWay.getTags().forEach(super::addTag);
        osmWay.getNodeRefs().forEach(this::addNodeRef);
    }

    public OSMWay(long id) {
        super(id);
    }

    public List<Long> getNodeRefs() {
        return nodeRefs.stream().toList();
    }

    public void addNodeRef(Long nodeRef) {
        nodeRefs.add(nodeRef);
    }

    public Long getStart() {
        return nodeRefs.get(0);
    }

    public Long getEnd() {
        return nodeRefs.get(nodeRefs.size() - 1);
    }

    public List<Long> getNodeRefsTrimEnd() {
        return nodeRefs.stream().limit(nodeRefs.size() - 1).collect(Collectors.toList());
    }

    public List<Long> getNodeRefsTrimStart() {
        return nodeRefs.stream().skip(1).collect(Collectors.toList());
    }

    public String toString() {
        return "osm way " + getId();
    }

    public boolean isClosed() {
        return getStart().equals(getEnd());
    }
}
