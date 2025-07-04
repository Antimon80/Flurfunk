package com.example.flurfunk.ui.fragments;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.flurfunk.R;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.ui.OfferListAdapter;
import com.example.flurfunk.model.Offer;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.ui.activities.CreateOfferActivity;
import com.example.flurfunk.util.Constants;
import com.example.flurfunk.util.Constants.Category;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Collections;
import java.util.List;

/**
 * A fragment that displays a list of offers filtered by {@link Constants.Category}.
 * <p>
 * If the category is {@code MY_OFFERS}, only offers created by the current user are shown.
 * Otherwise, the fragment shows all active, non-deleted offers within the selected category.
 * <p>
 * A floating action button allows users to create new offers in the current category.
 * The list updates automatically when the fragment is resumed.
 */
public class OfferListFragment extends Fragment {

    private static final String CATEGORY = "category";
    private Category category;

    /**
     * Reads the category to display from the fragment arguments.
     * <p>
     * If the argument is missing or invalid, {@code ELECTRONICS} is used as the default.
     *
     * @param savedInstanceState unused, as this fragment does not use saved state
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null && getArguments().containsKey(CATEGORY)) {
            try {
                category = Category.valueOf(getArguments().getString(CATEGORY));
            } catch (IllegalArgumentException e) {
                category = Category.ELECTRONICS;
            }
        } else {
            category = Category.ELECTRONICS;
        }
    }

    /**
     * Inflates the offer list layout and sets up the RecyclerView.
     * <p>
     * Offers are filtered based on the selected category:
     * <ul>
     *     <li>{@code MY_OFFERS} → only user's own offers (excluding deleted)</li>
     *     <li>Other categories → only active, non-deleted offers in the selected category</li>
     * </ul>
     * If the filtered list is empty, a placeholder text is shown.
     * The floating action button opens the {@link CreateOfferActivity}.
     *
     * @param inflater           the layout inflater
     * @param container          the parent view group
     * @param savedInstanceState unused
     * @return the root view of the fragment
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_offer_list, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerViewOffers);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        TextView emptyView = view.findViewById(R.id.emptyView);

        List<Offer> offers = OfferManager.loadOffers(requireContext());
        if (category == Category.MY_OFFERS) {
            String myId = UserProfile.loadFromFile(requireContext()).getId();
            offers = OfferManager.filterByCreator(offers, myId);
            offers = OfferManager.filterByDeletion(offers, false);
        } else if (category != null) {
            offers = OfferManager.filterByCategory(offers, category);
            offers = OfferManager.filterByStatus(offers, Constants.OfferStatus.ACTIVE);
            offers = OfferManager.filterByDeletion(offers, false);
        } else {
            offers = Collections.emptyList();
        }

        recyclerView.setAdapter(new OfferListAdapter(offers, requireContext(), category));

        if (offers.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        FloatingActionButton fab = view.findViewById(R.id.fabCreateOffer);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CreateOfferActivity.class);
            intent.putExtra("category", category.name());
            startActivity(intent);
        });

        return view;
    }

    /**
     * Reloads the list of offers when the fragment becomes visible again.
     * <p>
     * This ensures that new or changed offers (e.g., created, deleted, deactivated)
     * are reflected in the UI without requiring a full restart.
     * <p>
     * If the offer list is empty after filtering, a placeholder message is shown.
     */
    @Override
    public void onResume() {
        super.onResume();

        RecyclerView recyclerView = requireView().findViewById(R.id.recyclerViewOffers);
        TextView emptyView = requireView().findViewById(R.id.emptyView);

        List<Offer> offers = OfferManager.loadOffers(requireContext());
        if (category == Category.MY_OFFERS) {
            String myId = UserProfile.loadFromFile(requireContext()).getId();
            offers = OfferManager.filterByCreator(offers, myId);
            offers = OfferManager.filterByDeletion(offers, false);
        } else if (category != null) {
            offers = OfferManager.filterByCategory(offers, category);
            offers = OfferManager.filterByStatus(offers, Constants.OfferStatus.ACTIVE);
            offers = OfferManager.filterByDeletion(offers, false);
        } else {
            offers = Collections.emptyList();
        }

        recyclerView.setAdapter(new OfferListAdapter(offers, requireContext(), category));

        if (offers.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }
}
