package com.example.flurfunk.util;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that holds global constants and enumerations used throughout the Flurfunk app.
 * <p>
 * This includes category definitions for offers, offer statuses, file names, and helper methods
 * for working with display names and compact codes for transmission.
 */
public class Constants {

    /**
     * Enumeration of available offer categories, each with a localized display name and a compact 3-letter code.
     * Used to classify and filter offers in the app and to encode categories in LoRa protocol messages.
     */
    public enum Category {
        ELECTRONICS("Elektronik", "ELC"),
        FURNITURE("Möbel", "FUR"),
        HOUSEHOLD("Haushalt", "HOU"),
        TOOLS("Werkzeug", "TLS"),
        BOOKS("Bücher", "BOK"),
        CLOTHING("Kleidung", "CLT"),
        TOYS("Spielwaren", "TOY"),
        VEHICLES("Fahrzeuge", "VEH"),
        MISC("Sonstiges", "MSC"),
        MY_OFFERS("Meine Angebote", "MYO");

        private final String displayName;
        private final String code;

        Category(String displayName, String code) {
            this.displayName = displayName;
            this.code = code;
        }

        /**
         * @return the display name of the category as its string representation
         */
        @NonNull
        @Override
        public String toString() {
            return displayName;
        }

        /**
         * @return the localized display name of the category
         */
        public String getDisplayName() {
            return displayName;
        }

        /**
         * @return the compact 3-letter code for protocol use
         */
        public String getCode() {
            return code;
        }

        /**
         * Retrieves the Category enum matching the given compact code.
         *
         * @param code the 3-letter compact category code
         * @return the matching Category or MISC if no match is found
         */
        public static Category fromCode(String code) {
            for (Category category : values()) {
                if (category.code.equalsIgnoreCase(code)) return category;
            }
            return MISC;
        }
    }

    /**
     * Enumeration representing the status of an offer: active or inactive.
     */
    public enum OfferStatus {
        ACTIVE("aktiv", "AC"),
        INACTIVE("inaktiv", "IN");

        private final String displayName;
        private final String code;

        OfferStatus(String displayName, String code) {

            this.displayName = displayName;
            this.code = code;
        }

        /**
         * @return the display name of the status
         */
        @NonNull
        @Override
        public String toString() {
            return displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getCode() {
            return code;
        }

        public static OfferStatus fromCode(String code) {
            for (OfferStatus status : values()) {
                if (status.code.equalsIgnoreCase(code)) return status;
            }
            return INACTIVE;
        }
    }

    /**
     * The name of the JSON file where offer data is stored.
     */
    public static final String OFFER_FILE = "offers.json";

    /**
     * Returns a list of display names for all available categories.
     *
     * @return a list of strings representing category display names
     */
    public static List<String> getAllCategoryDisplayNames() {
        List<String> names = new ArrayList<>();
        for (Category category : Category.values()) {
            if(category != Category.MY_OFFERS){
                names.add(category.getDisplayName());
            }
        }
        return names;
    }

    /**
     * Returns the localized display name for a given category.
     *
     * @param category the {@link Category} enum value
     * @return the corresponding display name
     */
    public static String getDisplayNameForCategory(Category category) {
        return category.getDisplayName();
    }

    /**
     * Returns the {@link Category} enum that matches the given display name.
     * If no match is found, {@link Category#MISC} is returned as a fallback.
     *
     * @param displayName the display name to look up
     * @return the matching {@link Category}, or {@link Category#MISC} if not found
     */
    public static Category getCategoryFromDisplayName(String displayName) {
        for (Category category : Category.values()) {
            if (category.getDisplayName().equalsIgnoreCase(displayName)) {
                return category;
            }
        }
        return Category.MISC;
    }

    /**
     * Returns the {@link Category} enum that matches the given compact code.
     * If no match is found, {@link Category#MISC} is returned.
     *
     * @param code the short protocol code
     * @return the corresponding category or MISC
     */
    public static Category getCategoryFromCode(String code) {
        return Category.fromCode(code);
    }

    /**
     * Returns the {@link OfferStatus} enum that matches the given compact code.
     * If no match is found, {@link OfferStatus#INACTIVE} is returned.
     *
     * @param code the short protocol code
     * @return the corresponding status or INACTIVE
     */
    public static OfferStatus getOfferStatusFromCode(String code) {
        return OfferStatus.fromCode(code);
    }

}
