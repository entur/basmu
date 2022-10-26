package org.entur.basmu.osm.mapper;

import net.opengis.gml._3.PolygonType;
import org.entur.basmu.osm.model.OSMWithTags;
import org.entur.basmu.osm.util.NetexGeoUtil;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.rutebanken.netex.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public class TopographicPlaceMapper {

    private static final String TAG_NAME = "name";
    private static final String PARTICIPANT_REF = "OSM";

    public static TopographicPlace map(OSMWithTags entity, IanaCountryTldEnumeration countryRef) {

        return new TopographicPlace()
                .withVersion("any")
                .withModification(ModificationEnumeration.NEW)
                .withName(multilingualString(entity.getAssumedName()))
                .withAlternativeDescriptors(mapAlternativeDescriptors(entity))
                .withDescriptor(new TopographicPlaceDescriptor_VersionedChildStructure().withName(multilingualString(entity.getAssumedName())))
                .withTopographicPlaceType(TopographicPlaceTypeEnumeration.PLACE_OF_INTEREST)
                .withCountryRef(new CountryRef().withRef(countryRef))
                .withId(prefix(entity.getId()))
                .withKeyList(new KeyListStructure().withKeyValue(mapKeyValues(entity)));
    }

    private static TopographicPlace_VersionStructure.AlternativeDescriptors mapAlternativeDescriptors(OSMWithTags entity) {

        List<TopographicPlaceDescriptor_VersionedChildStructure> descriptors = entity.getTags().entrySet().stream().filter(e -> !e.getKey().equals("name") && e.getKey().startsWith("name:") && e.getValue() != null)
                .map(e -> new TopographicPlaceDescriptor_VersionedChildStructure()
                        .withName(new MultilingualString().withValue(e.getValue()).withLang(e.getKey().replaceFirst("name:", "")))).collect(Collectors.toList());

        if (descriptors.isEmpty()) {
            return null;
        }

        return new TopographicPlace_VersionStructure.AlternativeDescriptors().withTopographicPlaceDescriptor(descriptors);
    }

    private static List<KeyValueStructure> mapKeyValues(OSMWithTags entity) {
        return entity.getTags().entrySet().stream()
                .filter(e -> !TAG_NAME.equals(e.getKey()))
                .map(e -> new KeyValueStructure().withKey(e.getKey()).withValue(e.getValue()))
                .collect(Collectors.toList());
    }

    private static String prefix(long id) {
        return PARTICIPANT_REF + ":PlaceOfInterest:" + id;
    }

    private static MultilingualString multilingualString(String val) {
        return new MultilingualString().withLang("no").withValue(val);
    }

    /**
     * Calculate centroid of list of coordinates.
     * <p>
     * If coordinates may be converted to a polygon, the polygons centroid is used. If not, the centroid of the corresponding multipoint is used.
     */
    public static SimplePoint_VersionStructure toCentroid(List<Coordinate> coordinates) {
        Point centroid;
        try {
            centroid = new GeometryFactory().createPolygon(coordinates.toArray(new Coordinate[0])).getCentroid();
        } catch (RuntimeException re) {
            centroid = new GeometryFactory().createMultiPointFromCoords(coordinates.toArray(new Coordinate[0])).getCentroid();
        }
        return toCentroid(centroid.getY(), centroid.getX());
    }

    public static SimplePoint_VersionStructure toCentroid(double latitude, double longitude) {
        return new SimplePoint_VersionStructure().withLocation(
                new LocationStructure().withLatitude(BigDecimal.valueOf(latitude))
                        .withLongitude(BigDecimal.valueOf(longitude)));
    }

    public static PolygonType toPolygon(List<Coordinate> coordinates, long id) {
        Polygon polygon = new GeometryFactory().createPolygon(coordinates.toArray(new Coordinate[0]));
        if (!polygon.isValid()) {
            return null;
        }
        return NetexGeoUtil.toNetexPolygon(polygon).withId(PARTICIPANT_REF + "-" + id);
    }
}
