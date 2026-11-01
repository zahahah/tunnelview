package com.zahah.tunnelview.testfixtures;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Simulates approval in another task without funds, accounts or a third-party service. */
public class ExternalApprovalActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("External approval test");
        setContentView(label);
        approve();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        approve();
    }
    private void approve() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> moveTaskToBack(true), 700);
    }
}
