package com.example.flurfunk.util;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines the structure and utility methods for the custom Flurfunk communication protocol
 * used over LoRa.
 * <p>
 * A protocol message has the format:
 * <pre>
 *     #COMMAND|KEY1=VAL1;KEY2=VAL2;...;EOM
 * </pre>
 * where the command is a fixed-length identifier (e.g. {@code SYNOF}) and the payload is a
 * semicolon-separated list of key-value pairs. The message always ends with {@code ;EOM}.
 * <p>
 * The class provides static methods to build and parse messages, and defines command and key
 * constants for all supported protocol operations related to offer and peer synchronization.
 */
public class Protocol {

    // --- Command keywords ---

    /**
     * Offer synchronization: summary of known offers (IDs and timestamps).
     */
    public static final String SYNOF = "SYNOF";
    /**
     * Request for full data of specific offers.
     */
    public static final String REQOF = "REQOF";
    /**
     * Contains the full data of all requested local offers.
     */
    public static final String OFDAT = "OFDAT";

    /**
     * Peer synchronization: summary of known peer profiles.
     */
    public static final String SYNPR = "SYNPR";
    /**
     * Request for full data of specific peer profiles.
     */
    public static final String REQPR = "REQPR";
    /**
     * Contains the full data of one or more peer profiles.
     */
    public static final String PRDAT = "PRDAT";
    /**
     * Notification of a deleted user profile.
     */
    public static final String USRDEL = "USRDEL";


    // --- Common keys ---

    /**
     * Unique message ID.
     */
    public static final String KEY_ID = "ID";
    /**
     * Sender/user ID.
     */
    public static final String KEY_UID = "SID";
    /**
     * Offer ID
     */
    public static final String KEY_OID = "OID";
    /**
     * LoRa Mesh-network ID.
     */
    public static final String KEY_MID = "MID";
    /**
     * Timestamp of the item (offer or profile).
     */
    public static final String KEY_TS = "TS";
    /**
     * Initialization vector for encrypted content.
     */
    public static final String KEY_IV = "IV";

    // --- Offer-related keys ---

    /**
     * List of offer summaries or full offers.
     */
    public static final String KEY_OFF = "OFF";
    /**
     * List of requested offer IDs.
     */
    public static final String KEY_REQ = "REQ";
    /**
     * Array of full offer objects.
     */
    public static final String KEY_OFA = "OFA";
    /**
     * Offer title.
     */
    public static final String KEY_TTL = "TTL";
    /**
     * Offer description.
     */
    public static final String KEY_DESC = "DSC";
    /**
     * Offer category code.
     */
    public static final String KEY_CAT = "CAT";
    /**
     * Creator user ID.
     */
    public static final String KEY_CID = "CID";
    /**
     * Offer status code.
     */
    public static final String KEY_STAT = "STA";

    // --- Peer-related keys ---

    /**
     * Peer summaries (ID + timestamp).
     */
    public static final String KEY_PRS = "PRS";
    /**
     * Peer full data.
     */
    public static final String KEY_PFD = "PFD";
    /**
     * Peer name.
     */
    public static final String KEY_NAME = "NAM";
    /**
     * Floor number.
     */
    public static final String KEY_FLR = "FLR";
    /**
     * Phone number.
     */
    public static final String KEY_PHN = "PHN";
    /**
     * Email address.
     */
    public static final String KEY_MAIL = "EML";
    /**
     * Last seen timestamp.
     */
    public static final String KEY_LS = "LS";

    /**
     * Builds a protocol message string from a command and a set of key-value pairs.
     *
     * @param command   the protocol command identifier
     * @param keyValues the key-value data map
     * @return a valid protocol message string
     */
    public static String build(String command, Map<String, String> keyValues) {
        StringBuilder sb = new StringBuilder("#").append(command).append("|");
        boolean first = true;
        for (Map.Entry<String, String> entry : keyValues.entrySet()) {
            if (!first) sb.append(";");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append(";EOM");
        return sb.toString();
    }

    /**
     * Parses a protocol message string into a {@link ParsedMessage} object.
     *
     * @param message the raw protocol string (e.g. "#COMMAND#KEY=VAL;...")
     * @return the parsed message object
     * @throws IllegalArgumentException if the message format is invalid
     */
    public static ParsedMessage parse(String message) {
        if (!message.startsWith("#") || !message.contains("|")) {
            throw new IllegalArgumentException("Invalid protocol message format: " + message);
        }

        int pipeIndex = message.indexOf('|');
        String command = message.substring(1, pipeIndex);
        String content = message.substring(pipeIndex + 1);

        if (content.endsWith(";EOM")) {
            content = content.substring(0, content.length() - 4);
        }

        Map<String, String> keyValues = new HashMap<>();

        int i = 0;
        while (i < content.length()) {
            int eq = content.indexOf('=', i);
            if (eq == -1) break;

            String key = content.substring(i, eq);
            i = eq + 1;

            StringBuilder valueBuilder = new StringBuilder();
            int braceLevel = 0;
            int bracketLevel = 0;

            while (i < content.length()) {
                char c = content.charAt(i);

                if (c == '{') braceLevel++;
                if (c == '}') braceLevel--;
                if (c == '[') bracketLevel++;
                if (c == ']') bracketLevel--;

                if (c == ';' && braceLevel == 0 && bracketLevel == 0) {
                    i++;
                    break;
                }

                valueBuilder.append(c);
                i++;
            }

            keyValues.put(key, valueBuilder.toString());
        }
        return new ParsedMessage(command, keyValues);
    }

    /**
     * Represents a parsed protocol message, consisting of a command and a map of key-value pairs.
     * Used as the result of the {@link Protocol#parse(String)} method.
     */
    public static class ParsedMessage {
        /**
         * The command identifier (e.g. "SYNOF", "REQPR")
         */
        public final String command;
        /**
         * The parsed key-value pairs of the message body
         */
        public final Map<String, String> keyValues;

        /**
         * Constructs a new {@code ParsedMessage}.
         *
         * @param command   the parsed command
         * @param keyValues the parsed key-value pairs
         */
        public ParsedMessage(String command, Map<String, String> keyValues) {
            this.command = command;
            this.keyValues = keyValues;
        }

        /**
         * Retrieves the value associated with a given key in the message.
         *
         * @param key the key to look up
         * @return the value for that key, or {@code null} if not present
         */
        public String getValue(String key) {
            return keyValues.get(key);
        }

        /**
         * Returns a human-readable string representation of the parsed message.
         *
         * @return a string representing the message
         */
        @NonNull
        public String toString() {
            return "#" + command + "#" + keyValues.toString();
        }
    }
}
