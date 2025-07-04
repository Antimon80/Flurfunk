package com.example.flurfunk.network;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Constants;
import com.example.flurfunk.util.Protocol;
import com.example.flurfunk.util.Protocol.ParsedMessage;
import com.example.flurfunk.util.SecureCrypto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Responsible for synchronizing offer data between nearby devices using LoRa communication.
 * <p>
 * The {@code OfferSyncManager} handles:
 * <ul>
 *     <li>Broadcasting local offer summaries to peers ({@code SYNOF})</li>
 *     <li>Requesting missing or outdated offers from peers ({@code REQOF})</li>
 *     <li>Sending full offer data in response ({@code OFDAT})</li>
 *     <li>Importing incoming offer data and updating local storage</li>
 *     <li>Automatic inactivation of outdated active offers</li>
 * </ul>
 */

public class OfferSyncManager {
    private final Context context;
    private final UserProfile userProfile;
    private final LoRaManager loRaManager;
    private static final int MAX_LORA_BYTES = 960;
    private static final long MAX_ACTIVE_AGE_MS = 21L * 24 * 60 * 60 * 1000;
    private static final String TAG = "OfferSyncManager";

    /**
     * Constructs a new {@code OfferSyncManager} for the given user and context.
     *
     * @param context     the Android application context
     * @param userProfile the local user profile
     * @param loRaManager the manager responsible for LoRa communication
     */
    public OfferSyncManager(Context context, UserProfile userProfile, LoRaManager loRaManager) {
        this.context = context;
        this.userProfile = userProfile;
        this.loRaManager = loRaManager;
    }

    /**
     * Sends a synchronization message to all peers with the same mesh ID.
     * <p>
     * The message contains a summary of local offers (offer ID and timestamp).
     * Offers are split into multiple packets if needed to fit within 960 bytes.
     */
    public void sendOfferSync() {
        deactivateOutdatedOffers();

        JSONArray offerList = buildOfferList();

        JSONArray chunk = new JSONArray();
        try {
            for (int i = 0; i < offerList.length(); i++) {
                chunk.put(offerList.get(i));

                Map<String, String> payload = new HashMap<>();
                payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
                payload.put(Protocol.KEY_UID, userProfile.getId());
                payload.put(Protocol.KEY_MID, userProfile.getMeshId());
                payload.put(Protocol.KEY_OFF, chunk.toString());

                String message = Protocol.build(Protocol.SYNOF, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_OFF, chunk.toString());
                    Log.d(TAG, "SYNOF lagrger than 960 B - " + Protocol.build(Protocol.SYNOF, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.SYNOF, payload));

                    chunk = new JSONArray().put(offerList.get(i));
                }

                if (i == offerList.length() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_OFF, chunk.toString());
                    Log.d(TAG, "SYNOF smaller than 960 B - " + Protocol.build(Protocol.SYNOF, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.SYNOF, payload));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build SYNOF message");
        }
    }

    /**
     * Builds a list of offer summaries for synchronization.
     * <p>
     * Only active offers from active peers are included.
     *
     * @return a {@link JSONArray} of offer metadata (ID and timestamp)
     */
    public JSONArray buildOfferList() {
        JSONArray offerSummaries = new JSONArray();
        List<Offer> localOffers = OfferManager.loadOffers(context);
        for (Offer offer : localOffers) {
            UserProfile peer = PeerManager.getPeerById(context, offer.getCreatorId());
            if (peer != null && !PeerManager.isPeerActive(peer)) {
                continue;
            }

            try {
                JSONObject summary = new JSONObject();
                summary.put(Protocol.KEY_OID, offer.getOfferId());
                summary.put(Protocol.KEY_TS, offer.getLastModified());
                offerSummaries.put(summary);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to build offer list");
            }
        }
        return offerSummaries;
    }

