package com.example.flurfunk.ui.activities;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.flurfunk.R;

import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.util.Constants;
import com.example.flurfunk.util.Constants.Category;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Activity for creating a new {@link Offer}.
 * <p>
 * This activity allows the user to enter a title, description, and category
 * for their offer. It loads the current user profile, validates the input,
 * and stores the offer using {@link OfferManager}.
 */
public class CreateOfferActivity extends AppCompatActivity {

    private TextInputEditText editTitle;
    private TextInputEditText editDescription;
    private Spinner categorySpinner;

    /**
     * Called when the activity is starting.
     * Initializes UI elements, configures category spinner, and handles intent data if provided.
     *
     * @param savedInstanceState if the activity is being re-initialized after previously being shut down,
     *                           this contains the data it most recently supplied.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_create_offer);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layoutCreateOffer), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editTitle = findViewById(R.id.editOfferTitle);
        editDescription = findViewById(R.id.editOfferDescription);
        categorySpinner = findViewById(R.id.spinnerCategory);
        Button buttonSubmit = findViewById(R.id.buttonCreateOffer);
        Button buttonCancel = findViewById(R.id.buttonCancel);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                Constants.getAllCategoryDisplayNames()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);

        String categoryName = getIntent().getStringExtra("category");
        if (categoryName != null) {
            try {
                Category enumCategory = Category.valueOf(categoryName);
                String displayName = Constants.getDisplayNameForCategory(enumCategory);

                int position = adapter.getPosition(displayName);
                if (position >= 0) {
                    categorySpinner.setSelection(position);
                }
            } catch (IllegalArgumentException e) {
                Log.w("CreateOfferActivity", "Unbekannte Kategorie: " + categoryName, e);
            }
        }

        buttonSubmit.setOnClickListener(v -> createOffer());
        buttonCancel.setOnClickListener(v -> finish());
    }

    /**
     * Validates the form input and creates a new {@link Offer} if all fields are valid.
     * The offer is saved to local storage and associated with the current user profile.
     */
    private void createOffer() {
        String title = String.valueOf(editTitle.getText()).trim();
        String description = String.valueOf(editDescription.getText()).trim();
        String displayName = (String) categorySpinner.getSelectedItem();
        Category category = Constants.getCategoryFromDisplayName(displayName);

        if (title.isEmpty()) {
            Toast.makeText(this, "Bitte gib einen Titel ein.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (description.isEmpty()) {
            Toast.makeText(this, "Bitte gib eine Beschreibung ein.", Toast.LENGTH_SHORT).show();
            return;
        }

        UserProfile profile = UserProfile.loadFromFile(this);
        if (profile == null) {
            Toast.makeText(this, "Kein Benutzerprofil gefunden.", Toast.LENGTH_LONG).show();
            return;
        }

        Offer newOffer = new Offer(title, description, category, profile.getId());
        List<Offer> offers = OfferManager.loadOffers(this);
        OfferManager.updateOrAdd(offers, newOffer);
        OfferManager.saveOffers(this, offers);

        Toast.makeText(this, "Angebot erstellt!", Toast.LENGTH_SHORT).show();
        finish();
    }
}
