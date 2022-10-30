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

import org.entur.basmu.osm.OpenStreetMapContentHandler;
import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.mapper.OSMToPeliasDocumentMapper;
import org.entur.basmu.osm.model.*;
import org.entur.geocoder.model.GeoPoint;
import org.entur.geocoder.model.PeliasDocument;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * Map OSM nodes and ways to Netex topographic place.
 * <p>
 * Ways refer to nodes for coordinates. Because of this, files must be parsed twice,
 * first to collect node ids referred by relevant ways and then to map relevant nodes and ways.
 */
public class OsmContentHandler implements OpenStreetMapContentHandler {
    private static final Logger logger = LoggerFactory.getLogger(OsmContentHandler.class);
    private static final String TAG_NAME = "name";

    private final BlockingQueue<PeliasDocument> peliasDocumentQueue;
    private final List<OSMPOIFilter> osmPoiFilters;
    private final Map<Long, OSMNode> nodesMapForWays = new HashMap<>();

    private final Set<Long> nodeRefsForWays = new HashSet<>();
    private final Set<Long> nodeRefsForMultipolygonRelations = new HashSet<>();
    private final Set<Long> wayRefsForMultipolygonRelations = new HashSet<>();

    private final Map<Long, OSMRelation> multiPolygonRelationsMap = new HashMap<>();
    private final Map<Long, OSMWay> waysMapForMultipolygonRelations = new HashMap<>();
    private final Map<Long, OSMNode> nodesMapForMultipolygonRelations = new HashMap<>();

    private final OSMToPeliasDocumentMapper osmToPeliasDocumentMapper;

    private boolean gatherNodesUsedInWaysPhase = true;

    public OsmContentHandler(BlockingQueue<PeliasDocument> peliasDocumentQueue,
                             List<OSMPOIFilter> osmPoiFilters,
                             long poiBoost,
                             List<String> poiFilter) {
        this.peliasDocumentQueue = peliasDocumentQueue;
        this.osmPoiFilters = osmPoiFilters;
        this.osmToPeliasDocumentMapper = new OSMToPeliasDocumentMapper(poiBoost, poiFilter, osmPoiFilters);
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
            peliasDocumentQueue.addAll(osmToPeliasDocumentMapper.map(osmNode, new GeoPoint(osmNode.lat, osmNode.lon)));
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
                GeoPoint centroid = getCentroid(osmWay);
                if (centroid != null) {
                    List<PeliasDocument> peliasDocuments = osmToPeliasDocumentMapper.map(osmWay, centroid);
                    peliasDocumentQueue.addAll(peliasDocuments);
                } else {
                    logger.info("Ignoring osmWay with missing nodes: " + osmWay.getAssumedName());
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
                GeoPoint centroid = getCentroid(innerWaysOfMultipolygonRelation, outerWaysOfMultipolygonRelation);
                if (centroid != null) {
                    peliasDocumentQueue.addAll(osmToPeliasDocumentMapper.map(relation, centroid));
                    counter++;
                }
            }
        }
        logger.info("Total {} multipolygon POIs added.", counter);
    }

    private GeoPoint getCentroid(ArrayList<OSMWay> innerWaysOfMultipolygonRelation,
                                 ArrayList<OSMWay> outerWaysOfMultipolygonRelation) {

        List<Polygon> polygons = new ArrayList<>();

        final List<List<Long>> outerRingNodes = MappingUtil.constructRings(outerWaysOfMultipolygonRelation);
        final List<List<Long>> innerRingNodes = MappingUtil.constructRings(innerWaysOfMultipolygonRelation);

        var outerPolygons = outerRingNodes.stream()
                .map(ring -> new Ring(ring, nodesMapForMultipolygonRelations).getPolygon())
                .filter(Objects::nonNull)
                .toList();
        var innerPolygons = innerRingNodes.stream()
                .map(ring -> new Ring(ring, nodesMapForMultipolygonRelations).getPolygon())
                .filter(Objects::nonNull).toList();

        if (!outerPolygons.isEmpty() && !MappingUtil.checkPolygonProximity(outerPolygons)) {
            polygons.addAll(outerPolygons);
            polygons.addAll(innerPolygons);

            try {
                var multiPolygon = new GeometryFactory().createMultiPolygon(polygons.toArray(new Polygon[0]));
                var interiorPoint = multiPolygon.getInteriorPoint();
                return new GeoPoint(interiorPoint.getY(), interiorPoint.getX());
            } catch (RuntimeException e) {
                logger.warn("unable to add geometry" + e);
                return null;
            }
        }
        return null;
    }

    private boolean matchesFilter(OSMWithTags entity) {
        if (!entity.hasTag(TAG_NAME)) {
            return false;
        }

        for (Map.Entry<String, String> tag : entity.getTags().entrySet()) {
            if (osmPoiFilters.stream()
                    .anyMatch(filter ->
                            tag.getKey().equals(filter.key())
                                    && tag.getValue().startsWith(filter.value()))) {
                return true;
            }
        }
        return false;
    }

    private GeoPoint getCentroid(OSMWay osmWay) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (Long nodeRef : osmWay.getNodeRefs()) {
            OSMNode node = nodesMapForWays.get(nodeRef);
            if (node != null) {
                coordinates.add(new Coordinate(node.lon, node.lat));
            }
        }

        if (coordinates.size() != osmWay.getNodeRefs().size()) {
            return null;
        }
        return MappingUtil.toCentroid(coordinates);
    }
}
