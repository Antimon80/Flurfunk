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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the synchronization of peer {@link UserProfile} data across nearby devices using LoRa communication.
 * <p>
 * The synchronization process involves three protocol message types:
 * <ul>
 *     <li><b>SYNPR</b> – peer_list: summary of known peer IDs and their last modification timestamps</li>
 *     <li><b>REQPR</b> – peer_request: request for missing or outdated peer profiles</li>
 *     <li><b>PRDAT</b> – peer_data: full profile data for one or more requested peers</li>
 * </ul>
 * <p>
 * This manager ensures that each device maintains an up-to-date set of peer profiles within the same address scope.
 */
public class PeerSyncManager {

    private final Context context;
    private final UserProfile localProfile;
    private final LoRaManager loRaManager;
    private static final int MAX_LORA_BYTES = 960;
    private static final String TAG = "PeerSyncManager";

    /**
     * Constructs a new {@code PeerSyncManager}.
     *
     * @param context      Android application context
     * @param localProfile the current user's profile
     * @param loRaManager  LoRa communication handler
     */
    public PeerSyncManager(Context context, UserProfile localProfile, LoRaManager loRaManager) {
        this.context = context;
        this.localProfile = localProfile;
        this.loRaManager = loRaManager;
    }

    public void sendPeerSync(String peerId) {
        List<UserProfile> allPeers = PeerManager.loadPeers(context);
        JSONArray chunk = new JSONArray();

        for (UserProfile peer : allPeers) {
            if (!peer.getMeshId().equals(localProfile.getMeshId())) {
                continue;
            }
            if (!PeerManager.isPeerActive(peer)) {
                continue;
            }

            try {
                JSONObject summary = new JSONObject();
                summary.put(Protocol.KEY_UID, peer.getId());
                summary.put(Protocol.KEY_TS, peer.getTimestamp());
                chunk.put(summary);

                Map<String, String> payload = new HashMap<>();
                payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
                payload.put(Protocol.KEY_UID, localProfile.getId());
                payload.put(Protocol.KEY_MID, localProfile.getMeshId());
                payload.put(Protocol.KEY_HOP, "1");
                payload.put(Protocol.KEY_PRS, chunk.toString());

                String message = Protocol.build(Protocol.SYNPR, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_PRS, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.SYNPR, payload));

                    chunk = new JSONArray().put(summary);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error while preparing peer sync", e);
            }
        }

