package co.tinode.tindroid;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import co.tinode.tindroid.db.BaseDb;

/**
 * Splash screen on startup
 */
public class InitRouterActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // On SDK 35+, edge-to-edge is enforced by default.
        // Only call EdgeToEdge.enable() on older versions to avoid deprecated API warnings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            EdgeToEdge.enable(this);
        }
        super.onCreate(savedInstanceState);

        // No need to check for live connection here.

        // Send user to appropriate screen:
        // 1. If we have an account and no credential validation is needed, send to ChatsActivity.
        // 2. If we don't have an account or credential validation is required send to LoginActivity.
        final Intent source = getIntent();
        final boolean dbReady = BaseDb.getInstance().isReady();

        Intent launch;
        if (dbReady) {
            launch = UiUtils.createPostLoginIntent(this, source);
        } else {
            launch = new Intent(this, LoginActivity.class);
            UiUtils.copyLaunchTopicExtras(source, launch);
        }
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
        finish();
    }
}
