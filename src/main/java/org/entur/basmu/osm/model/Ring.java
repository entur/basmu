package org.entur.basmu.osm.model;

import java.util.*;

public record Ring(List<OSMWay> ways) {
    public static Ring withWay(OSMWay osmWay) {
        return new Ring(List.of(new OSMWay(osmWay)));
    }

    public boolean isClosed() {
        if (ways().isEmpty()) {
            return false;
        }

        return ways.stream().allMatch(thisWay ->
                ways.stream().anyMatch(thatWay ->
                        thisWay.getEnd().equals(thatWay.getEnd())
                        || thisWay.getEnd().equals(thatWay.getStart())
                ));
    }

    public Long getStart() {
        return ways.get(0).getStart();
    }

    public Long getEnd() {
        return ways.get(ways.size() - 1).getEnd();
    }

    public List<Long> getClosedRingNodeRefs() {
        if (!isClosed()) {
            throw new RuntimeException("Ring is not closed.");
        }

        List<Long> nodeRefs = new ArrayList<>();

        ways.stream().findFirst()
                .ifPresent(way -> {
                    nodeRefs.addAll(way.getNodeRefs());
                });

        for (int i = 0; i < ways.size() - 1; i++) {
            ways.stream()
                    .filter(way -> way.getStart().equals(nodeRefs.get(nodeRefs.size() - 1)))
                    .findFirst()
                    .ifPresent(way -> nodeRefs.addAll(way.getNodeRefsTrimStart()));
        }

        return nodeRefs;
    }
}