        if (chunk.length() > 0) {
            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
            payload.put(Protocol.KEY_UID, localProfile.getId());
            payload.put(Protocol.KEY_MID, localProfile.getMeshId());
            payload.put(Protocol.KEY_HOP, "1");
            payload.put(Protocol.KEY_PRS, chunk.toString());

            loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.SYNPR, payload));
        }

    }

    /**
     * Creates a summary of all locally known peer profiles, including only their ID and last modified timestamp.
     * <p>
     * This summary is used during synchronization to detect which profiles are missing or outdated.
     *
     * @return a {@link JSONArray} containing the peer summaries
     */
    public JSONArray buildPeerList() {
        JSONArray peerArray = new JSONArray();
        List<UserProfile> peerList = PeerManager.loadPeers(context);
        for (UserProfile peer : peerList) {
            try {
                JSONObject object = new JSONObject();
                object.put(Protocol.KEY_UID, peer.getId());
                object.put(Protocol.KEY_TS, peer.getTimestamp());
                peerArray.put(object);
            } catch (JSONException e) {
                Log.e(TAG, "Error building peer list", e);
            }
        }
        return peerArray;
    }

    /**
     * Handles an incoming peer list ({@code SYNPR}) message from a remote peer.
     * Compares the list with local peer profiles and requests any missing or outdated entries.
     *
     * @param senderId the ID of the sending peer
     * @param msg      the parsed protocol message
     */
    public void handlePeerList(String senderId, ParsedMessage msg) {
        if (!localProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) {
            Log.i(TAG, "Ignored peer sync from different mesh");
            return;
        }

        try {
            JSONArray remote = new JSONArray(msg.getValue(Protocol.KEY_PRS));
            List<UserProfile> localPeers = PeerManager.loadPeers(context);
            Map<String, UserProfile> localMap = new HashMap<>();
            for (UserProfile profile : localPeers) {
                localMap.put(profile.getId(), profile);
            }

            List<String> toRequest = new ArrayList<>();

            for (int i = 0; i < remote.length(); i++) {
                JSONObject remotePeer = remote.getJSONObject(i);
                String id = remotePeer.getString(Protocol.KEY_UID);
                long ts = remotePeer.getLong(Protocol.KEY_TS);

                if (!localMap.containsKey(id) || localMap.get(id).getTimestamp() < ts) {
                    toRequest.add(id);
                }
            }
            if (!toRequest.isEmpty()) {
                sendPeerRequest(senderId, toRequest);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse peer list", e);
        }
    }

    /**
     * Sends a {@code REQPR} peer request message to a peer,
     * requesting full profiles for the specified peer IDs.
     *
     * @param peerId  the ID of the peer to request from
     * @param peerIds the list of peer IDs to request
     */
    private void sendPeerRequest(String peerId, List<String> peerIds) {
        JSONArray chunk = new JSONArray();

        try {
            for (int i = 0; i < peerIds.size(); i++) {
                chunk.put(peerIds.get(i));

                Map<String, String> payload = new HashMap<>();
                payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
                payload.put(Protocol.KEY_UID, localProfile.getId());
                payload.put(Protocol.KEY_MID, localProfile.getMeshId());
                payload.put(Protocol.KEY_REQ, chunk.toString());

                String message = Protocol.build(Protocol.REQPR, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > 960) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.REQPR, payload));

                    chunk = new JSONArray().put(peerIds.get(i));
                }

                if (i == peerIds.size() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.REQPR, payload));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to build REQPR message", e);
        }
    }

    /**
     * Handles an incoming {@code REQPR} peer request and responds
     * with the full profile data for all requested peers that are known locally.
     *
     * @param senderId the ID of the requesting peer
     * @param msg      the parsed request message
     */
    public void handlePeerRequest(String senderId, Protocol.ParsedMessage msg) {
        if (!localProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) return;

        try {
            JSONArray requested = new JSONArray(msg.getValue(Protocol.KEY_REQ));
            List<UserProfile> allPeers = PeerManager.loadPeers(context);
            List<UserProfile> toSend = new ArrayList<>();

            for (int i = 0; i < requested.length(); i++) {
                String id = requested.getString(i);
                for (UserProfile peer : allPeers) {
                    if (peer.getId().equals(id)) {
                        toSend.add(peer);
                        break;
                    }
                }
            }

            if (!toSend.isEmpty()) sendPeerData(senderId, toSend);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to handle peer request", e);
        }
    }

    /**
     * Sends a {@code PRDAT} message containing full profile data for the given list of peers.
     *
     * @param peerId   the ID of the peer to send data to
     * @param profiles the list of peer profiles to send
     */
    private void sendPeerData(String peerId, List<UserProfile> profiles) {
        JSONArray chunk = new JSONArray();

        for (int i = 0; i < profiles.size(); i++) {
            JSONObject obj = profiles.get(i).toJsonForNetwork();
            chunk.put(obj);

            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
            payload.put(Protocol.KEY_UID, localProfile.getId());
            payload.put(Protocol.KEY_MID, localProfile.getMeshId());
            payload.put(Protocol.KEY_PFD, chunk.toString());

            String message = Protocol.build(Protocol.PRDAT, payload);

            if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                chunk.remove(chunk.length() - 1);

                payload.put(Protocol.KEY_PFD, chunk.toString());
                loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.PRDAT, payload));

                chunk = new JSONArray().put(obj);
            }

            if (i == profiles.size() - 1 && chunk.length() > 0) {
                payload.put(Protocol.KEY_PFD, chunk.toString());
                loRaManager.sendMessageTo(peerId, Protocol.build(Protocol.PRDAT, payload));
            }
        }
    }

    /**
     * Handles an incoming {@code PRDAT} message and updates the local peer database
     * with the received profile information.
     * <p>
     * Existing profiles are updated if timestamps are newer; unknown profiles are added.
     *
     * @param msg the parsed protocol message containing peer profile data
     */
    public void handlePeerData(ParsedMessage msg) {
        if (!localProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) return;

        try {
            JSONArray array = new JSONArray(msg.getValue(Protocol.KEY_PFD));
            for (int i = 0; i < array.length(); i++) {
                JSONObject data = array.getJSONObject(i);

                UserProfile profile = new UserProfile();
                profile.setId(data.getString(Protocol.KEY_UID));
                profile.setName(data.getString(Protocol.KEY_NAME));
                profile.setFloor(data.getString(Protocol.KEY_FLR));
                profile.setPhone(data.optString(Protocol.KEY_PHN, null));
                profile.setEmail(data.optString(Protocol.KEY_MAIL, null));
                profile.setTimestamp(data.getLong(Protocol.KEY_TS));
                profile.setMeshId(data.getString(Protocol.KEY_MID));

                PeerManager.updateOrAddPeer(context, profile);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to handle peer data", e);
        }
    }

    public void handleUserDeletion(ParsedMessage msg) {
        String deletedUserId = msg.getValue(Protocol.KEY_UID);
        String meshId = msg.getValue(Protocol.KEY_MID);

        if (!localProfile.getMeshId().equals(meshId)) return;

        PeerManager.markAsInactive(context, deletedUserId);
        List<Offer> offers = OfferManager.loadOffers(context);
        boolean changed = false;

        for (Offer offer : offers) {
            if (deletedUserId.equals(offer.getCreatorId()) && offer.getStatus() == Constants.OfferStatus.ACTIVE) {
                offer.setStatus(Constants.OfferStatus.INACTIVE);
                offer.setLastModified(System.currentTimeMillis());
                changed = true;
            }
        }

        if (changed) {
            OfferManager.saveOffers(context, offers);
            Log.i(TAG, "Offers of deleted user " + deletedUserId + " marked as inactive.");
        } else {
            Log.i(TAG, "User deletion received for " + deletedUserId + ", no active offers found.");
        }
    }

    public void sendUserDeletion() {
        Map<String, String> payload = new HashMap<>();
        payload.put(Protocol.KEY_ID , String.format("%016x", ThreadLocalRandom.current().nextLong()));
        payload.put(Protocol.KEY_UID, localProfile.getId());
        payload.put(Protocol.KEY_MID, localProfile.getMeshId());

        String message = Protocol.build(Protocol.USRDEL, payload);
        loRaManager.sendMessageTo("broadcast", message);
    }
}
