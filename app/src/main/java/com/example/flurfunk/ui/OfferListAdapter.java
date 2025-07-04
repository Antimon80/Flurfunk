package com.example.flurfunk.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.example.flurfunk.R;
import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Constants;

import java.util.List;

/**
 * RecyclerView adapter for displaying a list of offers in the offer list screen.
 * <p>
 * Each list item shows the offer's title, creator name, floor, and contact information.
 * Clicking on an item navigates to the {@code OfferDetailFragment} to show full details.
 */
public class OfferListAdapter extends RecyclerView.Adapter<OfferListAdapter.OfferViewHolder> {
    private final List<Offer> offers;
    private final Context context;
    private Constants.Category category;

    /**
     * Constructs a new {@code OfferListAdapter}.
     *
     * @param offers  the list of offers to display
     * @param context the context used to access resources and inflate views
     */
    public OfferListAdapter(List<Offer> offers, Context context, Constants.Category category) {
        this.offers = offers;
        this.context = context;
        this.category = category;
    }

    /**
     * Inflates the layout for a single offer item.
     *
     * @param parent   the parent view group
     * @param viewType the view type (unused)
     * @return a new {@link OfferViewHolder}
     */
    @NonNull
    @Override
    public OfferViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_offer, parent, false);
        return new OfferViewHolder(view);
    }

    /**
     * Binds an offer to a view holder, displaying offer and creator information.
     *
     * @param holder   the view holder to bind data to
     * @param position the position of the offer in the list
     */
    @Override
    public void onBindViewHolder(@NonNull OfferViewHolder holder, int position) {
        Offer offer = offers.get(position);
        holder.titleText.setText(offer.getTitle());

        UserProfile creator = PeerManager.getPeerById(context, offer.getCreatorId());
        if (creator != null) {
            holder.nameText.setText(creator.getName() != null ? creator.getName() : context.getString(R.string.unknown_offerer));
            holder.floorText.setText(creator.getFloor() != null ? context.getString(R.string.floor_label, creator.getFloor()) : "");

            String contactText;
            if (creator.getPhone() != null && !creator.getPhone().isEmpty()) {
                contactText = context.getString(R.string.phone_label, creator.getPhone());
            } else if (creator.getEmail() != null && !creator.getEmail().isEmpty()) {
                contactText = context.getString(R.string.email_label, creator.getEmail());
            } else {
                contactText = context.getString(R.string.no_contact);
            }

            holder.contactText.setText(contactText);
        } else {
            holder.nameText.setText(context.getString(R.string.unknown_offerer));
            holder.floorText.setText("");
            holder.contactText.setText("");
        }

        holder.itemView.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString("offerId", offer.getOfferId());
            Navigation.findNavController(v).navigate(R.id.nav_offer_detail, args);
        });

        if (category == Constants.Category.MY_OFFERS) {
            if (offer.getStatus() == Constants.OfferStatus.ACTIVE) {
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.offer_green));
            } else {
                holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.offer_red));
            }
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    /**
     * @return the total number of offers in the list
     */
    @Override
    public int getItemCount() {
        return offers.size();
    }

    /**
     * View holder for a single offer item in the list.
     */
    static class OfferViewHolder extends RecyclerView.ViewHolder {
        TextView titleText, nameText, floorText, contactText;

        /**
         * Constructs a new {@code OfferViewHolder} and initializes all views.
         *
         * @param itemView the item layout view
         */
        public OfferViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.textTitle);
            nameText = itemView.findViewById(R.id.textName);
            floorText = itemView.findViewById(R.id.textFloor);
            contactText = itemView.findViewById((R.id.textContact));
        }
    }

}
