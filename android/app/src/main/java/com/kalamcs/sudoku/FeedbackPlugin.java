package com.kalamcs.sudoku;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SudokuFeedback")
public class FeedbackPlugin extends Plugin {
    @PluginMethod
    public void openEmail(PluginCall call) {
        String recipient = call.getString("recipient", "");
        String subject = call.getString("subject", "");
        String body = call.getString("body", "");
        if (recipient.isEmpty()) {
            call.reject("No feedback email address is configured.");
            return;
        }

        Uri mailto = Uri.parse("mailto:" + Uri.encode(recipient, "@") +
            "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body));
        Intent intent = new Intent(Intent.ACTION_SENDTO, mailto);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] { recipient });
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, body);

        getActivity().runOnUiThread(() -> {
            try {
                getActivity().startActivity(intent);
                call.resolve();
            } catch (ActivityNotFoundException error) {
                call.reject("No email app is available.", "NO_EMAIL_APP", error);
            }
        });
    }
}
