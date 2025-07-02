package com.example.flurfunk.ui.fragments;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
        TextView githubTextView = view.findViewById(R.id.aboutGithub);

        emailTextView.setMovementMethod(LinkMovementMethod.getInstance());
        githubTextView.setMovementMethod(LinkMovementMethod.getInstance());
    }

}
