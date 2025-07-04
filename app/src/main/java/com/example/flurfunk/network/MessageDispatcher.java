package com.example.flurfunk.network;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MessageDispatcher {

    private static final String TAG = "MessageDispatcher";

    private final Context context;
    private final UserProfile localProfile;
    private final OfferSyncManager offerSyncManager;
    private final PeerSyncManager peerSyncManager;
    private final LoRaManager loRaManager;

    private final Set<String> seenMessageIds = new HashSet<>();

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
     * Entry point for all received messages.
     *
     * @param message  the full protocol message (e.g. "#SYNOF#ID=...;ADR=...;...")
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

        if (messageId != null) seenMessageIds.add(messageId);

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
