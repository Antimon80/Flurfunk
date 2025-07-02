package com.example.flurfunk.model;

import com.example.flurfunk.util.Constants.*;

import java.util.UUID;

/**
 * Represents an offer in the Flurfunk application.
 * An offer contains a description, category, creator identifier, timestamps,
 * and a status indicating whether the offer is active or inactive.
 * <p>
 * Each offer has a unique ID and supports timestamp updates and merging with
 * newer versions of itself.
 */
public class Offer {

    private String offerId;
    private String title;
    private String description;
    private Category category;
    private String creatorId;
    private final long createdAt;
    private long lastModified;
    private OfferStatus status;

    /**
     * Default constructor used for deserialization.
     * Initializes creation and modification timestamps.
     */
    public Offer() {
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
    }

    /**
     * Constructs a new Offer with the specified description, category, and creator ID.
     * Generates a unique offer ID and sets initial timestamps.
     *
     * @param title       the title of the offer
     * @param description the description of the offer
     * @param category    the category of the offer
     * @param creatorId   the ID of the user who created the offer
     */
    public Offer(String title, String description, Category category, String creatorId) {
        this.offerId = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.category = category;
        this.creatorId = creatorId;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = this.createdAt;
        this.status = OfferStatus.ACTIVE;
    }

    public String getOfferId() {
        return offerId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastModified() {
        return lastModified;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public void setOfferId(String offerId) {
        this.offerId = offerId;
    }

    public void setTitle(String title) {
        this.title = title;
        updateTimestamp();
    }

    public void setDescription(String description) {
        this.description = description;
        updateTimestamp();
    }


    public void setCategory(Category category) {
        this.category = category;
        updateTimestamp();
    }

    public void setCreatorId(String creatorId) {
        this.creatorId = creatorId;
    }

    public void setStatus(OfferStatus status) {
        this.status = status;
        updateTimestamp();
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Updates the last modified timestamp to the current system time.
     */
    private void updateTimestamp() {
        this.lastModified = System.currentTimeMillis();
    }

    /**
     * Merges another offer into this one if the IDs match and the other offer is newer.
     * Updates title, description, category, status, and lastModified.
     *
     * @param other the other {@code Offer} to merge from
     */
    public void merge(Offer other) {
        if (!this.offerId.equals(other.offerId)) return;
        if (other.lastModified > this.lastModified) {
            this.title = other.title;
            this.description = other.description;
            this.category = other.category;
            this.status = other.status;
            this.lastModified = other.lastModified;
        }
    }
}
