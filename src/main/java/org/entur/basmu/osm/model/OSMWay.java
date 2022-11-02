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

import java.util.ArrayList;
import java.util.List;

public class OSMWay extends OSMWithTags {

    private final List<Long> nodeRefs = new ArrayList<>();

    OSMWay(OSMWay osmWay) {
        super(osmWay.getId());
        osmWay.getTags().forEach(super::addTag);
        osmWay.getNodeRefs().forEach(this::addNodeRef);
    }

    public OSMWay(long id) {
        super(id);
    }

    public List<Long> getNodeRefs() {
        return nodeRefs.stream().toList();
    }

    public void addNodeRef(Long nodeRef) {
        nodeRefs.add(nodeRef);
    }

    public Long getStart() {
        return nodeRefs.get(0);
    }

    public Long getEnd() {
        return nodeRefs.get(nodeRefs.size() - 1);
    }

    public List<Long> getNodeRefsTrimEnd() {
        return nodeRefs.stream().limit(nodeRefs.size() - 1).toList();
    }

    public List<Long> getNodeRefsTrimStart() {
        return nodeRefs.stream().skip(1).toList();
    }

    public String toString() {
        return "osm way " + getId();
    }

}
