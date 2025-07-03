package com.example.flurfunk.network;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.util.Constants;
import com.example.flurfunk.util.Protocol;
import com.example.flurfunk.util.Protocol.ParsedMessage;

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
 * This class handles:
 * <ul>
 *     <li>Sending sync messages with local offer summaries</li>
 *     <li>Receiving and comparing remote offer summaries</li>
 *     <li>Requesting missing or outdated offers</li>
 *     <li>Responding to offer requests</li>
 *     <li>Handling full offer data messages</li>
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
     * Constructs a new OfferSyncManager.
     *
     * @param context     the application context
     * @param userProfile the current user's profile
     * @param loRaManager the LoRaManager used to send messages
     */
    public OfferSyncManager(Context context, UserProfile userProfile, LoRaManager loRaManager) {
        this.context = context;
        this.userProfile = userProfile;
        this.loRaManager = loRaManager;
    }

    /**
     * Sends a synchronization message to all peers with the same address.
     * This message contains a summary (ID and timestamp) of all local offers.
     */
    public void sendOfferSync(String peerId) {
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
                payload.put(Protocol.KEY_HOP, "1");
                payload.put(Protocol.KEY_OFF, chunk.toString());

                String message = Protocol.build(Protocol.SYNOF, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_OFF, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.SYNOF, payload));

                    chunk = new JSONArray().put(offerList.get(i));
                }

                if (i == offerList.length() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_OFF, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.SYNOF, payload));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build SYNOF message");
        }

    }

    public JSONArray buildOfferList() {
        JSONArray offerSummaries = new JSONArray();
        List<Offer> localOffers = OfferManager.loadOffers(context);
        for (Offer offer : localOffers) {
            if (offer.getStatus() != Constants.OfferStatus.INACTIVE) {
                try {
                    JSONObject summary = new JSONObject();
                    summary.put(Protocol.KEY_OID, offer.getOfferId());
                    summary.put(Protocol.KEY_TS, offer.getLastModified());
                    offerSummaries.put(summary);
                } catch (JSONException e) {
                    Log.w(TAG, "Failed to build offer list");
                }
            }
        }
        return offerSummaries;
    }

    /**
     * Handles a received sync message by comparing remote offer summaries to local offers
     * and sending a request for any missing or outdated offers.
     *
     * @param senderId the ID of the sending peer
     * @param msg      the parsed sync message
     */
    public void handleSyncMessage(String senderId, ParsedMessage msg) {
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
                sendOfferRequest(senderId, missing);
                Log.i(TAG, "Sending offer request");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error while processing incoming sync", e);
        }
    }

    /**
     * Sends a request to a peer asking for the full data of the specified offer IDs.
     *
     * @param peerId   the ID of the peer to request from
     * @param offerIds the list of offer IDs to request
     */
    public void sendOfferRequest(String peerId, List<String> offerIds) {
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
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.REQOF, payload));

                    chunk = new JSONArray().put(offerIds.get(i));
                }

                if (i == offerIds.size() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.REQOF, payload));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build offer request", e);
        }
    }

    /**
     * Handles a request from a peer for specific offers.
     * Responds with full offer data, sent either as individual messages or in bulk.
     *
     * @param senderId the ID of the requesting peer
     * @param msg      the parsed offer request message
     */
    public void handleOfferRequest(String senderId, ParsedMessage msg) {
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
                payload.put(Protocol.KEY_OFA, chunk.toString());

                String message = Protocol.build(Protocol.OFALL, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_OFA, chunk.toString());
                    loRaManager.sendMessageTo(senderId, Protocol.build(Protocol.OFALL, payload));

                    chunk = new JSONArray();
                }

                if (i == requested.length() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_OFA, chunk.toString());
                    loRaManager.sendMessageTo(senderId, Protocol.build(Protocol.OFALL, payload));
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "Failed to process offer request", e);
        }
    }

    /**
     * Handles incoming full offer data messages (individual or batch),
     * imports them into the local storage, and updates existing entries if needed.
     *
     * @param msg the parsed message containing offer data
     */
    public void handleOfferData(ParsedMessage msg) {
        try {
            List<Offer> current = OfferManager.loadOffers(context);

            if (msg.command.equals(Protocol.OFDAT)) {
                JSONObject data = new JSONObject(msg.getValue(Protocol.KEY_OFD));
                importOffer(data, current);
            } else if (msg.command.equals(Protocol.OFALL)) {
                JSONArray array = new JSONArray(msg.getValue(Protocol.KEY_OFA));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject data = array.getJSONObject(i);
                    importOffer(data, current);
                }
            }

            OfferManager.saveOffers(context, current);
            Log.i(TAG, "Saving incoming offer data");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to handle incoming offer data", e);
        }
    }

    /**
     * Imports a single offer into the provided list of offers.
     * Updates the existing offer if it already exists, or adds a new one.
     *
     * @param data    the JSON object containing offer data
     * @param current the current list of local offers
     * @throws JSONException if data fields are missing or malformed
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
