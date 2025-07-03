package com.example.flurfunk.ui.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.flurfunk.R;
import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Constants;

import java.util.List;

/**
 * A fragment that displays detailed information about a selected offer.
 * <p>
 * The fragment shows the offer's title, description, creator's name, floor, and contact info.
 * If the current user is the creator of the offer, a button is shown to toggle the offer's active status.
 */
public class OfferDetailFragment extends Fragment {

    /**
     * Inflates the fragment layout.
     *
     * @param inflater           the LayoutInflater object
     * @param container          the parent view group
     * @param savedInstanceState previously saved instance state
     * @return the root view of the fragment
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_offer_detail, container, false);
    }

    /**
     * Initializes UI elements and populates them with the selected offer's details.
     * If the offer belongs to the current user, a button is shown to activate/deactivate it.
     *
     * @param view               the fragment's root view
     * @param savedInstanceState previously saved instance state
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String offerId = getArguments() != null ? getArguments().getString("offerId") : null;
        if (offerId == null) return;

        List<Offer> offers = OfferManager.loadOffers(requireContext());
        Offer offer = OfferManager.getOfferById(offers, offerId);
        if (offer == null) return;

        TextView titleView = view.findViewById(R.id.detailTitle);
        TextView descriptionView = view.findViewById(R.id.detailDescription);
        TextView nameView = view.findViewById(R.id.detailName);
        TextView floorView = view.findViewById(R.id.detailFloor);
        TextView phoneView = view.findViewById(R.id.detailContactPhone);
        TextView emailView = view.findViewById(R.id.detailContactEmail);

        titleView.setText(offer.getTitle());
        descriptionView.setText(offer.getDescription());

        List<UserProfile> peers = PeerManager.loadPeers(requireContext());
        UserProfile creator = peers.stream().filter(peer -> peer.getId().equals(offer.getCreatorId())).findFirst().orElse(null);

        if (creator != null) {
            nameView.setText(creator.getName() != null ? creator.getName() : getString(R.string.unknown_user));
            floorView.setText(creator.getFloor() != null ? creator.getFloor() : getString(R.string.unknown_floor));

            if (creator.getPhone() != null && !creator.getPhone().isEmpty()) {
                phoneView.setText(creator.getPhone());
                phoneView.setVisibility(View.VISIBLE);
                phoneView.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_DIAL);
                    intent.setData(Uri.parse("tel:" + creator.getPhone()));
                    startActivity(intent);
                });
            } else {
                phoneView.setText(R.string.contact_unknown);
            }

            if (creator.getEmail() != null && !creator.getEmail().isEmpty()) {
                emailView.setText(creator.getEmail());
                emailView.setVisibility(View.VISIBLE);
                emailView.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_SENDTO);
                    intent.setData(Uri.parse("mailto:" + creator.getEmail()));
                    startActivity(intent);
                });
            } else {
                emailView.setText(R.string.contact_unknown);
            }
        }

        Button deactivateButton = view.findViewById(R.id.buttonDeactivate);

        String currentUserId = UserProfile.loadFromFile(requireContext()).getId();
        boolean isOwnOffer = currentUserId.equals(offer.getCreatorId());

        if (isOwnOffer) {
            deactivateButton.setVisibility(View.VISIBLE);
            updateButtonLabel(deactivateButton, offer.getStatus());

            deactivateButton.setOnClickListener(v -> {
                Constants.OfferStatus newStatus = (offer.getStatus() == Constants.OfferStatus.ACTIVE)
                        ? Constants.OfferStatus.INACTIVE
                        : Constants.OfferStatus.ACTIVE;

                offer.setStatus(newStatus);
                offer.setLastModified(System.currentTimeMillis());

                List<Offer> allOffers = OfferManager.loadOffers(requireContext());
                OfferManager.updateOrAdd(allOffers, offer);
                OfferManager.saveOffers(requireContext(), allOffers);

                updateButtonLabel(deactivateButton, newStatus);
            });
        }
    }

    /**
     * Updates the deactivate/reactivate button label and color based on offer status.
     *
     * @param button the button to update
     * @param status the current status of the offer
     */
    private void updateButtonLabel(Button button, Constants.OfferStatus status) {
        if (status == Constants.OfferStatus.ACTIVE) {
            button.setText("Angebot deaktivieren");
            button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.button_red));
        } else {
            button.setText("Angebot reaktivieren");
            button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.button_green));
        }
    }

}
