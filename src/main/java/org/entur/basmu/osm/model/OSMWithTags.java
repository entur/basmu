/* 
 Copyright 2008 Brian Ferris
 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.entur.basmu.osm.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A base class for OSM entities containing common methods.
 */

public class OSMWithTags {

    private final long id;
    private final Map<String, String> tags = new HashMap<>();

    public OSMWithTags(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    public void addTag(String key, String value) {
        tags.put(key.toLowerCase(), value);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * Is the tag defined?
     */
    public boolean hasTag(String tag) {
        tag = tag.toLowerCase();
        return tags.containsKey(tag);
    }

    /**
     * Checks is a tag contains the specified value.
     */
    public Boolean isTag(String tag, String value) {
        tag = tag.toLowerCase();
        if (tags.containsKey(tag) && value != null)
            return value.equals(tags.get(tag));

        return false;
    }

    /**
     * Returns a name-like value for an entity (if one exists).
     */
    public String getAssumedName() {
        if (tags.containsKey("name"))
            return tags.get("name");

        if (tags.containsKey("ref"))
            return tags.get("ref");

        return null;
    }
}
