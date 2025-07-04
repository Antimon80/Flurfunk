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

public class AboutFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

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
