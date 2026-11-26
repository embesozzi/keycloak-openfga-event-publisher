package com.twogenidentity.keycloak.utils;

import java.nio.charset.StandardCharsets;

/**
 * Makes Keycloak names safe to use as segments of OpenFGA object and user ids.
 *
 * Percent-encodes, as UTF-8: '%', '/', ':', '#', and the characters of the Unicode categories Cc (control),
 * Zs, Zl, Zp (space, line and paragraph separators). Every other character is kept, so most names are published
 * unchanged.
 * <ul>
 *   <li>':', '#' and whitespace: OpenFGA rejects them in ids (TupleKey validation in openfga/api).</li>
 *   <li>'%': keeps the encoding injective, so the raw name "a%3Ab" never equals the encoded form of "a:b".</li>
 *   <li>'/': separates the segments of role:&lt;clientId&gt;/&lt;roleName&gt;, so a '/' inside a name must not
 *       look like the separator (client "a/b" + role "c" vs client "a" + role "b/c").</li>
 *   <li>control characters and other separators: never meaningful in a name, and invisible in logs.</li>
 * </ul>
 * The mechanism is the percent-encoding of RFC 3986 (STD 66): data that would conflict with a delimiter is
 * percent-encoded (section 2.2), '%' itself as "%25" (2.4), text as UTF-8 octets (2.5), uppercase hex digits (2.1).
 * The characters to encode are those of OpenFGA's id format and of role:&lt;clientId&gt;/&lt;roleName&gt;.
 */
public final class OpenFgaIds {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private OpenFgaIds() {
    }

    public static String encode(String name) {
        StringBuilder sb = null;
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            int length = Character.charCount(codePoint);
            if (mustEncode(codePoint)) {
                if (sb == null) {
                    sb = new StringBuilder(name.length() + 8).append(name, 0, i);
                }
                for (byte b : name.substring(i, i + length).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            } else if (sb != null) {
                sb.appendCodePoint(codePoint);
            }
            i += length;
        }
        return sb == null ? name : sb.toString();
    }

    private static boolean mustEncode(int codePoint) {
        return codePoint == '%' || codePoint == '/' || codePoint == ':' || codePoint == '#'
                || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint);
    }
}
