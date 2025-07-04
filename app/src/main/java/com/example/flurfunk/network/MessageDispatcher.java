package com.example.flurfunk.network;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.util.Protocol;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Central dispatcher for all incoming LoRa messages in the Flurfunk network stack.
 * <p>
 * The {@code MessageDispatcher} parses incoming protocol messages,
 * filters duplicates and mismatched mesh IDs, and delegates them
 * to the appropriate synchronization managers.
 * <p>
 * Currently supported message types include offer sync, peer sync,
 * peer deletion, and offer data exchange.
 */

public class MessageDispatcher {

    private static final String TAG = "MessageDispatcher";

    private final Context context;
    private final UserProfile localProfile;
    private final OfferSyncManager offerSyncManager;
    private final PeerSyncManager peerSyncManager;
    private final LoRaManager loRaManager;

    private final Set<String> seenMessageIds = new HashSet<>();

    /**
     * Constructs a new {@code MessageDispatcher} responsible for handling received LoRa messages.
     *
     * @param context           the Android context
     * @param localProfile      the local user profile (used for mesh ID filtering)
     * @param offerSyncManager  the offer synchronization handler
     * @param peerSyncManager   the peer synchronization handler
     * @param loRaManager       the LoRa communication interface
     */

    public MessageDispatcher(Context context, UserProfile localProfile, OfferSyncManager offerSyncManager, PeerSyncManager peerSyncManager, LoRaManager loRaManager) {
        this.context = context;
        this.localProfile = localProfile;
        this.offerSyncManager = offerSyncManager;
        this.peerSyncManager = peerSyncManager;
        this.loRaManager = loRaManager;
    }

    public PeerSyncManager getPeerSyncManager() {
        return peerSyncManager;
    }

    public OfferSyncManager getOfferSyncManager() {
        return offerSyncManager;
    }

    public UserProfile getLocalProfile() {
        return localProfile;
    }

    public LoRaManager getLoRaManager() {
        return loRaManager;
    }

    /**
     * Entry point for all received LoRa messages.
     * <p>
     * Parses the message using the Flurfunk protocol format and dispatches
     * it to the appropriate handler based on the protocol command.
     * <p>
     * The dispatcher also:
     * <ul>
     *     <li>Validates the mesh ID</li>
     *     <li>Prevents duplicate message processing using a rolling cache</li>
     * </ul>
     *
     * @param message the full protocol message string received via LoRa
     */

    public void onMessageReceived(String message) {
        Protocol.ParsedMessage parsed;
        try {
            parsed = Protocol.parse(message);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid protocol message: " + message);
            return;
        }

        String command = parsed.command;
        Map<String, String> data = parsed.keyValues;

        String messageId = data.get(Protocol.KEY_ID);
        String meshId = data.get(Protocol.KEY_MID);

        if (meshId == null || !meshId.equals(localProfile.getMeshId())) {
            Log.d(TAG, "Ignoring message for different Mesh-ID: " + meshId);
            return;
        }

        if (messageId != null && seenMessageIds.contains(messageId)) {
            Log.d(TAG, "Duplicate message ignored: " + message);
            return;
        }

        if (messageId != null){
            seenMessageIds.add(messageId);
            if(seenMessageIds.size() > 1000){
                Iterator<String> iterator = seenMessageIds.iterator();
                iterator.next();
                iterator.remove();
            }
        }

        switch (command) {
            case Protocol.SYNOF:
                offerSyncManager.handleSyncMessage(parsed);
                break;
            case Protocol.REQOF:
                offerSyncManager.handleOfferRequest(parsed);
                break;
            case Protocol.OFDAT:
                offerSyncManager.handleOfferData(parsed);
                break;

            case Protocol.SYNPR:
                peerSyncManager.handlePeerList(parsed);
                break;
            case Protocol.REQPR:
                peerSyncManager.handlePeerRequest(parsed);
                break;

            case Protocol.PRDAT:
                peerSyncManager.handlePeerData(parsed);
                break;

            case Protocol.USRDEL:
                peerSyncManager.handleUserDeletion(parsed);
                break;

            default:
                Log.w(TAG, "Unknown protocol command: " + command);
        }
    }
}
