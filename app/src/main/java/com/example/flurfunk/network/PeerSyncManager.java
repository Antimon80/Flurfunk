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
 * Manages the synchronization of peer {@link UserProfile} data across nearby devices using LoRa communication.
 * <p>
 * The synchronization process involves three protocol message types:
 * <ul>
 *     <li><b>SYNPR</b> – peer_list: summary of known peer IDs and their last modification timestamps</li>
 *     <li><b>REQPR</b> – peer_request: request for missing or outdated peer profiles</li>
 *     <li><b>PRDAT</b> – peer_data: full profile data for one or more requested peers</li>
 * </ul>
 * This manager ensures that each device maintains an up-to-date set of peer profiles
 * for all users sharing the same mesh (i.e. address-based) network.
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

    /**
     * Sends a {@code SYNPR} broadcast to peers with the same mesh ID.
     * <p>
     * The message includes the local device's lastSeen timestamp
     * and a list of known peer profile timestamps for comparison.
     */
    public void sendPeerSync() {
        List<UserProfile> allPeers = PeerManager.loadPeers(context);
        JSONArray chunk = new JSONArray();

        localProfile.updateLastSeen();
        PeerManager.updateLastSeen(context, localProfile.getId(), localProfile.getLastSeen());

        for (UserProfile peer : allPeers) {
            if (!peer.getMeshId().equals(localProfile.getMeshId())) {
                continue;
            }
            if (!PeerManager.isPeerActive(peer)) {
                Log.d(TAG, "No active peers, stopping PeerSync");
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
                payload.put(Protocol.KEY_LS, String.valueOf(localProfile.getLastSeen()));
                payload.put(Protocol.KEY_PRS, chunk.toString());

                String message = Protocol.build(Protocol.SYNPR, payload);

                if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                    chunk.remove(chunk.length() - 1);

                    payload.put(Protocol.KEY_PRS, chunk.toString());
                    loRaManager.sendBroadcast(Protocol.build(Protocol.SYNPR, payload));

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
            payload.put(Protocol.KEY_LS, String.valueOf(localProfile.getLastSeen()));
            payload.put(Protocol.KEY_PRS, chunk.toString());

            loRaManager.sendBroadcast(Protocol.build(Protocol.SYNPR, payload));
        }

    }

    /**
     * Handles an incoming {@code SYNPR} peer list message.
     * <p>
     * Updates the sender's {@code lastSeen} timestamp and determines
     * which profiles are missing or outdated. A {@code REQPR} is sent if needed.
     *
     * @param msg the parsed {@link ParsedMessage} containing peer summaries
     */
    public void handlePeerList(ParsedMessage msg) {
        if (!localProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) {
            Log.i(TAG, "Ignored peer sync from different mesh");
            return;
        }

        String senderId = msg.getValue(Protocol.KEY_UID);
        String lastSeenStr = msg.getValue(Protocol.KEY_LS);
        if (senderId != null && lastSeenStr != null) {
            try {
                long lastSeen = Long.parseLong(lastSeenStr);
                PeerManager.updateLastSeen(context, senderId, lastSeen);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid lastSeen format from " + senderId);
            }
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
                sendPeerRequest(toRequest);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse peer list", e);
        }
    }

    /**
     * Sends a {@code REQPR} request for full profile data for the specified peer IDs.
     * <p>
     * Large requests are split into multiple packets if they exceed the LoRa size limit.
     *
     * @param peerIds the list of peer IDs to request
     */
    private void sendPeerRequest(List<String> peerIds) {
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
                    loRaManager.sendBroadcast(Protocol.build(Protocol.REQPR, payload));

                    chunk = new JSONArray().put(peerIds.get(i));
                }

                if (i == peerIds.size() - 1 && chunk.length() > 0) {
                    payload.put(Protocol.KEY_REQ, chunk.toString());
                    loRaManager.sendBroadcast(Protocol.build(Protocol.REQPR, payload));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to build REQPR message", e);
        }
    }

    /**
     * Handles an incoming {@code REQPR} request.
     * <p>
     * Sends a {@code PRDAT} response containing full profile data for all requested peer IDs
     * that are available locally.
     *
     * @param msg the parsed peer request message
     */
    public void handlePeerRequest(ParsedMessage msg) {
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

            if (!toSend.isEmpty()) sendPeerData(toSend);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to handle peer request", e);
        }
    }

    /**
     * Sends a {@code PRDAT} message containing full profile data.
     * <p>
     * Profile data is encrypted using the shared mesh ID. If the message size
     * exceeds 960 bytes, the data is chunked and sent in multiple messages.
     *
     * @param profiles the list of {@link UserProfile} objects to send
     */
    private void sendPeerData(List<UserProfile> profiles) {
        JSONArray chunk = new JSONArray();

        for (int i = 0; i < profiles.size(); i++) {
            JSONObject obj = profiles.get(i).toJsonForNetwork();
            chunk.put(obj);

            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
            payload.put(Protocol.KEY_UID, localProfile.getId());
            payload.put(Protocol.KEY_MID, localProfile.getMeshId());

            // Encryption
            SecureCrypto.EncryptedPayload encrypted = SecureCrypto.encrypt(chunk.toString(), localProfile.getMeshId());
            payload.put(Protocol.KEY_IV, encrypted.iv);
            payload.put(Protocol.KEY_PFD, encrypted.ciphertext);

            String message = Protocol.build(Protocol.PRDAT, payload);

            if (message.getBytes(StandardCharsets.UTF_8).length > MAX_LORA_BYTES) {
                chunk.remove(chunk.length() - 1);

                // Encryption
                encrypted = SecureCrypto.encrypt(chunk.toString(), localProfile.getMeshId());
                payload.put(Protocol.KEY_IV, encrypted.iv);
                payload.put(Protocol.KEY_PFD, encrypted.ciphertext);
                loRaManager.sendBroadcast(Protocol.build(Protocol.PRDAT, payload));

                chunk = new JSONArray().put(obj);
            }

            if (i == profiles.size() - 1 && chunk.length() > 0) {
                // Encryption
                encrypted = SecureCrypto.encrypt(chunk.toString(), localProfile.getMeshId());
                payload.put(Protocol.KEY_IV, encrypted.iv);
                payload.put(Protocol.KEY_PFD, encrypted.ciphertext);
                loRaManager.sendBroadcast(Protocol.build(Protocol.PRDAT, payload));
            }
        }
    }

    /**
     * Handles an incoming {@code PRDAT} message with encrypted peer profile data.
     * <p>
     * Decrypts the payload and updates the local peer list accordingly.
     * New peers are added, and outdated entries are updated.
     *
     * @param msg the parsed peer data message
     */
    public void handlePeerData(ParsedMessage msg) {
        if (!localProfile.getMeshId().equals(msg.getValue(Protocol.KEY_MID))) return;

        try {
            // Decryption
            String iv = msg.getValue(Protocol.KEY_IV);
            String ciphertext = msg.getValue(Protocol.KEY_PFD);
            String decryptedJson = SecureCrypto.decrypt(ciphertext, iv, localProfile.getMeshId());

            JSONArray array = new JSONArray(decryptedJson);
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

    /**
     * Handles an incoming {@code USRDEL} message indicating a peer was deleted.
     * <p>
     * Marks the peer as inactive and sets all of their active offers to {@code INACTIVE}.
     *
     * @param msg the parsed deletion message
     */
    public void handleUserDeletion(ParsedMessage msg) {
        String deletedUserId = msg.getValue(Protocol.KEY_UID);
        String meshId = msg.getValue(Protocol.KEY_MID);

        if (!localProfile.getMeshId().equals(meshId)) return;

        PeerManager.updateLastSeen(context, deletedUserId, 0);
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

    /**
     * Broadcasts a {@code USRDEL} message indicating that this user has been deleted
     * or marked as inactive, so that peers can update their records accordingly.
     */
    public void sendUserDeletion() {
        Map<String, String> payload = new HashMap<>();
        payload.put(Protocol.KEY_ID, String.format("%016x", ThreadLocalRandom.current().nextLong()));
        payload.put(Protocol.KEY_UID, localProfile.getId());
        payload.put(Protocol.KEY_MID, localProfile.getMeshId());

        String message = Protocol.build(Protocol.USRDEL, payload);
        loRaManager.sendBroadcast(message);
    }
}
