package org.entur.basmu.osm.model;

import java.util.ArrayList;
import java.util.List;

public class OSMRelation extends OSMWithTags {

    private final List<OSMRelationMember> members = new ArrayList<>();

    public OSMRelation(long id) {
        super(id);
    }

    public void addMember(OSMRelationMember member) {
        members.add(member);
    }

    public List<Long> getMemberRefsOfType(String type) {
        return members.stream()
                .filter(member -> member.type().equals(type))
                .map(OSMRelationMember::ref)
                .toList();
    }

    public List<Long> getMemberRefsForRole(String role) {
        return members.stream()
                .filter(member -> member.role().equals(role))
                .map(OSMRelationMember::ref)
                .toList();
    }

    public String toString() {
        return "osm relation " + getId();
    }
}
