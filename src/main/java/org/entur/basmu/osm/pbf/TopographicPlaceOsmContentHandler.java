/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package org.entur.basmu.osm.pbf;

import com.google.common.collect.ArrayListMultimap;
import org.entur.basmu.osm.OpenStreetMapContentHandler;
import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.mapper.TopographicPlaceMapper;
import org.entur.basmu.osm.model.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.rutebanken.netex.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

/**
 * Map OSM nodes and ways to Netex topographic place.
 * <p>
 * Ways refer to nodes for coordinates. Because of this, files must be parsed twice,
 * first to collect node ids referred by relevant ways and then to map relevant nodes and ways.
 */
public class TopographicPlaceOsmContentHandler implements OpenStreetMapContentHandler {
    private static final Logger logger = LoggerFactory.getLogger(TopographicPlaceOsmContentHandler.class);
    private static final double MINIMUM_DISTANCE = 0.0002; // Minimum distance between outer polygons/rings in a relations, distance unit is min/sec
    private final BlockingQueue<TopographicPlace> topographicPlaceQueue;
    private final List<OSMPOIFilter> osmPoiFilters;
    private final IanaCountryTldEnumeration countryRef;
    private final Map<Long, OSMNode> nodesMapForWays = new HashMap<>();

    private final Set<Long> nodeRefsForWays = new HashSet<>();
    private final Set<Long> nodeRefsForMultipolygonRelations = new HashSet<>();
    private final Set<Long> wayRefsForMultipolygonRelations = new HashSet<>();

    private final Map<Long, OSMRelation> multiPolygonRelationsMap = new HashMap<>();
    private final Map<Long, OSMWay> waysMapForMultipolygonRelations = new HashMap<>();
    private final Map<Long, OSMNode> nodesMapForMultipolygonRelations = new HashMap<>();

    private boolean gatherNodesUsedInWaysPhase = true;

    public TopographicPlaceOsmContentHandler(BlockingQueue<TopographicPlace> topographicPlaceQueue,
                                             List<OSMPOIFilter> osmPoiFilters,
                                             IanaCountryTldEnumeration countryRef) {
        this.topographicPlaceQueue = topographicPlaceQueue;
        this.osmPoiFilters = osmPoiFilters;
        this.countryRef = countryRef;
    }

    @Override
    public void doneSecondPhaseWays() {
        gatherNodesUsedInWaysPhase = false;
    }

    @Override
    public void doneThirdPhaseNodes() {
        processMultipolygonRelations();
    }

    @Override
    public void addNode(OSMNode osmNode) {
        if (matchesFilter(osmNode)) {
            TopographicPlace topographicPlace = TopographicPlaceMapper.map(osmNode, countryRef)
                    .withCentroid(TopographicPlaceMapper.toCentroid(osmNode.lat, osmNode.lon));
            topographicPlaceQueue.add(topographicPlace);
        }

        if (nodeRefsForWays.contains(osmNode.getId())) {
            nodesMapForWays.put(osmNode.getId(), osmNode);
        }

        if (nodesMapForMultipolygonRelations.containsKey(osmNode.getId())) {
            return;
        }

        if (nodeRefsForMultipolygonRelations.contains(osmNode.getId())) {
            nodesMapForMultipolygonRelations.put(osmNode.getId(), osmNode);
        }
    }

    @Override
    public void addWay(OSMWay osmWay) {
        var wayId = osmWay.getId();
        if (waysMapForMultipolygonRelations.containsKey(wayId)) {
            return;
        }

        // TODO: I think, this needs to be done only when gatherNodesUsedInWaysPhase = true
        if (wayRefsForMultipolygonRelations.contains(wayId)) {
            if (!gatherNodesUsedInWaysPhase) {
                logger.debug("waysById = " + waysMapForMultipolygonRelations.containsKey(wayId) + " nodeRefsUsedInRel = " + nodeRefsForMultipolygonRelations.containsAll(osmWay.getNodeRefs()));
            }
            waysMapForMultipolygonRelations.put(wayId, osmWay);
            nodeRefsForMultipolygonRelations.addAll(osmWay.getNodeRefs());
        }

        if (matchesFilter(osmWay)) {
            if (gatherNodesUsedInWaysPhase) {
                nodeRefsForWays.addAll(osmWay.getNodeRefs());
            } else {
                SimplePoint_VersionStructure centroid = getCentroid(osmWay);
                if (centroid != null) {
                    TopographicPlace topographicPlace = TopographicPlaceMapper.map(osmWay, countryRef).withCentroid(centroid);
                    topographicPlaceQueue.add(topographicPlace);
                }
            }
        }
    }

