package com.example.flurfunk.ui.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.example.flurfunk.MainActivity;
import com.example.flurfunk.R;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.network.LoRaManager;
import com.example.flurfunk.network.PeerSyncManager;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.ui.activities.ProfileSetupActivity;
import com.example.flurfunk.util.Protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Fragment that displays the user's settings, allowing them to view, update, or delete their profile.
 * <p>
 * The user can edit their name, floor, email, and phone number. Upon saving, changes are stored locally
 * and also propagated to the peer list. If the user deletes their profile, they are redirected to
 * the {@link ProfileSetupActivity}.
 */
public class SettingsFragment extends Fragment {

    private EditText editFloor, editEmail, editPhone;
    private UserProfile profile;
    private static final String TAG = "SettingsFragment";

    /**
     * Inflates the settings layout.
     *
     * @param inflater          the layout inflater
     * @param container         the parent view group
     * @param saveInstanceState saved state from previous instance (unused)
     * @return the inflated view
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saveInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    /**
     * Initializes the UI components and sets up button listeners for saving and deleting the profile.
     *
     * @param view               the root view of the fragment
     * @param savedInstanceState saved state from previous instance (unused)
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        editFloor = view.findViewById(R.id.editFloor);
        editEmail = view.findViewById(R.id.editEmail);
        editPhone = view.findViewById(R.id.editPhone);
        Button buttonSave = view.findViewById(R.id.buttonSaveProfile);
        Button buttonDelete = view.findViewById(R.id.buttonDeleteProfile);

        Context context = requireContext();
        profile = UserProfile.loadFromFile(context);
        if (profile != null) {
            editFloor.setText(profile.getFloor());
            editEmail.setText(profile.getEmail());
            editPhone.setText(profile.getPhone());
        }

        buttonSave.setOnClickListener(v -> {
            if (profile != null) {
                profile.setFloor(editFloor.getText().toString());
                profile.setEmail(editEmail.getText().toString());
                profile.setPhone(editPhone.getText().toString());
                profile.updateTimestamp();
                profile.saveToFile(context);
                PeerManager.updateOrAddPeer(context, profile);

                Toast.makeText(context, "Profil gespeichert", Toast.LENGTH_SHORT).show();
                requireActivity().getSupportFragmentManager().popBackStack();
            }
        });

        buttonDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context).setTitle("Profil löschen").setMessage("Willst du dein Profil wirklich löschen?")
                    .setPositiveButton("Ja", (dialog, which) -> {
                        try {
                            LoRaManager loRaManager = ((MainActivity) requireActivity()).getDispatcher().getLoRaManager();
                            PeerSyncManager peerSyncManager = new PeerSyncManager(context, profile, loRaManager);
                            peerSyncManager.sendUserDeletion();

                            Log.i(TAG, "Deletion broadcast sent for profile " + profile.getId());

                        } catch (Exception e) {
                            Log.e(TAG, "Failed to broadcast profile deletion", e);
                        }

                        UserProfile.deleteProfileFile(context);

                        Intent intent = new Intent(context, ProfileSetupActivity.class);
                        startActivity(intent);
                        requireActivity().finish();
                    }).setNegativeButton("Nein", null).show();
        });
    }
}