    /**
     * Handles a received {@code SYNOF} synchronization message.
     * <p>
     * Compares the incoming list of offer summaries to local offers
     * and sends a request for any missing or outdated ones.
     *
     * @param msg the parsed protocol message
     */
    public void handleSyncMessage(ParsedMessage msg) {
        if (!userProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) {
            Log.w(TAG, "Rejected sync message due to different mesh-ID: " + userProfile.getMeshId() + "(local)" + msg.getValue(Protocol.KEY_MID) + "(incoming)");
        }

        List<Offer> localOffers = OfferManager.loadOffers(context);
        Map<String, Offer> localMap = new HashMap<>();
        for (Offer offer : localOffers) {
            localMap.put(offer.getOfferId(), offer);
        }

        List<String> missing = new ArrayList<>();
        try {
            String payload = msg.getValue(Protocol.KEY_OFF);
            if (payload == null || payload.trim().isEmpty()) {
                Log.w(TAG, "Received sync message with null or empty OFF payload");
                return;
            }

            JSONArray remote = new JSONArray(msg.getValue(Protocol.KEY_OFF));
            for (int i = 0; i < remote.length(); i++) {
                JSONObject r = remote.getJSONObject(i);
                String offerId = r.getString(Protocol.KEY_OID);
                long ts = r.getLong(Protocol.KEY_TS);
                if (!localMap.containsKey(offerId) || localMap.get(offerId).getLastModified() < ts) {
                    missing.add(offerId);
                }
            }

            if (!missing.isEmpty()) {
                sendOfferRequest(missing);
                Log.i(TAG, "Sending offer request");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error while processing incoming sync", e);
        }
    }

    /**
     * Sends a {@code REQOF} request message for the specified offer IDs.
     * <p>
     * The request is split into multiple broadcast messages if the
     * encoded payload exceeds the LoRa size limit (960 bytes).
     *
     * @param offerIds the list of offer IDs to request
     */
    public void sendOfferRequest(List<String> offerIds) {
        JSONArray chunk = new JSONArray();

        try {
            for (int i = 0; i < offerIds.size(); i++) {
                chunk.put(offerIds.get(i));

                Map<String, String> payload = new HashMap<>();
                payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
                payload.put(Protocol.KEY_UID, userProfile.getId());
                payload.put(Protocol.KEY_MID, userProfile.getMeshId());
                payload.put(Protocol.KEY_REQ, chunk.toString());

                String message = Protocol.build(Protocol.REQOF, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    Log.d(TAG, "REQOF larger than 960 B - " + Protocol.build(Protocol.REQOF, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.REQOF, payload));

                    chunk = new JSONArray().put(offerIds.get(i));
                }

                if (i == offerIds.size() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    Log.d(TAG, "REQOF smaller than 960 B - " + Protocol.build(Protocol.REQOF, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.REQOF, payload));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build offer request", e);
        }
    }

    /**
     * Handles a {@code REQOF} request message received from another peer.
     * <p>
     * Responds by sending full offer data for each requested offer as {@code OFDAT} messages.
     * The response is encrypted using the mesh ID as the shared key.
     *
     * @param msg the parsed request message
     */
    public void handleOfferRequest(ParsedMessage msg) {
        if (!userProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) {
            Log.i(TAG, "OfferRequest rejected due to different Mesh-ID: " + msg.getValue(Protocol.KEY_MID));
            return;
        }

        try {
            JSONArray requested = new JSONArray(msg.getValue(Protocol.KEY_REQ));
            List<Offer> localOffers = OfferManager.loadOffers(context);
            Map<String, Offer> offersById = new HashMap<>();
            for (Offer offer : localOffers) {
                offersById.put(offer.getOfferId(), offer);
            }

            JSONArray chunk = new JSONArray();

            for (int i = 0; i < requested.length(); i++) {
                String requestedId = requested.getString(i);
                Offer offer = offersById.get(requestedId);

                if (offer == null) {
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put(Protocol.KEY_OID, offer.getOfferId());
                obj.put(Protocol.KEY_TS, offer.getLastModified());
                obj.put(Protocol.KEY_TTL, offer.getTitle());
                obj.put(Protocol.KEY_DESC, offer.getDescription());
                obj.put(Protocol.KEY_CAT, offer.getCategory().getCode());
                obj.put(Protocol.KEY_CID, offer.getCreatorId());
                obj.put(Protocol.KEY_STAT, offer.getStatus().getCode());
                chunk.put(obj);

                Map<String, String> payload = new HashMap<>();
                payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
                payload.put(Protocol.KEY_UID, userProfile.getId());
                payload.put(Protocol.KEY_MID, userProfile.getMeshId());

                // Encryption
                SecureCrypto.EncryptedPayload encrypted = SecureCrypto.encrypt(chunk.toString(), userProfile.getMeshId());
                payload.put(Protocol.KEY_IV, encrypted.iv);
                payload.put(Protocol.KEY_OFA, encrypted.ciphertext);

                String message = Protocol.build(Protocol.OFDAT, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    // Encryption
                    encrypted = SecureCrypto.encrypt(chunk.toString(), userProfile.getMeshId());
                    payload.put(Protocol.KEY_IV, encrypted.iv);
                    payload.put(Protocol.KEY_OFA, encrypted.ciphertext);
                    Log.d(TAG, "OFDAT larger than 960 B - " + Protocol.build(Protocol.OFDAT, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.OFDAT, payload));

                    chunk = new JSONArray();
                }

                if (i == requested.length() - 1 && chunk.length() > 0) {
                    // Encryption
                    encrypted = SecureCrypto.encrypt(chunk.toString(), userProfile.getMeshId());
                    payload.put(Protocol.KEY_IV, encrypted.iv);
                    payload.put(Protocol.KEY_OFA, encrypted.ciphertext);
                    Log.d(TAG, "OFDAT smaller than 960 B - " + Protocol.build(Protocol.OFDAT, payload));
                    loRaManager.sendBroadcast(Protocol.build(Protocol.OFDAT, payload));
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "Failed to process offer request", e);
        }
    }

    /**
     * Handles an incoming {@code OFDAT} message containing encrypted offer data.
     * <p>
     * Decrypts and parses the data, updates or adds offers to local storage.
     *
     * @param msg the parsed data message
     */
    public void handleOfferData(ParsedMessage msg) {
        try {
            List<Offer> current = OfferManager.loadOffers(context);

            // Decryption
            String iv = msg.getValue(Protocol.KEY_IV);
            String ciphertext = msg.getValue(Protocol.KEY_OFA);
            String decryptedJson = SecureCrypto.decrypt(ciphertext, iv, userProfile.getMeshId());

            JSONArray array = new JSONArray(decryptedJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject data = array.getJSONObject(i);
                importOffer(data, current);
            }

            OfferManager.saveOffers(context, current);
            Log.i(TAG, "Saving incoming offer data");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to handle incoming offer data", e);
        }
    }

    /**
     * Imports a single offer from JSON into the given list.
     * <p>
     * Updates the existing entry if already present, or adds it otherwise.
     *
     * @param data    the JSON representation of the offer
     * @param current the list of existing local offers
     * @throws JSONException if any field is missing or invalid
     */
    private void importOffer(JSONObject data, List<Offer> current) throws JSONException {
        Offer offer = new Offer();
        offer.setOfferId(data.getString(Protocol.KEY_OID));
        offer.setTitle(data.getString(Protocol.KEY_TTL));
        offer.setDescription(data.getString(Protocol.KEY_DESC));
        offer.setCreatorId(data.getString(Protocol.KEY_CID));
        offer.setCategory(Constants.getCategoryFromCode(data.getString(Protocol.KEY_CAT)));
        offer.setStatus(Constants.getOfferStatusFromCode(data.getString(Protocol.KEY_STAT)));
        offer.setLastModified(data.getLong(Protocol.KEY_TS));

        OfferManager.updateOrAdd(current, offer);
    }

    /**
     * Automatically sets offers to INACTIVE if they are older than {@code MAX_ACTIVE_AGE_MS}.
     * <p>
     * The method is called before sending a sync broadcast to avoid advertising stale offers.
     */
    private void deactivateOutdatedOffers() {
        List<Offer> offers = OfferManager.loadOffers(context);
        long cutoff = System.currentTimeMillis() - MAX_ACTIVE_AGE_MS;
        boolean changed = false;

        for (Offer offer : offers) {
            if (offer.getStatus() == Constants.OfferStatus.ACTIVE && offer.getLastModified() < cutoff) {
                offer.setStatus(Constants.OfferStatus.INACTIVE);
                changed = true;
                Log.d(TAG, "Auto-inactivated state for offer " + offer.getOfferId());
            }
        }
        if (changed) {
            OfferManager.saveOffers(context, offers);
        }
    }
}
