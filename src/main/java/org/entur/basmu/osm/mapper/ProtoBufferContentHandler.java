package org.entur.basmu.osm.mapper;

import org.entur.basmu.osm.domain.PointOfInterestFilter;
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
import java.util.stream.Stream;

/**
 * Map OSM nodes and ways to Netex topographic place.
 * <p>
 * Ways refer to nodes for coordinates. Because of this, files must be parsed twice,
 * first to collect node ids referred by relevant ways and then to map relevant nodes and ways.
 */
public class ProtoBufferContentHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProtoBufferContentHandler.class);
    private static final String TAG_NAME = "name";

    private final BlockingQueue<PeliasDocument> peliasDocumentQueue;
    private final List<PointOfInterestFilter> pointOfInterestFilters;
    private final Map<Long, OSMNode> nodesMapForWays = new HashMap<>();

    private final Set<Long> nodeRefsForWays = new HashSet<>();
    private final Set<Long> nodeRefsForMultipolygonRelations = new HashSet<>();
    private final Set<Long> wayRefsForMultipolygonRelations = new HashSet<>();

    private final Map<Long, OSMRelation> multiPolygonRelationsMap = new HashMap<>();
    private final Map<Long, OSMWay> waysMapForMultipolygonRelations = new HashMap<>();
    private final Map<Long, OSMNode> nodesMapForMultipolygonRelations = new HashMap<>();

    private final PeliasDocumentMapper peliasDocumentMapper;

    private boolean gatherNodesUsedInWaysPhase = true;

    public ProtoBufferContentHandler(BlockingQueue<PeliasDocument> peliasDocumentQueue,
                                     List<PointOfInterestFilter> pointOfInterestFilters,
                                     long poiBoost,
                                     List<String> poiFilter) {
        this.peliasDocumentQueue = peliasDocumentQueue;
        this.pointOfInterestFilters = pointOfInterestFilters;
        this.peliasDocumentMapper = new PeliasDocumentMapper(poiBoost, poiFilter, pointOfInterestFilters);
    }

    public void doneSecondPhaseWays() {
        gatherNodesUsedInWaysPhase = false;
    }

    public void doneThirdPhaseNodes() {
        processMultipolygonRelations();
    }

    public void addNode(OSMNode osmNode) {
        if (matchesFilter(osmNode)) {
            peliasDocumentQueue.addAll(peliasDocumentMapper.map(osmNode, new GeoPoint(osmNode.getLat(), osmNode.getLon())));
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
                    List<PeliasDocument> peliasDocuments = peliasDocumentMapper.map(osmWay, centroid);
                    peliasDocumentQueue.addAll(peliasDocuments);
                } else {
                    logger.info("Ignoring osmWay with missing nodes: " + osmWay.getAssumedName());
                }
            }
        }
    }

    public void addRelation(OSMRelation osmRelation) {
        if (!multiPolygonRelationsMap.containsKey(osmRelation.getId())
                && osmRelation.isTag("type", "multipolygon")
                && matchesFilter(osmRelation)) {

            wayRefsForMultipolygonRelations.addAll(osmRelation.getMemberRefsOfType("way"));
            multiPolygonRelationsMap.put(osmRelation.getId(), osmRelation);
        }
    }

    private void processMultipolygonRelations() {
        var counter = 0;
        for (OSMRelation relation : multiPolygonRelationsMap.values()) {

            var innerWaysOfMultipolygonRelation = relation.getMemberRefsForRole("inner").stream()
                    .map(waysMapForMultipolygonRelations::get)
                    .filter(Objects::nonNull)
                    .toList();

            var outerWaysOfMultipolygonRelation = relation.getMemberRefsForRole("outer").stream()
                    .map(waysMapForMultipolygonRelations::get)
                    .filter(Objects::nonNull)
                    .toList();

            GeoPoint centroid = getCentroid(innerWaysOfMultipolygonRelation, outerWaysOfMultipolygonRelation);
            if (centroid != null) {
                peliasDocumentQueue.addAll(peliasDocumentMapper.map(relation, centroid));
                counter++;
            }
        }
        logger.info("Total {} multipolygon POIs added.", counter);
    }

    private GeoPoint getCentroid(List<OSMWay> innerWaysOfMultipolygonRelation,
                                 List<OSMWay> outerWaysOfMultipolygonRelation) {

        var outerPolygons =
                MappingUtil.makeMultiPolygonsForOSMWays(outerWaysOfMultipolygonRelation, nodesMapForMultipolygonRelations);

        if (!outerPolygons.isEmpty() && !MappingUtil.checkPolygonProximity(outerPolygons)) {

            var innerPolygons =
                    MappingUtil.makeMultiPolygonsForOSMWays(innerWaysOfMultipolygonRelation, nodesMapForMultipolygonRelations);

            try {
                var multiPolygon = new GeometryFactory().createMultiPolygon(
                        Stream.of(outerPolygons, innerPolygons)
                                .flatMap(Collection::stream)
                                .toArray(Polygon[]::new)
                );
                var interiorPoint = multiPolygon.getInteriorPoint();
                return new GeoPoint(interiorPoint.getY(), interiorPoint.getX());
            } catch (RuntimeException e) {
                logger.warn("Unable to find centroid" + e);
                return null;
            }
        }
        return null;
    }

    private boolean matchesFilter(OSMWithTags entity) {
        if (!entity.hasTag(TAG_NAME)) {
            return false;
        }

        return pointOfInterestFilters.stream()
                .filter(poiFilter -> entity.getTags().containsKey(poiFilter.key()))
                .anyMatch(poiFilter -> poiFilter.getTagWithName(entity.getTags().get(poiFilter.key())) != null);
    }

    private GeoPoint getCentroid(OSMWay osmWay) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (Long nodeRef : osmWay.getNodeRefs()) {
            OSMNode node = nodesMapForWays.get(nodeRef);
            if (node != null) {
                coordinates.add(new Coordinate(node.getLon(), node.getLat()));
            }
        }

        if (coordinates.size() != osmWay.getNodeRefs().size()) {
            return null;
        }
        return MappingUtil.toCentroid(coordinates);
    }
}
