package org.entur.basmu.osm.domain;

import java.util.Objects;

public record Tag(String name, Integer priority) {

    public Tag(String name, Integer priority) {
        this.name = Objects.requireNonNull(name);
        this.priority = Objects.requireNonNull(priority);
    }
}
