package org.entur.basmu.osm.mapper;

import com.google.common.collect.ArrayListMultimap;
import org.entur.basmu.osm.model.OSMNode;
import org.entur.basmu.osm.model.OSMWay;
import org.entur.basmu.osm.model.Ring;
import org.entur.geocoder.model.GeoPoint;
import org.locationtech.jts.geom.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MappingUtil {

    /**
     * Minimum distance between outer polygons/rings in a relations, distance unit is min/sec
     */
    private static final double MINIMUM_DISTANCE = 0.0002;

    /**
     * Calculate centroid of list of coordinates.
     * <p>
     * If coordinates may be converted to a polygon, the polygon's centroid is used.
     * If not, the centroid of the corresponding multipoint is used.
     */
    public static GeoPoint toCentroid(List<Coordinate> coordinates) {
        Point centroid;
        try {
            centroid = new GeometryFactory()
                    .createPolygon(coordinates.toArray(new Coordinate[0])).getCentroid();
        } catch (RuntimeException re) {
            centroid = new GeometryFactory()
                    .createMultiPointFromCoords(coordinates.toArray(new Coordinate[0])).getCentroid();
        }
        return new GeoPoint(centroid.getY(), centroid.getX());
    }

    public static boolean checkPolygonProximity(List<Polygon> outerPolygons) {
        boolean outerIgnorePolygons = false;
        boolean innerIgnorePolygons = false;
        for (var p : outerPolygons) {
            for (var q : outerPolygons) {
                if (!p.isWithinDistance(q, MINIMUM_DISTANCE)) {
                    innerIgnorePolygons = true;
                    break;
                }
            }
            if (innerIgnorePolygons) {
                outerIgnorePolygons = true;
                break;
            }
        }
        return outerIgnorePolygons;
    }

    public static List<Polygon> makeMultiPolygonsForOSMWays(List<OSMWay> osmWays, Map<Long, OSMNode> nodesMap) {
        final List<Ring> outerRingNodes = MappingUtil.constructRings(osmWays);

        return outerRingNodes.stream()
                .map(ring -> makePolygon(ring, nodesMap))
                .filter(Objects::nonNull)
                .toList();
    }

    public static List<Ring> constructRings(List<OSMWay> ways) {
        if (ways.isEmpty()) {
            return Collections.emptyList();
        }

        var closedRings = ways.stream()
                .map(Ring::withWay)
                .filter(Ring::isClosed)
                .toList();

        if (closedRings.size() == ways.size()) {
            return closedRings;
        }

        var partialRings = ways.stream()
                .map(Ring::withWay)
                .filter(Predicate.not(Ring::isClosed))
                .collect(PartialRingsCollector());

        if (!isValidPartialRings(partialRings)) {
            return Collections.emptyList();
        }

        List<Ring> newClosedRings = makeClosedRings(partialRings);

        return Stream.of(closedRings, newClosedRings).flatMap(Collection::stream).toList();
/*
        List<Set<Long>> sets = newClosedRings.stream()
                .map(Ring::closeThisRing)
                .toList();


        return closedRings;

        Ring partialRing = partialRings.keys().stream()
                .findFirst()
                .map(partialRings::get)
                .map(osmWays -> osmWays.get(0))
                .orElseThrow();

//        List<Long> partialRing = firstWay.getNodeRefs().stream().toList();

        partialRings.remove(partialRing.getStart(), partialRing);
        partialRings.remove(partialRing.getEnd(), partialRing);

        if (constructRingsRecursive(partialRings, partialRing, closedRings, partialRing.getStart())) {
            return closedRings;
        } else {
            return Collections.emptyList();
        }

 */
    }

    private static List<Ring> makeClosedRings(ArrayListMultimap<Long, Ring> partialRings) {

        if (partialRings.keySet().size() == 1) {
            System.out.println();
        }

        List<Long> ignoreKeys = new ArrayList<>();
        List<Ring> newRings = new ArrayList<>();

        for (Long key : partialRings.keySet()) {
            if (ignoreKeys.contains(key)) {
                continue;
            }

            Ring ring = findConnections(key, partialRings, ignoreKeys, 0);
            newRings.add(ring);
/*            List<Ring> rings = partialRings.get(key);
            rings.stream().findAny()
                    .map(ring -> List.of(ring.getStart(), ring.getEnd()))
                    .stream().flatMap(Collection::stream)
                    .filter(endPoint -> !Objects.equals(endPoint, key))
                    .forEach(ignoreKeys::add);
            Ring newRing = joinRings(rings);
            newRings.add(newRing);

 */
        }

        var closedAndPartialRings = newRings.stream().collect(Collectors.partitioningBy(Ring::isClosed));
        if (closedAndPartialRings.get(false).size() == 0) {
            return closedAndPartialRings.get(true);
        }

        var remainingPartialRings = makeClosedRings(
                closedAndPartialRings.get(false).stream()
                        .collect(PartialRingsCollector())
        );

        return Stream.of(remainingPartialRings, newRings).flatMap(Collection::stream).toList();
    }

    private static Ring findConnections(Long key, ArrayListMultimap<Long, Ring> partialRings, List<Long> keysTaken, int call) {
        List<Ring> rings = partialRings.get(key);
        Ring newRing = joinRings(rings);
        keysTaken.add(key);

        Set<Long> connections = newRing.ways().stream()
                .map(way -> List.of(way.getStart(), way.getEnd()))
                .flatMap(Collection::stream)
                .filter(endPoint -> !Objects.equals(endPoint, key))
                .collect(Collectors.toSet());

        final int calli = ++call;
        List<Ring> connectionRings = connections.stream()
                .filter(Predicate.not(keysTaken::contains))
                .map(connection -> findConnections(connection, partialRings, keysTaken, calli))
                .toList();

        return joinRings(Stream.of(connectionRings, List.of(newRing)).flatMap(Collection::stream).toList());
    }

    private static boolean isValidPartialRings(ArrayListMultimap<Long, Ring> partialRings) {
        return partialRings.asMap().values().stream()
                .map(Collection::size)
                .noneMatch(size -> size != 2);
    }

    private static Ring joinRings(List<Ring> rings) {
        return new Ring(
                rings.stream()
                        .map(Ring::ways)
                        .flatMap(Collection::stream)
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                        .stream().toList());
    }

    private static Collector<Ring, ArrayListMultimap<Long, Ring>, ArrayListMultimap<Long, Ring>>
    PartialRingsCollector() {
        return Collector.of(
                ArrayListMultimap::create,
                (map, ring) -> {
                    map.put(ring.getStart(), ring);
                    map.put(ring.getEnd(), ring);
                },
                (a, b) -> {
                    a.putAll(b);
                    return a;
                }
        );
    }

    private static Polygon makePolygon(Ring ring, Map<Long, OSMNode> nodes) {

        Coordinate[] coordinates = ring.getClosedRingNodeRefs().stream()
                .map(nodes::get)
                .map(node -> new Coordinate(node.getLon(), node.getLat()))
                .toArray(Coordinate[]::new);

        try {
            return new GeometryFactory().createPolygon(coordinates);
        } catch (IllegalArgumentException illegalArgumentException) {
            // TODO: Uncomment
//            logger.debug("Unable to create polygon: " + illegalArgumentException.getMessage());
            return null;
        }
    }
}