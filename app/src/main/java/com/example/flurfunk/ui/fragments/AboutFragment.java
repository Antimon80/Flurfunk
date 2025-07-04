package com.example.flurfunk.ui.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.flurfunk.R;

/**
 * Fragment displaying information about the Flurfunk application.
 * <p>
 * This screen includes contact information and external project links,
 * such as the developer's email address and the GitHub repository.
 * <p>
 * The email field is automatically linkified, and the GitHub link opens in a browser.
 */
public class AboutFragment extends Fragment {

    /**
     * Inflates the layout for the About screen.
     *
     * @param inflater           the LayoutInflater used to inflate views
     * @param container          the parent view that the fragment's UI should attach to
     * @param savedInstanceState optional saved instance state
     * @return the root view of the inflated layout
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    /**
     * Initializes the UI components of the About screen after the view has been created.
     * <p>
     * This includes:
     * <ul>
     *     <li>Linkifying the email address</li>
     *     <li>Setting a click listener on the GitHub link</li>
     * </ul>
     *
     * @param view               the root view returned by {@link #onCreateView}
     * @param savedInstanceState optional saved instance state
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView emailTextView = view.findViewById(R.id.aboutEmail);
        Linkify.addLinks(emailTextView, Linkify.EMAIL_ADDRESSES);
        emailTextView.setMovementMethod(LinkMovementMethod.getInstance());
        emailTextView.setLinkTextColor(ContextCompat.getColor(requireContext(), R.color.purple_500));

        TextView githubTextView = view.findViewById(R.id.aboutGithub);
        githubTextView.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Antimon80/Flurfunk.git"));
            startActivity(intent);
        });
    }

}
