package org.entur.basmu.osm.pbf;

import com.google.common.collect.ArrayListMultimap;
import org.entur.basmu.osm.model.OSMWay;
import org.entur.geocoder.model.GeoPoint;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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

    public static List<List<Long>> constructRings(List<OSMWay> ways) {
        if (ways.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Long>> closedRings = new ArrayList<>();
        ArrayListMultimap<Long, OSMWay> waysByEndpoint = ArrayListMultimap.create();
        for (OSMWay way : ways) {
            final List<Long> refs = way.getNodeRefs();

            var start = refs.get(0);
            var end = refs.get(refs.size() - 1);

            // This way is a Closed Ring
            if (start.equals(end)) {
                ArrayList<Long> ring = new ArrayList<>(refs);
                closedRings.add(ring);
                // This way is not a Closed ring
            } else {
                waysByEndpoint.put(start, way);
                waysByEndpoint.put(end, way);
            }
        }

        // No non Closed rings found. Return Closed rings
        if (waysByEndpoint.isEmpty()) {
            return closedRings;
        }
        // ELSE: Resolve partial rings.

        for (Long endpoint : waysByEndpoint.keySet()) {
            Collection<OSMWay> list = waysByEndpoint.get(endpoint);
            if (list.size() % 2 == 1) { // TODO: Should not check for even but exactly  2.
                return Collections.emptyList();
            }
        }

        List<Long> partialRing = new ArrayList<>();
        long firstEndpoint = 0;
        long otherEndpoint = 0;

        OSMWay firstWay = null;

        for (Long endpoint : waysByEndpoint.keySet()) {
            final List<OSMWay> list = waysByEndpoint.get(endpoint);
            firstWay = list.get(0);
            final List<Long> nodeRefs = firstWay.getNodeRefs();
            partialRing.addAll(nodeRefs);
            firstEndpoint = nodeRefs.get(0);
            otherEndpoint = nodeRefs.get(nodeRefs.size() - 1);
            break;
        }
        waysByEndpoint.get(firstEndpoint).remove(firstWay);
        waysByEndpoint.get(otherEndpoint).remove(firstWay);

        if (constructRingsRecursive(waysByEndpoint, partialRing, closedRings, firstEndpoint)) {
            return closedRings;
        } else {
            return Collections.emptyList();
        }
    }

    private static boolean constructRingsRecursive(ArrayListMultimap<Long, OSMWay> waysByEndpoint,
                                                   List<Long> ring,
                                                   List<List<Long>> closedRings,
                                                   long endpoint) {
        List<OSMWay> ways = new ArrayList<>(waysByEndpoint.get(endpoint));
        for (OSMWay way : ways) {
            //remove this way from the map
            List<Long> nodeRefs = way.getNodeRefs();
            long firstEndpoint = nodeRefs.get(0);
            long otherEndpoint = nodeRefs.get(nodeRefs.size() - 1);

            waysByEndpoint.remove(firstEndpoint, way);
            waysByEndpoint.remove(otherEndpoint, way);

            ArrayList<Long> newRing = new ArrayList<>(ring.size() + nodeRefs.size());
            long newFirstEndpoint;
            if (firstEndpoint == endpoint) {
                for (int i = nodeRefs.size() - 1; i >= 1; --i) {
                    newRing.add(nodeRefs.get(i));
                }
                newRing.addAll(ring);
                newFirstEndpoint = otherEndpoint;
            } else {
                newRing.addAll(nodeRefs.subList(0, nodeRefs.size() - 1));
                newRing.addAll(ring);
                newFirstEndpoint = firstEndpoint;
            }

            if (newRing.get(newRing.size() - 1).equals(newRing.get(0))) {
                //Closing ring
                closedRings.add(newRing);
                //out of endpoints done parsing
                if (waysByEndpoint.size() == 0) {
                    return true;
                }

                //otherwise start new partial ring
                newRing = new ArrayList<>();
                OSMWay firstWay = null;
                for (Long entry : waysByEndpoint.keySet()) {
                    final List<OSMWay> list = waysByEndpoint.get(entry);
                    firstWay = list.get(0);
                    nodeRefs = firstWay.getNodeRefs();
                    newRing.addAll(nodeRefs);
                    firstEndpoint = nodeRefs.get(0);
                    otherEndpoint = nodeRefs.get(nodeRefs.size() - 1);
                    break;
                }

                waysByEndpoint.remove(firstEndpoint, way);
                waysByEndpoint.remove(otherEndpoint, way);

                if (constructRingsRecursive(waysByEndpoint, newRing, closedRings, firstEndpoint)) {
                    return true;
                }

                waysByEndpoint.remove(firstEndpoint, firstWay);
                waysByEndpoint.remove(otherEndpoint, firstWay);

            } else {
                // Continue with ring
                if (waysByEndpoint.get(newFirstEndpoint) != null) {
                    return constructRingsRecursive(waysByEndpoint, newRing, closedRings, newFirstEndpoint);
                }
            }
            if (firstEndpoint == endpoint) {
                waysByEndpoint.put(otherEndpoint, way);
            } else {
                waysByEndpoint.put(firstEndpoint, way);
            }
        }
        return false;
    }
}