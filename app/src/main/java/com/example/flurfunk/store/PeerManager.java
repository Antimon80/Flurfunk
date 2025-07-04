package com.example.flurfunk.store;

import android.content.Context;
import android.util.Log;

import com.example.flurfunk.model.UserProfile;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for managing peer data (i.e., other user profiles) in the Flurfunk app.
 * <p>
 * This class provides functionality for loading, saving, and updating peer profiles
 * using a local JSON file stored in the app’s internal storage.
 */
public class PeerManager {

    private static final long INACTIVITY_TIMEOUT_MS = 1000L * 60 * 60 * 24 * 7 * 6;
    private static final String FILE_NAME = "peers.json";
    private static final String TAG = "PeerManager";

    /**
     * Loads a list of peers (user profiles) from internal storage.
     *
     * @param context the Android context used for file access
     * @return a list of {@link UserProfile} objects, or an empty list if loading fails
     */
    public static List<UserProfile> loadPeers(Context context) {
        try {
            FileInputStream fis = context.openFileInput(FILE_NAME);
            BufferedReader reader = new BufferedReader((new InputStreamReader(fis)));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            fis.close();
            Type listType = new TypeToken<List<UserProfile>>() {
            }.getType();
            return new Gson().fromJson(builder.toString(), listType);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't load peers.");
            return new ArrayList<>();
        }
    }

    /**
     * Saves a list of peers (user profiles) to internal storage as JSON.
     *
     * @param context the Android context used for file operations
     * @param peers   the list of {@link UserProfile} objects to save
     */
    public static void savePeers(Context context, List<UserProfile> peers) {
        try {
            String json = new Gson().toJson(peers);
            FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
            fos.write(json.getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Couldn't save peers.");
        }
    }

    /**
     * Retrieves a peer profile by its unique ID.
     *
     * @param context   the Android context used for loading data
     * @param creatorId the ID of the peer to retrieve
     * @return the {@link UserProfile} with the given ID, or {@code null} if not found
     */
    public static UserProfile getPeerById(Context context, String creatorId) {
        if (creatorId == null) return null;
        List<UserProfile> peers = loadPeers(context);
        for (UserProfile peer : peers) {
            if (creatorId.equals(peer.getId())) {
                return peer;
            }
        }
        return null;
    }

    /**
     * Updates a peer if it already exists (matching by ID), or adds it to the list otherwise.
     * The updated list is immediately saved to storage.
     *
     * @param context the Android context used for file operations
     * @param newPeer the new or updated {@link UserProfile} to add
     */
    public static void updateOrAddPeer(Context context, UserProfile newPeer) {
        List<UserProfile> peers = loadPeers(context);
        for (int i = 0; i < peers.size(); i++) {
            if (peers.get(i).getId().equals(newPeer.getId())) {
                peers.set(i, newPeer);
                savePeers(context, peers);
                return;
            }
        }
        peers.add(newPeer);
        savePeers(context, peers);
    }

    /**
     * Retrieves a list of peer IDs that match the ID of a given LoRa mesh network.
     *
     * @param context the Android context used for loading data
     * @param meshId  the ID encoding a full address to match (e.g. "Musterstrasse 12, 4056 Basel")
     * @return a list of peer IDs with the matching address
     */
    public static List<String> getPeerIdsWithMeshId(Context context, String meshId) {
        return loadPeers(context).stream()
                .filter(p -> meshId.equals(p.getMeshId()))
                .map(UserProfile::getId)
                .collect(Collectors.toList());
    }

    public static boolean isPeerActive(UserProfile peer) {
        return peer.getLastSeen() > 0 && (System.currentTimeMillis() - peer.getLastSeen() < INACTIVITY_TIMEOUT_MS);
    }

    /**
     * Updates the lastSeen timestamp of a peer with the given ID.
     * If the peer is found, its lastSeen is set and saved.
     *
     * @param context   the Android context used for file access
     * @param peerId    the ID of the peer to update
     * @param lastSeen  the new lastSeen timestamp (usually from a received SYNPR)
     */
    public static void updateLastSeen(Context context, String peerId, long lastSeen) {
        List<UserProfile> peers = loadPeers(context);
        boolean changed = false;
        for (UserProfile peer : peers) {
            if (peer.getId().equals(peerId)) {
                peer.setLastSeen(lastSeen);
                changed = true;
                break;
            }
        }
        if (changed) savePeers(context, peers);
    }
}
