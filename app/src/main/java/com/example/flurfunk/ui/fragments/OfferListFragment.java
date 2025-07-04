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
import java.util.stream.Collectors;

/**
 * A fragment that displays a list of offers filtered by category.
 * <p>
 * If the category is set to {@code MY_OFFERS}, it shows only the current user's own offers.
 * For all other categories, it displays active offers only.
 * <p>
 * The fragment includes a floating action button to open the offer creation screen.
 */
public class OfferListFragment extends Fragment {

    private static final String CATEGORY = "category";
    private Category category;

    /**
     * Reads the category from the fragment arguments and sets a default if invalid or missing.
     *
     * @param savedInstanceState previously saved instance state (unused)
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
     * Inflates the layout, sets up the RecyclerView with filtered offers,
     * and handles the creation button for new offers.
     *
     * @param inflater           the layout inflater
     * @param container          the parent view group
     * @param savedInstanceState previously saved instance state (unused)
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
     * Called when the fragment becomes visible to the user.
     * <p>
     * Reloads the list of offers and updates the RecyclerView.
     * If there are no offers for the current category, a placeholder text is shown instead.
     * This ensures that newly created or deleted offers are reflected immediately
     * after returning from the offer creation screen.
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
