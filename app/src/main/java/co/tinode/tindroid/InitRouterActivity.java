package co.tinode.tindroid;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tinodesdk.Tinode;

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
        final String topicName = readTopicName(source);
        final int seqId = readMessageSeq(source);

        Intent launch;
        if (dbReady && !TextUtils.isEmpty(topicName)) {
            launch = new Intent(this, MessageActivity.class);
            launch.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
            if (seqId > 0) {
                launch.putExtra(Const.INTENT_EXTRA_SEQ, seqId);
            }
        } else {
            launch = new Intent(this, dbReady ? ChatsActivity.class : LoginActivity.class);
            if (!TextUtils.isEmpty(topicName)) {
                launch.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
            }
            if (seqId > 0) {
                launch.putExtra(Const.INTENT_EXTRA_SEQ, seqId);
            }
        }
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
        finish();
    }

    private static String readTopicName(Intent intent) {
        String topicName = intent.getStringExtra(Const.INTENT_EXTRA_TOPIC);
        if (TextUtils.isEmpty(topicName)) {
            topicName = intent.getStringExtra("topic");
        }
        if (TextUtils.isEmpty(topicName) && intent.getData() != null) {
            topicName = Tinode.parseTinodeUrl(intent.getDataString());
        }
        return topicName;
    }

    private static int readMessageSeq(Intent intent) {
        int seqId = intent.getIntExtra(Const.INTENT_EXTRA_SEQ, 0);
        if (seqId <= 0) {
            seqId = intent.getIntExtra("seq", 0);
        }
        if (seqId > 0) {
            return seqId;
        }

        String seqStr = intent.getStringExtra(Const.INTENT_EXTRA_SEQ);
        if (TextUtils.isEmpty(seqStr)) {
            seqStr = intent.getStringExtra("seq");
        }
        if (TextUtils.isEmpty(seqStr)) {
            return 0;
        }

        try {
            return Integer.parseInt(seqStr);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
