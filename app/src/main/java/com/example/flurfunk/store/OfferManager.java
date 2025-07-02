package com.example.flurfunk.store;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.util.Constants;
import com.example.flurfunk.util.Constants.OfferStatus;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.lang.reflect.Type;

/**
 * A utility class for managing offer data within the Flurfunk application.
 * Provides methods for saving, loading, filtering, and updating {@link Offer} objects.
 * <p>
 * Offers are stored in JSON format in the app's internal storage.
 */
public class OfferManager {

    private static final String FILE_NAME = Constants.OFFER_FILE;
    private static final String TAG = "OfferManager";

    /**
     * Saves a list of offers to the internal storage as a JSON file.
     *
     * @param context the Android context used for file operations
     * @param offers  the list of {@link Offer} objects to save
     */
    public static void saveOffers(Context context, List<Offer> offers) {
        try {
            String json = new Gson().toJson(offers);
            FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
            fos.write(json.getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Could not save offers.");
            ;
        }
    }

    /**
     * Loads the list of offers from internal storage.
     *
     * @param context the Android context used for file access
     * @return a list of {@link Offer} objects, or an empty list if loading fails
     */
    public static List<Offer> loadOffers(Context context) {
        List<Offer> offers = new ArrayList<>();
        try {
            FileInputStream fis = context.openFileInput(FILE_NAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            fis.close();
            String json = jsonBuilder.toString();
            Type listType = new TypeToken<List<Offer>>() {
            }.getType();
            offers = new Gson().fromJson(json, listType);
        } catch (Exception e) {
            Log.e(TAG, "Could not load offers.");
        }
        return offers;
    }

    /**
     * Filters a list of offers by their status.
     *
     * @param offers the list of offers to filter
     * @param status the desired {@link OfferStatus}
     * @return a list of offers with the given status
     */
    public static List<Offer> filterByStatus(List<Offer> offers, OfferStatus status) {
        return offers.stream().filter(offer -> offer.getStatus() == status).collect(Collectors.toList());
    }

    /**
     * Filters a list of offers by their creator ID.
     *
     * @param offers    the list of offers to filter
     * @param creatorId the creator ID to match
     * @return a list of offers created by the specified user
     */
    public static List<Offer> filterByCreator(List<Offer> offers, String creatorId) {
        return offers.stream().filter(offer -> offer.getCreatorId().equals(creatorId)).collect(Collectors.toList());
    }

    /**
     * Filters a list of offers by their category.
     *
     * @param offers   the list of {@link Offer} objects to filter
     * @param category the {@link Constants.Category} to match against each offer's category
     * @return a new list containing only the offers that match the specified category
     */
    public static List<Offer> filterByCategory(List<Offer> offers, Constants.Category category) {
        return offers.stream()
                .filter(offer -> offer.getCategory() == category)
                .collect(Collectors.toList());
    }


    /**
     * Retrieves a single offer by its unique ID.
     *
     * @param offers the list of offers to search
     * @param id     the offer ID to look for
     * @return the matching {@link Offer} or {@code null} if not found
     */
    public static Offer getOfferById(List<Offer> offers, String id) {
        return offers.stream().filter(offer -> offer.getOfferId().equals(id)).findFirst().orElse(null);
    }

    /**
     * Updates an existing offer in the list or adds it if not present.
     * If an offer with the same ID exists, the newer data is merged using {@link Offer#merge(Offer)}.
     *
     * @param offers   the list of existing offers
     * @param newOffer the new or updated offer
     */
    public static void updateOrAdd(List<Offer> offers, Offer newOffer) {
        for (int i = 0; i < offers.size(); i++) {
            Offer existing = offers.get(i);
            if (existing.getOfferId().equals(newOffer.getOfferId())) {
                existing.merge(newOffer);
                return;
            }
        }
        offers.add(newOffer);
    }

    public static void deactivateOffersOfInactiveUsers(Context context) {
        List<UserProfile> peers = PeerManager.loadPeers(context);
        List<Offer> offers = loadOffers(context);

        boolean changed = false;
        for (Offer offer : offers) {
            for (UserProfile peer : peers) {
                if (offer.getCreatorId().equals(peer.getId()) && !PeerManager.isPeerActive(peer) && offer.getStatus() == OfferStatus.ACTIVE) {
                    offer.setStatus(OfferStatus.INACTIVE);
                    offer.setLastModified(System.currentTimeMillis());
                    changed = true;
                }
            }
        }
        if (changed) saveOffers(context, offers);
    }
}
