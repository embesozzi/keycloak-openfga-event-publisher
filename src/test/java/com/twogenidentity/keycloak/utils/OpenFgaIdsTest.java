package com.twogenidentity.keycloak.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The encoding of Keycloak names into OpenFGA id segments: RFC 3986 percent-encoding (UTF-8, uppercase hex) of
 * '%', '/', ':', '#' and the Unicode categories Cc, Zs, Zl, Zp; every other character is kept.
 *
 * ':', '#' and whitespace are rejected by OpenFGA (TupleKey validation in openfga/api); '%' keeps the encoding
 * injective; '/' separates the segments of role:<clientId>/<roleName>.
 */
class OpenFgaIdsTest {

    static Stream<Arguments> vectors() {
        return Stream.of(
                // unchanged: characters OpenFGA accepts and that are not used as separators
                Arguments.of("view-product", "view-product"),
                Arguments.of("view_product.v2", "view_product.v2"),
                Arguments.of("store-api", "store-api"),
                Arguments.of("rôle-ñ-é", "rôle-ñ-é"),
                Arguments.of("a@b|c,d=e*f", "a@b|c,d=e*f"),
                Arguments.of("3f2a9c1e-0b7a-4c6e-9d1a-5e0c2b77d001", "3f2a9c1e-0b7a-4c6e-9d1a-5e0c2b77d001"),
                // rejected by OpenFGA
                Arguments.of("Account Manager", "Account%20Manager"),
                Arguments.of("a:b", "a%3Ab"),
                Arguments.of("a#b", "a%23b"),
                Arguments.of("tab\there", "tab%09here"),
                Arguments.of("new\nline", "new%0Aline"),
                // separator of role:<clientId>/<roleName>
                Arguments.of("a/b", "a%2Fb"),
                Arguments.of("spiffe://example.org/ns/sa/agent", "spiffe%3A%2F%2Fexample.org%2Fns%2Fsa%2Fagent"),
                Arguments.of("https://app.example.com", "https%3A%2F%2Fapp.example.com"),
                // '%' itself, so the encoding can be reversed
                Arguments.of("100%", "100%25"),
                Arguments.of("a%3Ab", "a%253Ab"),
                // other control characters, and whitespace outside ASCII (non-breaking space), as UTF-8
                Arguments.of("bell\u0007", "bell%07"),
                Arguments.of("del\u007F", "del%7F"),
                Arguments.of("nb\u00A0sp", "nb%C2%A0sp")
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("vectors")
    @DisplayName("Encodes '%', '/', ':', '#' and Unicode Cc, Zs, Zl, Zp; keeps every other character")
    void encode(String name, String expected) {
        assertEquals(expected, OpenFgaIds.encode(name));
    }

    @Test
    @DisplayName("A raw name never equals the encoded form of another name ('%' is encoded)")
    void injectivePercent() {
        assertNotEquals(OpenFgaIds.encode("a:b"), OpenFgaIds.encode("a%3Ab"));
    }

    @Test
    @DisplayName("Segments joined with '/' stay unambiguous ('/' is encoded inside a segment)")
    void injectiveSegments() {
        // client "spiffe://x/agent" with role "read" vs client "spiffe://x" with role "agent/read"
        String first = OpenFgaIds.encode("spiffe://x/agent") + "/" + OpenFgaIds.encode("read");
        String second = OpenFgaIds.encode("spiffe://x") + "/" + OpenFgaIds.encode("agent/read");
        assertNotEquals(first, second);
    }
}