    @Override
    public void addRelation(OSMRelation osmRelation) {
        if (!multiPolygonRelationsMap.containsKey(osmRelation.getId())
                && osmRelation.isTag("type", "multipolygon")
                && matchesFilter(osmRelation)) {
            var members = osmRelation.getMembers();
            members.forEach(member -> {
                if (member.getType().equals("way")) {
                    wayRefsForMultipolygonRelations.add(member.getRef());
                }
            });
            multiPolygonRelationsMap.put(osmRelation.getId(), osmRelation);
        }
    }

    private SimplePoint_VersionStructure getCentroid(OSMWay osmWay) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (Long nodeRef : osmWay.getNodeRefs()) {
            OSMNode node = nodesMapForWays.get(nodeRef);
            if (node != null) {
                coordinates.add(new Coordinate(node.lon, node.lat));
            }
        }

        if (coordinates.size() != osmWay.getNodeRefs().size()) {
            logger.info("Ignoring osmWay with missing nodes: " + osmWay.getAssumedName());
            return null;
        }
        return TopographicPlaceMapper.toCentroid(coordinates);

        /* TODO: Polygon is not needed
        try {
            topographicPlace.withPolygon(TopographicPlaceMapper.toPolygon(coordinates, osmWay.getId()));
        } catch (RuntimeException e) {
            logger.info("Could not create polygon for osm way: " + osmWay.getAssumedName() + ". Exception: " + e.getMessage());
        }*/
    }

    private void processMultipolygonRelations() {
        var counter = 0;
        for (OSMRelation relation : multiPolygonRelationsMap.values()) {

            // TODO: This if check is not needed as this condition is already satisfied in addRelation
            if (relation.isTag("type", "multipolygon") && matchesFilter(relation)) {

                var innerWaysOfMultipolygonRelation = new ArrayList<OSMWay>();
                var outerWaysOfMultipolygonRelation = new ArrayList<OSMWay>();

                for (OSMRelationMember member : relation.getMembers()) {
                    final String role = member.getRole();
                    final OSMWay way = waysMapForMultipolygonRelations.get(member.getRef());

                    if (way != null) {
                        if (role.equals("inner")) {
                            innerWaysOfMultipolygonRelation.add(way);
                        } else if (role.equals("outer")) {
                            outerWaysOfMultipolygonRelation.add(way);
                        } else {
                            logger.warn("Unexpected role " + role + " in multipolygon");
                        }
                    }
                }
                SimplePoint_VersionStructure centroid = getCentroid(innerWaysOfMultipolygonRelation, outerWaysOfMultipolygonRelation);
                if (centroid != null) {
                    var topographicPlace = TopographicPlaceMapper.map(relation, countryRef).withCentroid(centroid);
                    topographicPlaceQueue.add(topographicPlace);
                    counter++;
                }
            }
        }
        logger.info("Total {} multipolygon POIs added.", counter);
    }

    private SimplePoint_VersionStructure getCentroid(ArrayList<OSMWay> innerWaysOfMultipolygonRelation,
                                                     ArrayList<OSMWay> outerWaysOfMultipolygonRelation) {

        List<Polygon> polygons = new ArrayList<>();

        final List<List<Long>> outerRingNodes = constructRings(outerWaysOfMultipolygonRelation);
        final List<List<Long>> innerRingNodes = constructRings(innerWaysOfMultipolygonRelation);

        var outerPolygons = outerRingNodes.stream()
                .map(ring -> new Ring(ring, nodesMapForMultipolygonRelations).getPolygon())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        var innerPolygons = innerRingNodes.stream()
                .map(ring -> new Ring(ring, nodesMapForMultipolygonRelations).getPolygon())
                .filter(Objects::nonNull).toList();

        if (!outerPolygons.isEmpty() && !checkPolygonProximity(outerPolygons)) {
            polygons.addAll(outerPolygons);
            polygons.addAll(innerPolygons);

            try {
                var multiPolygon = new GeometryFactory().createMultiPolygon(polygons.toArray(new Polygon[0]));
                var interiorPoint = multiPolygon.getInteriorPoint();
                return TopographicPlaceMapper.toCentroid(interiorPoint.getY(), interiorPoint.getX());
            } catch (RuntimeException e) {
                logger.warn("unable to add geometry" + e);
                return null;
            }
        }
        return null;
    }

    private boolean checkPolygonProximity(List<Polygon> outerPolygons) {
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

    private List<List<Long>> constructRings(List<OSMWay> ways) {
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

    private boolean constructRingsRecursive(ArrayListMultimap<Long, OSMWay> waysByEndpoint,
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

    private static final String TAG_NAME = "name";

    private boolean matchesFilter(OSMWithTags entity) {
        if (!entity.hasTag(TAG_NAME)) {
            return false;
        }

        for (Map.Entry<String, String> tag : entity.getTags().entrySet()) {
            if (osmPoiFilters.stream()
                    .anyMatch(filter -> (tag.getKey().equals(filter.key()) && tag.getValue().startsWith(filter.value())))) {
                return true;
            }
        }
        return false;
    }
}
