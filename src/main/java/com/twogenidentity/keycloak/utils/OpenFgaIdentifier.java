package com.twogenidentity.keycloak.utils;

/** What identifies roles, groups and clients in OpenFGA tuples. Users are always published by id. */
public enum OpenFgaIdentifier {
    /** Names, percent-encoded with {@link OpenFgaIds#encode(String)}: role:<roleName>, role:<clientId>/<roleName>, group:<groupName>, client:<clientId>. Default. */
    NAME,
    /** Keycloak ids: role:<roleId>, group:<groupId>, client:<client internal id>. */
    ID
}
