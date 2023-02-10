package org.entur.basmu.osm.domain;

import java.util.List;
import java.util.Objects;

public record PointOfInterestFilter(Long id, String key, List<Tag> tags) {

    public PointOfInterestFilter(Long id, String key, List<Tag> tags) {
        this.id = id;
        this.key = Objects.requireNonNull(key);
        this.tags = tags;
    }

    public Tag getTagWithName(String name) {
        return this.tags == null
                ? null
                : this.tags.stream()
                .filter(tag -> tag.name().equals(name))
                .findFirst().orElse(null);
    }
}
