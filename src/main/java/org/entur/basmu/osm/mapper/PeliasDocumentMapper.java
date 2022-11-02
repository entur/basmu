package org.entur.basmu.osm.mapper;

import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.model.OSMWithTags;
import org.entur.geocoder.model.GeoPoint;
import org.entur.geocoder.model.PeliasDocument;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class PeliasDocumentMapper {

    private static final String OSM_TAG_NAME = "name";
    private static final String DEFAULT_SOURCE = "osm";
    public static final String DEFAULT_LAYER = "pointOfInterest";

    private final long popularity;
    private final List<String> typeFilter;
    private final List<OSMPOIFilter> osmPoiFilters;

    public PeliasDocumentMapper(long popularity, List<String> typeFilter, List<OSMPOIFilter> osmPoiFilters) {
        this.popularity = popularity;
        this.typeFilter = typeFilter;
        this.osmPoiFilters = osmPoiFilters;
    }

    /**
     * TODO: Kan dette gjøres nå ???
     * Map single place hierarchy to (potentially) multiple pelias documents, one per alias/alternative name.
     * Pelias does not yet support queries in multiple languages / for aliases.
     * When support for this is ready this mapping should be refactored to produce
     * a single document per place hierarchy.
     */
    public List<PeliasDocument> map(OSMWithTags entity, GeoPoint centroid) {
        if (!isFilterMatch(entity)) {
            return Collections.emptyList();
        }
        var count = new AtomicInteger();
        return getNames(entity).stream()
                .map(entityName -> {
                    String entityId = makeID(count.getAndAdd(1), entity.getId());
                    return createPeliasDocument(entityId, entityName, centroid, entity);
                })
                .toList();
    }

    private String makeID(int nameIndex, long entityId) {
        String idSuffix = nameIndex > 0 ? "-" + nameIndex : "";
        return DEFAULT_SOURCE + ":PlaceOfInterest:" + entityId + idSuffix;
    }

    private PeliasDocument createPeliasDocument(String entityId,
                                                LanguageString name,
                                                GeoPoint centroid,
                                                OSMWithTags entity) {

        PeliasDocument document = new PeliasDocument(DEFAULT_LAYER, DEFAULT_SOURCE, entityId);

        document.setDefaultName(name.value());
        document.setCenterPoint(centroid);
        setDisplayName(document, entity);
        addAlternativeNames(entity, document);
        Map<String, String> osmTags = getOSMTags(entity);
        document.setPopularity(popularity * getPopularityBoost(osmTags));
        addPOICategories(document, osmTags);

        return document;
    }

    /**
     * Add official name as display name.
     * Not a part of standard pelias model, will be copied to name.default before deduping
     * and labelling in Entur-pelias API.
     */
    private static void setDisplayName(PeliasDocument document, OSMWithTags entity) {
        LanguageString displayName = getDisplayName(entity);
        document.setDisplayName(displayName.value());
        document.addAlternativeName(displayName.language(), displayName.value());
    }

    private static void addAlternativeNames(OSMWithTags entity, PeliasDocument document) {
        List<LanguageString> alternativeDescriptors = mapAlternativeDescriptors(entity);
        alternativeDescriptors.stream()
                .filter(alternativeDescriptor -> alternativeDescriptor.language() != null)
                .forEach(alternativeDescriptor -> document.addAlternativeName(alternativeDescriptor.language(), alternativeDescriptor.value()));
    }

    private void addPOICategories(PeliasDocument document, Map<String, String> osmTags) {
        document.addCategory("poi");
        osmTags.entrySet().stream()
                .filter(tagEntry -> osmPoiFilters.stream()
                        .anyMatch(poiFilter -> tagEntry.getKey().equals(poiFilter.key())
                                && tagEntry.getValue().equals(poiFilter.value())
                        ))
                .map(Map.Entry::getValue)
                .forEach(document::addCategory);
    }

    private int getPopularityBoost(Map<String, String> osmTags) {
        return osmPoiFilters.stream()
                .filter(poiFilter -> osmTags.entrySet().stream()
                        .anyMatch(key -> key.getKey().equals(poiFilter.key())
                                && key.getValue().equals(poiFilter.value())))
                .max(OSMPOIFilter::sort)
                .map(OSMPOIFilter::priority)
                .orElse(1);
    }

    private static Set<LanguageString> getNames(OSMWithTags entity) {
        List<LanguageString> alternativeDescriptors = mapAlternativeDescriptors(entity);

        Set<LanguageString> names = alternativeDescriptors.stream()
                .filter(alternativeDescriptor -> alternativeDescriptor.language() != null)
                .collect(Collectors.toSet());

        names.add(getDisplayName(entity));

        return names;
    }

    private static List<LanguageString> mapAlternativeDescriptors(OSMWithTags entity) {

        return entity.getTags().entrySet().stream()
                .filter(e -> !e.getKey().equals("name")
                        && e.getKey().startsWith("name:")
                        && e.getValue() != null)
                .map(e -> new LanguageString(
                        e.getKey().replaceFirst("name:", ""),
                        e.getValue())
                ).toList();
    }

    private static LanguageString getDisplayName(OSMWithTags entity) {
        return new LanguageString("no", entity.getAssumedName());
    }

    private static Map<String, String> getOSMTags(OSMWithTags entity) {
        return entity.getTags().entrySet().stream()
                .filter(e -> !OSM_TAG_NAME.equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean isFilterMatch(OSMWithTags entity) {
        if (CollectionUtils.isEmpty(typeFilter)) {
            return true;
        }

        Map<String, String> osmTags = getOSMTags(entity);

        if (osmTags.isEmpty()) {
            return false;
        }

        return typeFilter.stream()
                .anyMatch(filter ->
                        osmTags.entrySet().stream()
                                .map(key -> key.getKey() + "=" + key.getValue())
                                .anyMatch(filter::startsWith)
                );
    }
}
