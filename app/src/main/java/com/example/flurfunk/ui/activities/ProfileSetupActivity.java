package com.example.flurfunk.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.flurfunk.MainActivity;
import com.example.flurfunk.R;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.PeerManager;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Activity that allows the user to set up their profile by entering address and contact information.
 * <p>
 * Once completed, the profile is saved locally and also stored in the peer list.
 * After saving, the user is redirected to the {@link MainActivity}.
 */
public class ProfileSetupActivity extends AppCompatActivity {

    private TextInputEditText editStreet, editHouseNumber, editZip, editCity, editName, editFloor, editPhone, editEmail;

    /**
     * Initializes the profile setup activity and its UI components.
     *
     * @param savedInstanceState saved state bundle from a previous instance, if available
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile_setup);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editStreet = findViewById(R.id.editTextStreet);
        editHouseNumber = findViewById(R.id.editTextHouseNumber);
        editZip = findViewById(R.id.editTextZipCode);
        editCity = findViewById(R.id.editTextCity);

        editName = findViewById(R.id.editTextName);
        editFloor = findViewById(R.id.editTextFloor);
        editPhone = findViewById(R.id.editTextPhone);
        editEmail = findViewById(R.id.editTextEmail);

        Button buttonCreate = findViewById(R.id.buttonCreateProfile);

        buttonCreate.setOnClickListener(v -> {
            String street = String.valueOf(editStreet.getText()).trim();
            String houseNumber = String.valueOf(editHouseNumber.getText()).trim();
            String zipCode = String.valueOf(editZip.getText()).trim();
            String city = String.valueOf(editCity.getText()).trim();

            String name = String.valueOf(editName.getText()).trim();
            String floor = String.valueOf(editFloor.getText()).trim();
            String phone = String.valueOf(editPhone.getText()).trim();
            String email = String.valueOf(editEmail.getText()).trim();

            if (name.isEmpty() || floor.isEmpty()) {
                Toast.makeText(this, "Bitte Name und Stockwerk angeben.", Toast.LENGTH_SHORT).show();
                return;
            }

            UserProfile profile = new UserProfile(street, houseNumber, zipCode, city, name, floor, phone, email);
            profile.saveToFile(this);
            Toast.makeText(this, "Profil gespeichert!", Toast.LENGTH_SHORT).show();
            PeerManager.updateOrAddPeer(this, profile);

            Intent intent = new Intent(ProfileSetupActivity.this, MainActivity.class);
            startActivity(intent);

            finish();
        });
    }
}
