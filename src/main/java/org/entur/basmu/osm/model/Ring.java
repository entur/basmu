package org.entur.basmu.osm.model;

import java.util.*;
import java.util.stream.Collectors;

public record Ring(List<OSMWay> ways) {
    public static Ring withWay(OSMWay osmWay) {
        return new Ring(List.of(new OSMWay(osmWay)));
    }

    public boolean isClosed() {
        if (ways.isEmpty()) {
            return false;
        }

        if (ways.size() == 1) {
            return ways.get(0).isClosed();
        }

        return ways.stream().allMatch(thisWay ->
                ways.stream()
                        .filter(way -> way.getId() != thisWay.getId())
                        .anyMatch(
                                thatWay -> thisWay.getEnd().equals(thatWay.getEnd())
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

        List<Long> waysDone = new ArrayList<>();
        List<Long> nodeRefs = ways.stream().findFirst()
                .map(osmWay -> {
                    waysDone.add(osmWay.getId());
                    return osmWay.getNodeRefs();
                }).stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        while (waysDone.size() != ways.size()) {
            List<Long> refsFound = ways.stream()
                    .filter(way -> !waysDone.contains(way.getId()))
                    .filter(way -> nodeRefs.get(nodeRefs.size() - 1).equals(way.getStart()))
                    .findFirst()
                    .map(way -> {
                        waysDone.add(way.getId());
                        return way.getNodeRefsTrimStart();
                    })
                    .orElseGet(() -> ways.stream()
                            .filter(way -> !waysDone.contains(way.getId()))
                            .filter(way -> nodeRefs.get(nodeRefs.size() - 1).equals(way.getEnd()))
                            .findFirst()
                            .map(way -> {
                                waysDone.add(way.getId());
                                List<Long> nodeRefsTrimEnd = way.getNodeRefsTrimEnd();
                                Collections.reverse(nodeRefsTrimEnd);
                                return nodeRefsTrimEnd;
                            }).orElseThrow());

            nodeRefs.addAll(refsFound);
        }

        return nodeRefs;
    }
}
