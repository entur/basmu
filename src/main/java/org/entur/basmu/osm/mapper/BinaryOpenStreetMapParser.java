package org.entur.basmu.osm.mapper;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat;
import org.entur.basmu.osm.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parser for the OpenStreetMap PBF Format.
 */
public class BinaryOpenStreetMapParser extends BinaryParser {
    private final ProtoBufferContentHandler handler;
    private final Map<String, String> stringTable = new HashMap<>();
    private boolean parseWays = true;
    private boolean parseRelations = true;
    private boolean parseNodes = true;

    public BinaryOpenStreetMapParser(ProtoBufferContentHandler handler) {
        this.handler = handler;
    }

    // The strings are already being pulled from a string table in the PBF file,
    // but there appears to be a separate string table per 8k-entry PBF file block.
    // String.intern grinds to a halt on large PBF files (as it did on GTFS import), so 
    // we implement our own. 
    public String internalize(String s) {
        String fromTable = stringTable.get(s);
        if (fromTable == null) {
            stringTable.put(s, s);
            return s;
        }
        return fromTable;
    }

    public void complete() {
        // Jump in circles
    }

    @Override
    protected void parseNodes(List<Osmformat.Node> nodes) {
        if (!parseNodes) {
            return;
        }

        for (Osmformat.Node node : nodes) {
            OSMNode newNode = new OSMNode(node.getId(), parseLat(node.getLat()), parseLon(node.getLon()));

            findTags(node.getKeysCount(), node::getKeys, node::getVals)
                    .forEach(newNode::addTag);

            handler.addNode(newNode);
        }
    }

    @Override
    protected void parseDense(Osmformat.DenseNodes nodes) {
        if (!parseNodes) {
            return;
        }

        long previousId = 0, previousLat = 0, previousLon = 0;
        int j = 0; // Index into the keysvals array.

        for (int nodeIndex = 0; nodeIndex < nodes.getIdCount(); nodeIndex++) {

            long lat = nodes.getLat(nodeIndex) + previousLat;
            previousLat = lat;
            long lon = nodes.getLon(nodeIndex) + previousLon;
            previousLon = lon;
            long id = nodes.getId(nodeIndex) + previousId;
            previousId = id;

            OSMNode osmNode = new OSMNode(id, parseLat(lat), parseLon(lon));

            // If empty, assume that nothing here has keys or vals.
            if (nodes.getKeysValsCount() > 0) {
                while (nodes.getKeysVals(j) != 0) {
                    String key = internalize(getStringById(nodes.getKeysVals(j++)));
                    String value = internalize(getStringById(nodes.getKeysVals(j++)));
                    osmNode.addTag(key, value);
                }
                j++; // Skip over the '0' delimiter.
            }

            handler.addNode(osmNode);
        }
    }

    @Override
    protected void parseWays(List<Osmformat.Way> ways) {
        if (!parseWays) {
            return;
        }

        for (Osmformat.Way way : ways) {
            OSMWay newWay = new OSMWay(way.getId());

            findTags(way.getKeysCount(), way::getKeys, way::getVals)
                    .forEach(newWay::addTag);

            long previousRef = 0;
            for (long ref : way.getRefsList()) {
                newWay.addNodeRef(ref + previousRef);
                previousRef = ref + previousRef;
            }

            handler.addWay(newWay);
        }
    }

    @Override
    protected void parseRelations(List<Osmformat.Relation> relations) {
        if (!parseRelations) {
            return;
        }

        for (Osmformat.Relation relation : relations) {
            OSMRelation newRelation = new OSMRelation(relation.getId());

            findTags(relation.getKeysCount(), relation::getKeys, relation::getVals)
                    .forEach(newRelation::addTag);

            long previousMemberId = 0;
            for (int memberIdIndex = 0; memberIdIndex < relation.getMemidsCount(); memberIdIndex++) {

                long memberId = previousMemberId + relation.getMemids(memberIdIndex);
                previousMemberId = memberId;

                var memberType = switch (relation.getTypes(memberIdIndex)) {
                    case NODE -> "node";
                    case WAY -> "way";
                    case RELATION -> "relation";
                };

                OSMRelationMember relMember = new OSMRelationMember(
                        memberType,
                        memberId,
                        internalize(getStringById(relation.getRolesSid(memberIdIndex))));

                newRelation.addMember(relMember);
            }
            handler.addRelation(newRelation);
        }
    }

    @Override
    public void parse(Osmformat.HeaderBlock block) {
        block.getRequiredFeaturesList().stream()
                .filter(s -> !s.equals("OsmSchema-V0.6"))
                .filter(s -> !s.equals("DenseNodes"))
                .forEach(s -> {
                    throw new IllegalStateException("File requires unknown feature: " + s);
                });
    }

    private Map<String, String> findTags(int keyCount,
                                         UnaryOperator<Integer> keyStringId,
                                         UnaryOperator<Integer> valueStringId) {

        return Stream.iterate(0, n -> n + 1)
                .limit(keyCount)
                .collect(Collectors.toMap(
                        n -> internalize(getStringById(keyStringId.apply(n))),
                        n -> internalize(getStringById(valueStringId.apply(n))))
                );
    }

    /**
     * Should relations be parsed
     */
    public void setParseRelations(boolean parseRelations) {
        this.parseRelations = parseRelations;
    }

    /**
     * Should ways be parsed
     */
    public void setParseWays(boolean parseWays) {
        this.parseWays = parseWays;
    }

    /**
     * Should nodes be parsed
     */
    public void setParseNodes(boolean parseNodes) {
        this.parseNodes = parseNodes;
    }
}
