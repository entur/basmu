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

package org.entur.basmu.osm.mapper;

import org.apache.commons.lang3.StringUtils;
import org.entur.basmu.osm.domain.OSMPOIFilter;
import org.entur.basmu.osm.util.NetexPeliasMapperUtil;
import org.entur.geocoder.model.AddressParts;
import org.entur.geocoder.model.GeoPoint;
import org.entur.geocoder.model.PeliasDocument;
import org.rutebanken.netex.model.KeyValueStructure;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.MultilingualString;
import org.rutebanken.netex.model.TopographicPlace;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rutebanken.netex.model.TopographicPlaceTypeEnumeration.PLACE_OF_INTEREST;

public class TopographicPlaceToPeliasMapper {

    private static final String DEFAULT_LANGUAGE = "nor";
    private static final String DEFAULT_SOURCE = "osm";

    private final long popularity;
    private final List<String> typeFilter;
    private final List<OSMPOIFilter> osmpoiFilters;

    public TopographicPlaceToPeliasMapper(long popularity, List<String> typeFilter, List<OSMPOIFilter> osmpoiFilters) {
        super();
        this.popularity = popularity;
        this.typeFilter = typeFilter;
        this.osmpoiFilters = osmpoiFilters;
    }

    /**
     * TODO: Kan dette gjøres nå ???
     * Map single place hierarchy to (potentially) multiple pelias documents, one per alias/alternative name.
     * Pelias does not yet support queries in multiple languages / for aliases.
     * When support for this is ready this mapping should be refactored to produce
     * a single document per place hierarchy.
     */
    public List<PeliasDocument> toPeliasDocumentsForNames(TopographicPlace place) {
        if (!isValid(place)) {
            return Collections.emptyList();
        }
        var cnt = new AtomicInteger();

        return getNames(place).stream()
                .map(name -> createPeliasDocument(place, name, cnt.getAndAdd(1))).toList();
    }

    private PeliasDocument createPeliasDocument(TopographicPlace place, MultilingualString name, int idx) {

        String idSuffix = idx > 0 ? "-" + idx : "";

        PeliasDocument document = new PeliasDocument(getLayer(place), DEFAULT_SOURCE, place.getId() + idSuffix);

        if (name != null) {
            document.setDefaultName(name.getValue());
        }

        // Add official name as display name. Not a part of standard pelias model, will be copied to name.default before deduping and labelling in Entur-pelias API.
        MultilingualString displayName = getDisplayName(place);
        if (displayName != null) {
            document.setDisplayName(displayName.getValue());
            if (displayName.getLang() != null) {
                document.addAlternativeName(displayName.getLang(), displayName.getValue());
            }
        }

        // StopPlaces
        if (place.getCentroid() != null) {
            LocationStructure loc = place.getCentroid().getLocation();
            document.setCenterPoint(new GeoPoint(loc.getLatitude().doubleValue(), loc.getLongitude().doubleValue()));
        }

        addIdToStreetNameToAvoidFalseDuplicates(document, place.getId());

        if (place.getDescription() != null && !StringUtils.isEmpty(place.getDescription().getValue())) {
            String lang = place.getDescription().getLang();
            if (lang == null) {
                lang = DEFAULT_LANGUAGE;
            }
            document.addDescription(lang, place.getDescription().getValue());
        }

        if (place.getAlternativeDescriptors() != null
                && !CollectionUtils.isEmpty(place.getAlternativeDescriptors().getTopographicPlaceDescriptor())) {
            place.getAlternativeDescriptors().getTopographicPlaceDescriptor().stream()
                    .filter(an -> an.getName() != null && an.getName().getLang() != null)
                    .forEach(n -> document.addAlternativeName(n.getName().getLang(), n.getName().getValue()));
        }

        if (PLACE_OF_INTEREST.equals(place.getTopographicPlaceType())) {
            document.setPopularity(popularity * getPopularityBoost(place));
            setPOICategories(document, place.getKeyList().getKeyValue());
        } else {
            document.setPopularity(popularity);
        }

        return document;
    }

    private void setPOICategories(PeliasDocument document, List<KeyValueStructure> keyValue) {
        document.addCategory("poi");
        for (KeyValueStructure keyValueStructure : keyValue) {
            var key = keyValueStructure.getKey();
            var value = keyValueStructure.getValue();
            var category = osmpoiFilters.stream()
                    .filter(f -> key.equals(f.key()) && value.equals(f.value()))
                    .map(OSMPOIFilter::value)
                    .findFirst();
            category.ifPresent(document::addCategory);
        }
    }

    private int getPopularityBoost(TopographicPlace place) {
        return osmpoiFilters.stream().filter(f ->
                place.getKeyList().getKeyValue().stream()
                        .anyMatch(key -> key.getKey().equals(f.key()) && key.getValue().equals(f.value()))
        ).max(OSMPOIFilter::sort).map(OSMPOIFilter::priority).orElse(1);
    }

    protected MultilingualString getDisplayName(TopographicPlace place) {
        if (place.getName() != null) {
            return place.getName();
        }    // Use descriptor.name if name is not set
        else if (place.getDescriptor() != null && place.getDescriptor().getName() != null) {
            return place.getDescriptor().getName();
        }
        return null;
    }

    protected List<MultilingualString> getNames(TopographicPlace place) {
        List<MultilingualString> names = new ArrayList<>();

        MultilingualString displayName = getDisplayName(place);
        if (displayName != null) {
            names.add(displayName);
        }

        if (place.getAlternativeDescriptors() != null
                && !CollectionUtils.isEmpty(place.getAlternativeDescriptors().getTopographicPlaceDescriptor())) {
            place.getAlternativeDescriptors().getTopographicPlaceDescriptor().stream()
                    .filter(an -> an.getName() != null && an.getName().getLang() != null)
                    .forEach(n -> names.add(n.getName()));
        }
        return NetexPeliasMapperUtil.filterUnique(names);
    }

    protected boolean isValid(TopographicPlace place) {
        String layer = getLayer(place);
        return layer != null && isFilterMatch(place) && NetexPeliasMapperUtil.isValid(place);
    }

    private boolean isFilterMatch(TopographicPlace place) {
        if (CollectionUtils.isEmpty(typeFilter)) {
            return true;
        }
        if (place.getKeyList() == null || place.getKeyList().getKeyValue() == null) {
            return false;
        }

        return typeFilter.stream()
                .anyMatch(filter -> place.getKeyList().getKeyValue().stream()
                        .map(key -> key.getKey() + "=" + key.getValue())
                        .anyMatch(filter::startsWith));
    }

    /**
     * The Pelias APIs de-duper will throw away results with identical name, layer, parent and address.
     * Setting unique ID in street part of address to avoid unique topographic places with identical
     * names being de-duped.
     * TODO: DO we need this ???
     */
    private static void addIdToStreetNameToAvoidFalseDuplicates(PeliasDocument document, String placeId) {
        document.setAddressParts(new AddressParts("NOT_AN_ADDRESS-" + placeId));
    }

    protected String getLayer(TopographicPlace place) {
        return switch (place.getTopographicPlaceType()) {
            case PLACE_OF_INTEREST -> "address";
            case MUNICIPALITY -> "locality";
            case COUNTY -> "county";
            case COUNTRY -> "country";
            case AREA -> "borough";
            default -> null;
        };
    }
}
