package com.example.flurfunk.model;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.example.flurfunk.util.Protocol;
import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a user's profile in the Flurfunk application.
 * The profile includes personal information such as name, address,
 * contact details, and a timestamp for versioning.
 * <p>
 * The profile can be saved to and loaded from internal storage as JSON.
 */
public class UserProfile {
    private static final String PROFILE_FILE = "user_profile.json";
    private static final String TAG = "UserProfile";

    private String id;
    private String name;
    private String floor;
    private String phone;
    private String email;
    private String meshId;
    private long timestamp;
    private long lastSeen;

    /**
     * Default constructor required for deserialization.
     */
    public UserProfile() {

    }

    /**
     * Constructs a new {@code UserProfile} with the given user and address details.
     * Automatically generates a unique ID and timestamp.
     *
     * @param street      the street name
     * @param houseNumber the house number
     * @param zipCode     the postal code
     * @param city        the city name
     * @param name        the user's name
     * @param floor       the floor
     * @param phone       the phone number (optional)
     * @param email       the email address (optional)
     */
    public UserProfile(
            String street, String houseNumber, String zipCode, String city,
            String name, String floor, String phone, String email) {
        this.id = String.format("%016x", ThreadLocalRandom.current().nextLong());
        this.name = name;
        this.floor = floor;
        this.phone = phone;
        this.email = email;
        this.timestamp = System.currentTimeMillis();
        this.meshId = computeAddressMeshId(street, houseNumber, zipCode, city);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMeshId() {
        return meshId;
    }

    public String getFloor() {
        return floor;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getLastSeen(){
        return lastSeen;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setFloor(String floor) {
        this.floor = floor;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setMeshId(String meshId) {
        this.meshId = meshId;
    }

    public void setLastSeen(long lastSeen){
        this.lastSeen = lastSeen;
    }

    /**
     * Updates the timestamp to the current system time.
     */
    public void updateTimestamp() {
        this.timestamp = System.currentTimeMillis();
    }

    public void updateLastSeen(){
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * Saves the user profile to a private JSON file in the app's internal storage.
     *
     * @param context the Android context
     */
    public void saveToFile(Context context) {
        try {
            String json = new Gson().toJson(this);
            FileOutputStream fos = context.openFileOutput(PROFILE_FILE, Context.MODE_PRIVATE);
            fos.write(json.getBytes());
            fos.close();
        } catch (IOException e) {
            Log.e(TAG, "Could not save user profile.");
        }
    }

    /**
     * Loads the user profile from internal storage if it exists.
     *
     * @param context the Android context
     * @return the loaded {@code UserProfile}, or {@code null} if loading failed
     */
    public static UserProfile loadFromFile(Context context) {
        try {
            FileInputStream fis = context.openFileInput(PROFILE_FILE);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader reader = new BufferedReader(isr);

            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }

            fis.close();
            return new Gson().fromJson(json.toString(), UserProfile.class);
        } catch (IOException e) {
            Log.e(TAG, "Could not load user profile.");
            return null;
        }
    }

    /**
     * Deletes the profile file from the app's internal storage.
     *
     * @param context the Android context
     */
    public static void deleteProfileFile(Context context) {
        context.deleteFile(PROFILE_FILE);
    }

    /**
     * Computes a mesh ID based on the given address components.
     * <p>
     * The method removes whitespace and non-alphanumeric characters from the address,
     * hashes the cleaned string using SHA-256, and returns a shortened base64-encoded version.
     * <p>
     * This mesh ID is used to identify and group peers living in the same building.
     *
     * @param street      the street name
     * @param houseNumber the house number
     * @param zipCode     the postal code
     * @param city        the city
     * @return a short base64-encoded mesh ID
     */
    public String computeAddressMeshId(String street, String houseNumber, String zipCode, String city) {
        String raw = (street + houseNumber + zipCode + city).replaceAll("\\s+", "").replaceAll("[^a-zA-Z0-9]", "").toLowerCase();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            byte[] shortHash = Arrays.copyOfRange(hash, 0, 6);
            return Base64.encodeToString(shortHash, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error while calculating mesh-ID");
        }
    }

    /**
     * Converts this profile into a JSON object formatted for network transmission.
     * <p>
     * Includes the user ID, name, floor, phone, email, timestamp, and mesh ID.
     * Keys are abbreviated according to the Flurfunk protocol specification.
     *
     * @return a {@link JSONObject} containing the user's data, or an empty object if an error occurs
     */
    public JSONObject toJsonForNetwork() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(Protocol.KEY_UID, id);
            obj.put(Protocol.KEY_NAME, name);
            obj.put(Protocol.KEY_FLR, floor);
            obj.put(Protocol.KEY_PHN, phone);
            obj.put(Protocol.KEY_MAIL, email);
            obj.put(Protocol.KEY_TS, timestamp);
            obj.put(Protocol.KEY_MID, meshId);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build UserProfile-JSON for network");
        }
        return obj;
    }
}
