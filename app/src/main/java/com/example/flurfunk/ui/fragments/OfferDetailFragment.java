package com.example.flurfunk.ui.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
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

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * A fragment that displays detailed information about a selected {@link Offer}.
 * <p>
 * Shows title, description, date, creator contact info (phone/email),
 * and if the current user is the creator, allows them to deactivate or hide the offer.
 * <p>
 * Hiding an offer removes it from the user's own list permanently.
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
     * Populates the fragment with the offer's data and sets up interaction logic.
     * <p>
     * Retrieves the offer by ID passed via fragment arguments and displays:
     * <ul>
     *     <li>Offer title and description</li>
     *     <li>Creation date</li>
     *     <li>Creator name, floor, phone and email</li>
     * </ul>
     * If the viewer is the offer's creator, they can:
     * <ul>
     *     <li>Temporarily deactivate/reactivate the offer</li>
     *     <li>Permanently hide it from their own list</li>
     * </ul>
     *
     * @param view               the root view returned by {@link #onCreateView}
     * @param savedInstanceState optional saved state
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
        TextView createdAtView = view.findViewById(R.id.detailCreatedAt);

        TextView annotationView = view.findViewById(R.id.detailAnnotation);
        String htmlText = "Wenn du <b>Angebot deaktivieren</b> klickst, wird das Angebot in der entsprechenden Kategorie nicht mehr angezeigt. Du kannst es aber jederzeit reaktivieren. \n\nWenn du <b>Angebot ausblenden</b> klickst verschwindet es auch aus deiner eigenen Liste. Diese Aktion kannst du nicht rückgängig machen.";
        annotationView.setText(Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY));
        annotationView.setVisibility(View.GONE);

        titleView.setText(offer.getTitle());
        descriptionView.setText(offer.getDescription());
        DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);
        String createdAtFormatted = dateFormat.format(new Date(offer.getCreatedAt()));
        createdAtView.setText(createdAtFormatted);

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
        Button hideButton = view.findViewById(R.id.buttonHide);

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

            hideButton.setVisibility(View.VISIBLE);

            hideButton.setOnClickListener(v -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Angebot ausblenden")
                        .setMessage("Willst du dieses Angebot wirklich aus deiner Liste entfernen? Diese Aktion kannst du nicht rückgängig machen.")
                        .setPositiveButton("Ja", (dialog, which) -> {
                            offer.setStatus(Constants.OfferStatus.INACTIVE);
                            offer.setDeleted(true);
                            offer.setLastModified(System.currentTimeMillis());

                            List<Offer> allOffers = OfferManager.loadOffers(requireContext());
                            OfferManager.updateOrAdd(allOffers, offer);
                            OfferManager.saveOffers(requireContext(), allOffers);

                            requireActivity().onBackPressed();
                        })
                        .setNegativeButton("Nein", null)
                        .show();
            });

            annotationView.setVisibility(View.VISIBLE);
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
