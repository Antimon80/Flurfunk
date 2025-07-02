package com.example.flurfunk;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Build;
import android.util.Log;
import android.widget.Button;

import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.network.ATLoRaManager;
import com.example.flurfunk.network.MessageDispatcher;
import com.example.flurfunk.network.OfferSyncManager;
import com.example.flurfunk.network.OkHttpLoRaManager;
import com.example.flurfunk.network.PeerSyncManager;
import com.example.flurfunk.network.LoRaManager;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.ui.activities.ProfileSetupActivity;
import com.example.flurfunk.util.Protocol;
import com.google.android.material.navigation.NavigationView;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.AppCompatActivity;

import com.example.flurfunk.databinding.ActivityMainBinding;

import org.json.JSONArray;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The main activity of the Flurfunk app, launched after a user profile has been set up.
 * <p>
 * This class is responsible for:
 * <ul>
 *     <li>Initializing the LoRa communication backend (real device or emulator)</li>
 *     <li>Setting up the {@link MessageDispatcher} to handle incoming messages</li>
 *     <li>Periodically synchronizing peer and offer data with neighboring devices</li>
 *     <li>Displaying a navigation drawer for category and settings access</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration mAppBarConfiguration;
    private LoRaManager loRaManager;
    private MessageDispatcher dispatcher;
    private final Handler syncHandler = new Handler();
    private static final String TAG = "MainActivity";

    private static final int START_BYTES = 100;
    private static final int STEP_BYTES = 100;
    private static final int MAX_BYTES = 5000;
    private int testLen = START_BYTES;
    private int testSeq = 0;

    /**
     * Periodic task that synchronizes peer and offer data every 90 seconds.
     * <p>
     * Sends a broadcast peer sync message and, if neighbors are found,
     * triggers an offer sync.
     */
    private final Runnable syncTask = new Runnable() {
        @Override
        public void run() {
            if (dispatcher != null) {
                UserProfile profile = dispatcher.getLocalProfile();
                String meshId = profile.getMeshId();

                List<String> neighborIds = PeerManager.getPeerIdsWithMeshId(MainActivity.this, meshId);

                try {
                    String peerMsg = dispatcher.getPeerSyncManager().sendPeerSync();
                    dispatcher.getLoRaManager().sendMessageTo("broadcast", peerMsg);
                    Log.d(TAG, "Broadcast peer sync sent.");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to build peer sync payload", e);
                }

                if (!neighborIds.isEmpty()) {
                    syncHandler.postDelayed(() -> {
                        dispatcher.getOfferSyncManager().sendOfferSync("broadcast");
                        Log.d(TAG, "Offer sync sent");
                    }, 5000);
                } else {
                    Log.d(TAG, "No neighbours yet - skipping offer sync.");
                }
            }

            syncHandler.postDelayed(this, 90_000);
        }
    };


    /**
     * Called when the activity is created.
     * <p>
     * Loads the user profile, initializes the LoRa backend, sets up the
     * message dispatcher, starts synchronization, and configures the navigation UI.
     *
     * @param savedInstanceState the saved instance state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        SharedPreferences prefs = getSharedPreferences("flurfunk_prefs", MODE_PRIVATE);
        boolean introShown = prefs.getBoolean("intro_shown", false);

        // Show intro on first app start
        if (!introShown) {
            setContentView(R.layout.layout_intro);
            Button btnContinue = findViewById(R.id.btnContinue);
            btnContinue.setOnClickListener(v -> {
                prefs.edit().putBoolean("intro_shown", true).apply();
                recreate();
            });
            return;
        }

        // Load user profile
        UserProfile profile = UserProfile.loadFromFile(this);
        if (profile == null) {
            startActivity(new Intent(this, ProfileSetupActivity.class));
            finish();
            return;
        }

        // Detect if the app is running on an emulator
        boolean isEmulator =
                Build.FINGERPRINT.contains("generic")
                        || Build.FINGERPRINT.toLowerCase().contains("emulator")
                        || Build.MODEL.contains("Emulator")
                        || Build.MODEL.toLowerCase().contains("sdk")
                        || Build.PRODUCT.toLowerCase().contains("sdk");

        // Choose appropriate LoRaManager
        if (isEmulator) {
            Log.i(TAG, "Detected emulator → using OkHttpLoRaManager");
            loRaManager = new OkHttpLoRaManager();
        } else {
            Log.i(TAG, "Detected real device → using ATLoRaManager");
            loRaManager = new ATLoRaManager(this.getApplicationContext());
        }

        // Initialize message dispatcher
        dispatcher = new MessageDispatcher(this, profile, new OfferSyncManager(this, profile, loRaManager),
                new PeerSyncManager(this, profile, loRaManager), loRaManager);

        // Set up LoRa message handling
        loRaManager.setOnMessageReceived(dispatcher::onMessageReceived);
        loRaManager.start();

        // Start periodic sync
        syncHandler.post(syncTask);

        // Inflate and set layout
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drawer_layout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(findViewById(R.id.toolbar));

        // Setup navigation drawer and navigation controller
        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;

        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_category_electronics,
                R.id.nav_category_furniture,
                R.id.nav_category_household,
                R.id.nav_category_toys,
                R.id.nav_category_tools,
                R.id.nav_category_books,
                R.id.nav_category_clothing,
                R.id.nav_category_vehicles,
                R.id.nav_category_misc,
                R.id.nav_my_offers,
                R.id.nav_settings,
                R.id.nav_about)
                .setOpenableLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);
    }

    /**
     * Called when the activity is being destroyed.
     * Stops the LoRa backend and removes the scheduled sync task.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        syncHandler.removeCallbacks(syncTask);
        if (loRaManager != null) {
            loRaManager.stop();
        }
    }

    /**
     * Handles navigation when the user presses the "Up" button in the action bar.
     *
     * @return true if navigation was successful, false otherwise
     */
    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }

    public MessageDispatcher getDispatcher() {
        return this.dispatcher;
    }
}
