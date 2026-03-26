package co.tinode.tindroid;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;
import co.tinode.tindroid.account.ContactsManager;
import co.tinode.tindroid.account.Utils;
import co.tinode.tindroid.db.BaseDb;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.AlreadySubscribedException;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.NotConnectedException;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.ServerResponseException;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Description;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.MsgRange;
import co.tinode.tinodesdk.model.MsgServerData;
import co.tinode.tinodesdk.model.MsgServerInfo;
import co.tinode.tinodesdk.model.MsgServerPres;
import co.tinode.tinodesdk.model.PrivateType;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.Subscription;

/**
 * View to display a single conversation
 */
public class MessageActivity extends BaseActivity
        implements ImageViewFragment.AvatarCompletionHandler {
    private static final String TAG = "MessageActivity";

    static final String FRAGMENT_MESSAGES = "msg";
    static final String FRAGMENT_INVALID = "invalid";
    static final String FRAGMENT_INFO = "info";
    static final String FRAGMENT_GENERAL = "general";
    static final String FRAGMENT_PERMISSIONS = "permissions";
    static final String FRAGMENT_EDIT_MEMBERS = "edit_members";
    static final String FRAGMENT_VIEW_IMAGE = "view_image";
    static final String FRAGMENT_VIEW_VIDEO = "view_video";
    static final String FRAGMENT_FILE_PREVIEW = "file_preview";
    static final String FRAGMENT_AVATAR_PREVIEW = "avatar_preview";
    static final String FRAGMENT_FORWARD_TO = "forward_to";

    static final String TOPIC_NAME = "topicName";
    static final String PENDING_TOPIC_NAME = "pendingTopicName";
    static final String OPEN_MESSAGE_SEQ = "openMessageSeq";
    private static final String INTENT_EXTRA_LAUNCH_HANDLED = "co.tinode.tindroid.LAUNCH_HANDLED";

    private static final int MESSAGES_TO_LOAD = 24;

    private static final int READ_DELAY = 1000;

    // How long a typing indicator should play its animation, milliseconds.
    private static final int TYPING_INDICATOR_DURATION = 4000;

    private final BroadcastReceiver onNotificationClick = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            // FIXME: handle notification click.
            Log.d(TAG, "onNotificationClick" + intent.getExtras());
        }
    };
    final BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (dm == null || downloadId == -1) {
                return;
            }

            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor c = dm.query(query);
            if (c.moveToFirst()) {
                int idx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int status = idx >=0 ? c.getInt(idx) : -1;
                if (DownloadManager.STATUS_SUCCESSFUL == status) {
                    idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    URI fileUri = idx >= 0 ? URI.create(c.getString(idx)) : null;
                    idx = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                    String mimeType = idx >= 0 ? c.getString(idx) : null;
                    if (fileUri != null) {
                        Intent intent2 = new Intent();
                        intent2.setAction(android.content.Intent.ACTION_VIEW);
                        intent2.setDataAndType(FileProvider.getUriForFile(MessageActivity.this,
                                "co.tinode.tindroid.provider", new File(fileUri)), mimeType);
                        intent2.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(intent2);
                        } catch (ActivityNotFoundException ignored) {
                            Log.w(TAG, "No application can view downloaded file");
                            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                        }
                    }
                } else if (DownloadManager.STATUS_FAILED == status) {
                    idx = c.getColumnIndex(DownloadManager.COLUMN_REASON);
                    int reason = idx >= 0 ? c.getInt(idx) : -1;
                    Log.w(TAG, "Download failed. Reason: " + reason);
                    Toast.makeText(MessageActivity.this,
                            R.string.failed_to_download, Toast.LENGTH_SHORT).show();
                }
            }
            c.close();
        }
    };

    private final ActivityResultLauncher<String[]> mRequestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                for (Map.Entry<String,Boolean> e : result.entrySet()) {
                    // Check if all required permissions are granted.
                    if (!e.getValue()) {
                        return;
                    }
                }
                // Permissions are granted.
                UiUtils.onContactsPermissionsGranted(this);
            });

    private final LinkedHashMap<String, String> mTypingUsers = new LinkedHashMap<>();
    private final Map<String, Runnable> mTypingExpirationTasks = new LinkedHashMap<>();
    private String mMessageText = null;
    private PausableSingleThreadExecutor mMessageSender = null;
    private String mTopicName = null;
    private String mPendingTopicName = null;
    private ComTopic<VxCard> mTopic = null;
    private TListener mTopicEventListener;
    private LoginEventListener mTinodeListener;
    // Handler for sending {note what="read"} notifications after a READ_DELAY.
    private Handler mNoteReadHandler = null;
    private static final int NOTE_READ_ID = 1;
    // Notification settings.
    private boolean mSendTypingNotifications = false;
    private boolean mSendReadReceipts = false;
    private int mOpenMessageSeq = 0;

    // Only for grp topics:
    // Keeps track of the known subscriptions for the given topic.
    private Set<String> mKnownSubs = null;
    // True when new subscriptions were added to the topic.
    private boolean mNewSubsAvailable = false;
    // Tracker of pinned changes to prevent endless loop.
    private int mPinHash = -1;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mTopicName = savedInstanceState.getString(TOPIC_NAME);
            mPendingTopicName = savedInstanceState.getString(PENDING_TOPIC_NAME);
            mOpenMessageSeq = savedInstanceState.getInt(OPEN_MESSAGE_SEQ, 0);
        }

        UiUtils.setupSystemToolbar(this);

        setContentView(R.layout.activity_messages);
        applyEdgeToEdgeInsets(findViewById(android.R.id.content));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
        }
        toolbar.setOnClickListener(v -> openTopicDetails());
        toolbar.setNavigationOnClickListener(v -> {
            if (isFragmentVisible(FRAGMENT_MESSAGES) || isFragmentVisible(FRAGMENT_INVALID)) {
                Intent intent = new Intent(MessageActivity.this, ChatsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } else if (isFragmentVisible(FRAGMENT_FORWARD_TO)) {
                getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                showFragment(FRAGMENT_MESSAGES, null, false);
            } else {
                getSupportFragmentManager().popBackStack();
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(onNotificationClick,
                    new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            registerReceiver(onNotificationClick, new IntentFilter(DownloadManager.ACTION_NOTIFICATION_CLICKED));
        }

        mMessageSender = new PausableSingleThreadExecutor();
        mMessageSender.pause();

        final Tinode tinode = Cache.getTinode();
        mTinodeListener = new LoginEventListener(tinode.isConnected());
        tinode.addListener(mTinodeListener);

        mNoteReadHandler = new NoteHandler(this);
        captureLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);

        setIntent(intent);
        captureLaunchIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Intent with parameters passed on start of the activity.
        final Intent intent = getIntent();
        captureLaunchIntent(intent);

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        mMessageText = TextUtils.isEmpty(text) ? null : text.toString();
        intent.putExtra(Intent.EXTRA_TEXT, (String) null);

        String topicName = !TextUtils.isEmpty(mPendingTopicName) ? mPendingTopicName : mTopicName;
        if (TextUtils.isEmpty(topicName)) {
            topicName = readTopicNameFromIntent(intent);
        }

        if (!changeTopic(topicName, false)) {
            Cache.setSelectedTopicName(null);
            finish();
            return;
        }
        mPendingTopicName = null;

        if (mOpenMessageSeq > 0) {
            getWindow().getDecorView().post(this::openPendingMessageIfPossible);
        }

        // Resume message sender.
        mMessageSender.resume();

        Uri attachment = intent.getData();
        String type = intent.getType();
        if (attachment != null && type != null && !Utils.MIME_TINODE_PROFILE.equals(type)) {
            // Need to retain access right to the given Uri.
            Bundle args = new Bundle();
            args.putParcelable(AttachmentHandler.ARG_LOCAL_URI, attachment);
            args.putString(AttachmentHandler.ARG_MIME_TYPE, type);
            if (type.startsWith("image/")) {
                args.putString(AttachmentHandler.ARG_IMAGE_CAPTION, mMessageText);
                showFragment(FRAGMENT_VIEW_IMAGE, args, true);
            } else if (type.startsWith("video/")) {
                args.putString(AttachmentHandler.ARG_IMAGE_CAPTION, mMessageText);
                showFragment(FRAGMENT_VIEW_VIDEO, args, true);
            } else {
                showFragment(FRAGMENT_FILE_PREVIEW, args, true);
            }
        }
        intent.setData(null);

        final SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);

        mSendReadReceipts = pref.getBoolean(Const.PREF_READ_RCPT, true);
        mSendTypingNotifications = pref.getBoolean(Const.PREF_TYPING_NOTIF, true);

        if (mTopic != null) {
            BaseDb.getInstance().getStore().msgPruneFailed(mTopic);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(TOPIC_NAME, mTopicName);
        outState.putString(PENDING_TOPIC_NAME, mPendingTopicName);
        outState.putInt(OPEN_MESSAGE_SEQ, mOpenMessageSeq);
    }

    // Topic has changed. Update all the views with the new data.
    // Returns 'true' if topic was successfully changed, false otherwise.
    boolean changeTopic(String topicName, boolean forceReset) {
        if (TextUtils.isEmpty(topicName)) {
            Log.w(TAG, "Failed to switch topics: empty topic name");
            return false;
        }

        // Cancel all pending notifications addressed to the current topic.
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(topicName, 0);
        }

        boolean changed = false;
        if (mTopicName == null || !mTopicName.equals(topicName) || forceReset) {
            final Tinode tinode = Cache.getTinode();
            ComTopic<VxCard> topic;
            try {
                //noinspection unchecked
                topic = (ComTopic<VxCard>) tinode.getTopic(topicName);
            } catch (ClassCastException ex) {
                Log.w(TAG, "Failed to switch topics: non-comm topic");
                return false;
            }

            changed = true;
            if (mTopic != null) {
                topicDetach(mTopic);
            }

            mTopic = topic;

            Cache.setSelectedTopicName(topicName);
            mTopicName = topicName;
            clearTypingIndicators(false);

            mPinHash = -1;
            if (mTopic == null) {
                try {
                    // noinspection unchecked
                    mTopic = (ComTopic<VxCard>) tinode.newTopic(mTopicName, null);
                } catch (ClassCastException ex) {
                    Log.w(TAG, "New topic is a non-comm topic: " + mTopicName);
                    return false;
                }
                refreshToolbar();
                showFragment(FRAGMENT_INVALID, null, false);

                // Check if another fragment is already visible. If so, don't change it.
            } else if (forceReset || UiUtils.getVisibleFragment(getSupportFragmentManager()) == null) {
                refreshToolbar();

                // Reset requested or no fragment is visible. Show default and clear back stack.
                getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                showFragment(FRAGMENT_MESSAGES, null, false);
            }
        }

        mNewSubsAvailable = false;
        mKnownSubs = new HashSet<>();

        if (mTopic == null) {
            return true;
        }

        if (mTopic.isGrpType()) {
            Collection<Subscription<VxCard, PrivateType>> subs = mTopic.getSubscriptions();
            if (subs != null) {
                for (Subscription<VxCard, PrivateType> sub : subs) {
                    if (sub.user != null) {
                        mKnownSubs.add(sub.user);
                    }
                }
            }
        }

        if (mTopicEventListener == null) {
            mTopicEventListener = new TListener();
        }
        mTopic.addListener(mTopicEventListener);

        // Try immediate reconnect (increment attachment counter).
        if (!mTopic.isAttached()) {
            topicAttach();
        }

        MessagesFragment fragmsg = (MessagesFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_MESSAGES);
        if (fragmsg != null) {
            fragmsg.topicChanged(topicName, forceReset || changed);
        }

        return true;
    }

    // Get topic name from Intent the Activity was launched with (push notification, other app, other activity).
    private String readTopicNameFromIntent(Intent intent) {
        String name = UiUtils.readTopicNameFromLaunchIntent(intent);
        if (!TextUtils.isEmpty(name)) {
            return name;
        }

        Uri dataUri = intent.getData();
        if (dataUri != null) {
            // mTopicName is empty, so this is an external intent
            Uri contactUri = dataUri;
            Cursor cursor = null;
            if (TextUtils.equals("content", contactUri.getScheme()) &&
                    UiUtils.isPermissionGranted(this, Manifest.permission.READ_CONTACTS)) {
                cursor = getContentResolver().query(contactUri,
                        new String[]{Utils.DATA_PID}, null, null, null);
            } else if (TextUtils.equals("content", contactUri.getScheme())) {
                mRequestPermissionLauncher.launch(new String[]{Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS});
            }

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(Utils.DATA_PID);
                    if (idx >= 0) {
                        name = cursor.getString(idx);
                    }
                }
                cursor.close();
            }
        }

        return name;
    }

    private void captureLaunchIntent(@Nullable Intent intent) {
        if (intent == null || intent.getBooleanExtra(INTENT_EXTRA_LAUNCH_HANDLED, false)) {
            return;
        }

        String topicName = readTopicNameFromIntent(intent);
        if (TextUtils.isEmpty(topicName)) {
            return;
        }

        mPendingTopicName = topicName;
        int seqId = readMessageSeqFromIntent(intent);
        if (seqId > 0) {
            mOpenMessageSeq = seqId;
        }
        intent.putExtra(INTENT_EXTRA_LAUNCH_HANDLED, true);
    }

    private int readMessageSeqFromIntent(@NonNull Intent intent) {
        return UiUtils.readMessageSeqFromLaunchIntent(intent);
    }

    private void openPendingMessageIfPossible() {
        if (mOpenMessageSeq <= 0) {
            return;
        }

        MessagesFragment fragmsg = (MessagesFragment) getSupportFragmentManager()
                .findFragmentByTag(FRAGMENT_MESSAGES);
        if (fragmsg != null && fragmsg.scrollToMessage(mOpenMessageSeq)) {
            mOpenMessageSeq = 0;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mMessageSender.pause();
        // Stop handling read messages
        mNoteReadHandler.removeMessages(NOTE_READ_ID);
    }

    private Fragment maybeShowMessagesFragmentOnAttach() {
        FragmentManager fm = getSupportFragmentManager();
        Fragment visible = UiUtils.getVisibleFragment(fm);
        if (visible instanceof InvalidTopicFragment) {
            // Replace InvalidTopicFragment with default FRAGMENT_MESSAGES.
            fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            showFragment(FRAGMENT_MESSAGES, null, false);
        } else {
            MessagesFragment fragmsg = (MessagesFragment) fm.findFragmentByTag(FRAGMENT_MESSAGES);
            if (fragmsg != null) {
                fragmsg.topicChanged(mTopicName, true);
            }
        }
        return visible;
    }

    private void topicAttach() {
        if (mTopic.isDeleted()) {
            refreshToolbar();
            maybeShowMessagesFragmentOnAttach();
            return;
        }

        if (!Cache.getTinode().isAuthenticated()) {
            // If connection is not ready, wait for completion. This method will be called again
            // from the onLogin callback;
            return;
        }

        setRefreshing(true);
        Topic.MetaGetBuilder builder = mTopic.getMetaGetBuilder()
                .withDesc()
                .withSub()
                .withLaterData(MESSAGES_TO_LOAD)
                .withDel()
                .withAux();

        if (mTopic.isOwner()) {
            builder = builder.withTags();
        }

        mTopic.subscribe(null, builder.build())
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                        if (result.ctrl != null && result.ctrl.code == 303) {
                            // Redirect.
                            changeTopic(result.ctrl.getStringParam("topic", null), false);
                            return null;
                        }
                        runOnUiThread(() -> {
                            Fragment fragment = maybeShowMessagesFragmentOnAttach();
                            if (fragment instanceof MessagesFragment) {
                                refreshToolbar();
                            }
                        });
                        // Submit pending messages for processing: publish queued,
                        // delete marked for deletion.
                        syncAllMessages(true);
                        return null;
                    }
                })
                .thenCatch(new PromisedReply.FailureListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onFailure(Exception err) {
                        if (!(err instanceof NotConnectedException) && !(err instanceof AlreadySubscribedException)) {
                            Log.w(TAG, "Subscribe failed", err);
                            if (err instanceof ServerResponseException) {
                                int code = ((ServerResponseException) err).getCode();
                                if (code == 404) {
                                    showFragment(FRAGMENT_INVALID, null, false);
                                }
                            }
                        }
                        return null;
                    }
                })
                .thenFinally(new PromisedReply.FinalListener() {
                    @Override
                    public void onFinally() {
                        setRefreshing(false);
                    }
                });
    }

    // Clean up everything related to the topic being replaced of removed.
    private void topicDetach(@Nullable ComTopic<VxCard> topic) {
        clearTypingIndicators(false);

        if (topic != null) {
            topic.remListener(mTopicEventListener);
            topic.leave();
        }
    }

    void refreshToolbar() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (mTopic == null) {
            UiUtils.setupToolbar(this, null, mTopicName, false, null, false, 0, null);
            return;
        }

        UiUtils.setupToolbar(this, mTopic.getPub(), mTopic.getName(),
                mTopic.getOnline(), mTopic.getLastSeen(), mTopic.isDeleted(), mTopic.getSubCnt(),
                getTypingIndicatorText());
    }

    @Nullable
    private CharSequence getTypingIndicatorText() {
        if (mTopic == null || mTypingUsers.isEmpty()) {
            return null;
        }

        if (mTopic.isP2PType()) {
            return getString(R.string.typing_short);
        }

        if (mTopic.isGrpType() && !mTopic.isChannel()) {
            return getString(R.string.typing_users, TextUtils.join(", ", mTypingUsers.values()));
        }

        return null;
    }

    private void onRemoteKeyPress(@Nullable String uid) {
        if (mTopic == null || TextUtils.isEmpty(uid) || Cache.getTinode().isMe(uid)) {
            return;
        }

        String label = resolveTypingLabel(uid);
        mTypingUsers.remove(uid);
        mTypingUsers.put(uid, label);

        Runnable oldTask = mTypingExpirationTasks.remove(uid);
        if (oldTask != null) {
            mNoteReadHandler.removeCallbacks(oldTask);
        }

        Runnable newTask = () -> {
            mTypingExpirationTasks.remove(uid);
            if (mTypingUsers.remove(uid) != null) {
                refreshToolbar();
            }
        };
        mTypingExpirationTasks.put(uid, newTask);
        mNoteReadHandler.postDelayed(newTask, TYPING_INDICATOR_DURATION);
        refreshToolbar();
    }

    private void clearTypingIndicator(@Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            return;
        }

        Runnable task = mTypingExpirationTasks.remove(uid);
        if (task != null) {
            mNoteReadHandler.removeCallbacks(task);
        }

        if (mTypingUsers.remove(uid) != null) {
            refreshToolbar();
        }
    }

    private void clearTypingIndicators(boolean refreshToolbar) {
        for (Runnable task : mTypingExpirationTasks.values()) {
            mNoteReadHandler.removeCallbacks(task);
        }
        mTypingExpirationTasks.clear();

        if (!mTypingUsers.isEmpty()) {
            mTypingUsers.clear();
            if (refreshToolbar) {
                refreshToolbar();
            }
        }
    }

    @Nullable
    private String resolveTypingAlias(@Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            return null;
        }

        Topic<?, ?, ?, ?> cached = Cache.getTinode().getTopic(uid);
        if (cached != null) {
            String alias = cached.alias();
            if (!TextUtils.isEmpty(alias)) {
                return alias;
            }
        }

        if (mTopic != null && mTopic.isP2PType() && TextUtils.equals(uid, mTopic.getName())) {
            String alias = mTopic.alias();
            if (!TextUtils.isEmpty(alias)) {
                return alias;
            }
        }

        return null;
    }

    @NonNull
    private String resolveTypingLabel(@Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            return "";
        }

        String alias = resolveTypingAlias(uid);
        if (!TextUtils.isEmpty(alias)) {
            return alias;
        }

        if (mTopic != null) {
            Subscription<VxCard, PrivateType> sub = mTopic.getSubscription(uid);
            if (sub != null && sub.pub != null && !TextUtils.isEmpty(sub.pub.fn)) {
                return sub.pub.fn.trim();
            }
        }

        return uid;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Cache.getTinode().removeListener(mTinodeListener);
        topicDetach(mTopic);
        mTopic = null;
        mTopicName = null;

        UiUtils.setVisibleTopic(null);

        mMessageSender.shutdownNow();
        unregisterReceiver(onDownloadComplete);
        unregisterReceiver(onNotificationClick);
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        UiUtils.setVisibleTopic(hasFocus ? mTopicName : null);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (mTopic == null || !mTopic.isValid()) {
            return false;
        }

        int id = item.getItemId();
        if (id == R.id.action_view_contact) {
            openTopicDetails();
            return true;
        } else if (mTopic != null) {
            if (id == R.id.action_archive) {
                mTopic.updateArchived(true);
                return true;
            } else if (id == R.id.action_unarchive) {
                mTopic.updateArchived(false);
                return true;
            } else if (id == R.id.action_audio_call || id == R.id.action_video_call) {
                try {
                    CallManager.placeOutgoingCall(this, mTopicName, id == R.id.action_audio_call);
                } catch (IllegalStateException | SecurityException | UnsupportedOperationException ex) {
                    Log.w(TAG, "Unable to place outgoing call", ex);
                    Toast.makeText(this, R.string.calling_not_supported, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        } else {
            Toast.makeText(this, R.string.action_failed, Toast.LENGTH_SHORT).show();
        }

        return false;
    }

    private void openTopicDetails() {
        if (mTopic == null || !mTopic.isValid() || !isFragmentVisible(FRAGMENT_MESSAGES)) {
            return;
        }
        showFragment(FRAGMENT_INFO, null, true);
    }

    // Try to send all pending messages.
    public void syncAllMessages(final boolean runLoader) {
        syncMessages(-1, runLoader);
    }

    // Try to send the specified message.
    public void syncMessages(final long msgId, final boolean runLoader) {
        mMessageSender.submit(() -> {
            PromisedReply<ServerMessage> promise;
            if (msgId > 0) {
                promise = mTopic.syncOne(msgId);
            } else {
                promise = mTopic.syncAll();
            }
            if (runLoader) {
                promise.thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                        runMessagesLoader();
                        return null;
                    }
                });
            }
            promise.thenCatch(new PromisedReply.FailureListener<>() {
                @Override
                public PromisedReply<ServerMessage> onFailure(Exception err) {
                    Log.w(TAG, "Sync failed", err);
                    return null;
                }
            });
        });
    }

    private boolean isFragmentVisible(String tag) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        return fragment != null && fragment.isVisible();
    }

    void showFragment(String tag, Bundle args, boolean addToBackstack) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        FragmentManager fm = getSupportFragmentManager();

        Fragment fragment = fm.findFragmentByTag(tag);
        if (fragment == null) {
            switch (tag) {
                case FRAGMENT_MESSAGES:
                    fragment = new MessagesFragment();
                    break;
                case FRAGMENT_INFO:
                    fragment = new TopicInfoFragment();
                    break;
                case FRAGMENT_GENERAL:
                    fragment = new TopicGeneralFragment();
                    break;
                case FRAGMENT_PERMISSIONS:
                    fragment = new TopicSecurityFragment();
                    break;
                case FRAGMENT_EDIT_MEMBERS:
                    fragment = new EditMembersFragment();
                    break;
                case FRAGMENT_VIEW_IMAGE:
                    fragment = new ImageViewFragment();
                    break;
                case FRAGMENT_VIEW_VIDEO:
                    fragment = new VideoViewFragment();
                    break;
                case FRAGMENT_FILE_PREVIEW:
                    fragment = new FilePreviewFragment();
                    break;
                case FRAGMENT_INVALID:
                    fragment = new InvalidTopicFragment();
                    break;
                case FRAGMENT_AVATAR_PREVIEW:
                    fragment = new ImageViewFragment();
                    if (args == null) {
                        args = new Bundle();
                    }
                    args.putBoolean(AttachmentHandler.ARG_AVATAR, true);
                    break;
                case FRAGMENT_FORWARD_TO:
                    fragment = new ForwardToFragment();
                    break;
                default:
                    throw new IllegalArgumentException("Failed to create fragment: unknown tag " + tag);
            }
        } else if (args == null) {
            // Retain old arguments.
            args = fragment.getArguments();
        }

        args = args != null ? args : new Bundle();
        args.putString(Const.INTENT_EXTRA_TOPIC, mTopicName);

        if (tag.equals(FRAGMENT_MESSAGES)) {
            args.putString(MessagesFragment.MESSAGE_TO_SEND, mMessageText);
            mMessageText = null;
        }

        if (fragment.getArguments() != null) {
            fragment.getArguments().putAll(args);
        } else {
            fragment.setArguments(args);
        }

        FragmentTransaction trx = fm.beginTransaction();
        if (!fragment.isAdded()) {
            trx = trx.replace(R.id.contentFragment, fragment, tag)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        } else if (!fragment.isVisible()) {
            trx = trx.show(fragment);
        } else {
            addToBackstack = false;
        }

        if (FRAGMENT_MESSAGES.equals(tag)) {
            trx.setPrimaryNavigationFragment(fragment);
        }

        if (addToBackstack) {
            trx.addToBackStack(tag);
        }
        if (!trx.isEmpty()) {
            trx.commit();
        }
    }

    boolean sendMessage(Drafty content, int seq, boolean isReplacement) {
        return sendMessage(content, seq, isReplacement, null);
    }

    boolean sendMessage(Drafty content, int seq, boolean isReplacement, @Nullable Collection<String> mentions) {
        if (mTopic != null) {
            Map<String,Object> head = seq > 0 ?
                    (isReplacement ? Tinode.headersForReplacement(seq) :
                            Tinode.headersForReply(seq)) :
                    null;
            if (mentions != null) {
                List<String> resolvedMentions = mentions.stream()
                        .filter(mention -> !TextUtils.isEmpty(mention))
                        .distinct()
                        .collect(Collectors.toList());
                if (!resolvedMentions.isEmpty()) {
                    if (head == null) {
                        head = new java.util.HashMap<>();
                    }
                    head.put("mentions", resolvedMentions);
                }
            }
            PromisedReply<ServerMessage> done = mTopic.publish(content, head);
            BaseDb.getInstance().getStore().msgPruneFailed(mTopic);
            runMessagesLoader(); // Refreshes the messages: hides removed, shows pending.
            done
                    .thenApply(new PromisedReply.SuccessListener<>() {
                        @Override
                        public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                            if (mTopic.isArchived()) {
                                mTopic.updateArchived(false);
                            }
                            return null;
                        }
                    })
                    .thenCatch(new UiUtils.ToastFailureListener(this))
                    .thenFinally(new PromisedReply.FinalListener() {
                        @Override
                        public void onFinally() {
                            // Updates message list with "delivered" or "failed" icon.
                            runMessagesLoader();
                            if (seq > 0 && mTopic.isPinned(seq)) {
                                pinnedStateChanged(seq);
                            }
                        }
                    });
            return true;
        }
        return false;
    }

    void showReply(Drafty reply, int seq) {
        if (isFragmentVisible(FRAGMENT_MESSAGES)) {
            MessagesFragment mf = (MessagesFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_MESSAGES);
            if (mf != null) {
                mf.showReply(this, reply, seq);
            }
        }
    }

    void startEditing(String original, Drafty quote, int seq) {
        if (isFragmentVisible(FRAGMENT_MESSAGES)) {
            MessagesFragment mf = (MessagesFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_MESSAGES);
            if (mf != null) {
                mf.startEditing(this, original, quote, seq);
            }
        }
    }

    void sendKeyPress() {
        if (mTopic != null && mSendTypingNotifications) {
            mTopic.noteKeyPress();
        }
    }

    void sendRecordingProgress(boolean audioOnly) {
        if (mTopic != null && mSendTypingNotifications) {
            mTopic.noteRecording(audioOnly);
        }
    }

    void sendPinMessage(int seq, boolean pin) {
        mTopic.pinMessage(seq, pin)
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                        pinnedStateChanged(seq);
                        return null;
                    }
                }, new UiUtils.ToastFailureListener(this));
    }

    void runMessagesLoader() {
        final MessagesFragment fragment = (MessagesFragment) getSupportFragmentManager().
                findFragmentByTag(FRAGMENT_MESSAGES);
        if (fragment != null && fragment.isVisible()) {
            fragment.runMessagesLoader(mTopicName);
        }
    }

    void pinnedStateChanged(int seq) {
        final MessagesFragment fragment = (MessagesFragment) getSupportFragmentManager().
                findFragmentByTag(FRAGMENT_MESSAGES);
        if (fragment != null && fragment.isVisible()) {
            fragment.pinnedStateChanged(seq);
        }
    }

    /**
     * Show progress indicator based on current status
     *
     * @param active should be true to show progress indicator
     */
    public void setRefreshing(final boolean active) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(() -> {
            MessagesFragment fragMsg = (MessagesFragment) getSupportFragmentManager()
                    .findFragmentByTag(FRAGMENT_MESSAGES);
            if (fragMsg != null) {
                fragMsg.setRefreshing(active);
            }
        });
    }

    // Schedule a delayed {note what="read"} notification.
    void sendNoteRead(int seq) {
        if (mSendReadReceipts) {
            Message msg = mNoteReadHandler.obtainMessage(NOTE_READ_ID, seq, 0, mTopicName);
            mNoteReadHandler.sendMessageDelayed(msg, READ_DELAY);
        }
    }

    @Override
    public void onAcceptAvatar(String topicName, Bitmap avatar) {
        if (isDestroyed() || isFinishing()) {
            return;
        }

        // noinspection unchecked
        UiUtils.updateAvatar(Cache.getTinode().getTopic(topicName), avatar);
    }

    interface DataSetChangeListener {
        void notifyDataSetChanged();
    }

    // Handler which sends "read" notifications for received messages.
    private static class NoteHandler extends Handler {
        final WeakReference<MessageActivity> ref;

        NoteHandler(MessageActivity activity) {
            super(Looper.getMainLooper());
            ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            MessageActivity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            if (activity.mTopic == null) {
                return;
            }

            // If messages fragment is not visible don't send the notification.
            if (!activity.isFragmentVisible(FRAGMENT_MESSAGES)) {
                return;
            }

            // Don't send a notification if more notifications are pending. This avoids the case of acking
            // every {data} message in a large batch.
            // It may pose a problem if a later message is acked first (msg[1].seq > msg[2].seq), but that
            // should not happen.
            if (hasMessages(NOTE_READ_ID)) {
                return;
            }

            String topicName = (String) msg.obj;
            if (topicName.equals(activity.mTopic.getName())) {
                activity.mTopic.noteRead(msg.arg1);
            }
        }
    }

    /**
     * Utility class to send messages queued while offline.
     * The execution is paused while the activity is in background and unpaused
     * when the topic subscription is live.
     */
    private static class PausableSingleThreadExecutor extends ThreadPoolExecutor {
        private final ReentrantLock pauseLock = new ReentrantLock();
        private final Condition unpaused = pauseLock.newCondition();
        private boolean isPaused;

        PausableSingleThreadExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            super.beforeExecute(t, r);
            pauseLock.lock();
            try {
                while (isPaused) unpaused.await();
            } catch (InterruptedException ie) {
                t.interrupt();
            } finally {
                pauseLock.unlock();
            }
        }

        void pause() {
            pauseLock.lock();
            try {
                isPaused = true;
            } finally {
                pauseLock.unlock();
            }
        }

        void resume() {
            pauseLock.lock();
            try {
                isPaused = false;
                unpaused.signalAll();
            } finally {
                pauseLock.unlock();
            }
        }
    }

    private class TListener extends ComTopic.ComListener<VxCard> {

        TListener() {
        }

        @Override
        public void onSubscribe(int code, String text) {
            // Topic name may change after subscription, i.e. new -> grpXXX
            mTopicName = mTopic.getName();
        }

        @Override
        public void onData(MsgServerData data) {
            // Don't send a notification for own messages. They are read by default.
            boolean incomingRegularMessage = data != null &&
                    !Cache.getTinode().isMe(data.from) &&
                    UiUtils.parseSeqReference(data.getStringHeader("replace")) <= 0 &&
                    data.getStringHeader("webrtc") == null;
            if (data != null && !Cache.getTinode().isMe(data.from)) {
                sendNoteRead(data.seq);
            }
            final String senderId = data != null ? data.from : null;
            runOnUiThread(() -> {
                clearTypingIndicator(senderId);
                if (incomingRegularMessage) {
                    MessagesFragment fragment = (MessagesFragment) getSupportFragmentManager()
                            .findFragmentByTag(FRAGMENT_MESSAGES);
                    if (fragment != null && fragment.isVisible()) {
                        fragment.scrollToLatestMessage();
                    }
                }
            });
            runMessagesLoader();
        }

        @Override
        public void onAllMessagesReceived(Integer count) {
            int currentPinHash = mTopic.getPinnedHash();
            if (mPinHash == currentPinHash) {
                return;
            }
            mPinHash = currentPinHash;

            // Make sure all pinned messages are cached.
            int[] pinned = mTopic.getPinned();
            if (pinned == null || pinned.length == 0) {
                // No pinned messages.
                return;
            }
            MsgRange[] pinsArray = MsgRange.toRanges(pinned);
            final Storage store = BaseDb.getInstance().getStore();
            MsgRange[] found = store.msgIsCached(mTopic, pinsArray);
            List<MsgRange> missing = null;
            if (found == null || found.length <= pinned.length) {
                // Array to list.
                missing = Arrays.stream(pinsArray).collect(Collectors.toList());
                if (found != null) {
                    for (MsgRange f : found) {
                        List<MsgRange> tmp = new LinkedList<>();
                        for (MsgRange pin : missing) {
                            MsgRange p = new MsgRange(pin);
                            MsgRange[] clipped = MsgRange.clip(p, f);
                            if (clipped.length > 0) {
                                tmp.add(clipped[0]);
                                if (clipped.length > 1) {
                                    tmp.add(clipped[1]);
                                }
                            }
                        }

                        missing = tmp;
                        if (missing.isEmpty()) {
                            break;
                        }
                    }
                }
            }
            if (missing != null && !missing.isEmpty()) {
                mTopic.getMeta(mTopic.getMetaGetBuilder()
                        .withData(missing.toArray(new MsgRange[0]), MESSAGES_TO_LOAD).build());
            }
        }

        @Override
        public void onPres(MsgServerPres pres) {
            // noinspection SwitchStatementWithTooFewBranches
            switch (MsgServerPres.parseWhat(pres.what)) {
                case ACS:
                    runOnUiThread(() -> {
                        Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
                        if (fragment != null) {
                            if (fragment instanceof DataSetChangeListener) {
                                ((DataSetChangeListener) fragment).notifyDataSetChanged();
                            } else if (fragment instanceof MessagesFragment) {
                                ((MessagesFragment) fragment).notifyDataSetChanged(true);
                            }
                        }
                    });
                    break;
                default:
                    Log.d(TAG, "Topic '" + mTopicName + "' onPres what='" + pres.what + "' (unhandled)");
            }

        }

        @Override
        public void onInfo(MsgServerInfo info) {
            switch (MsgServerInfo.parseWhat(info.what)) {
                case READ:
                case RECV:
                    runOnUiThread(() -> {
                        MessagesFragment fragment = (MessagesFragment) getSupportFragmentManager().
                                findFragmentByTag(FRAGMENT_MESSAGES);
                        if (fragment != null && fragment.isVisible()) {
                            fragment.notifyDataSetChanged(false);
                        }
                    });
                    break;
                case KP:
                    final String typingUserId = !TextUtils.isEmpty(info.from) ? info.from : info.src;
                    runOnUiThread(() -> {
                        onRemoteKeyPress(typingUserId);
                    });
                    break;
                default:
                    // Call.
                    break;
            }
        }

        @Override
        public void onSubsUpdated() {
            Context context = getApplicationContext();
            if (UiUtils.isPermissionGranted(context, Manifest.permission.WRITE_CONTACTS)) {
                Account acc = Utils.getSavedAccount(AccountManager.get(context), Cache.getTinode().getMyId());
                if (acc != null) {
                    Collection<Subscription<VxCard, PrivateType>> subs = mTopic.getSubscriptions();
                    if (subs != null) {
                        ContactsManager.updateContacts(context, acc, Cache.getTinode(), subs, null, false);
                    }
                }
            }

            runOnUiThread(() -> {
                Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
                if (fragment != null) {
                    if (fragment instanceof DataSetChangeListener) {
                        ((DataSetChangeListener) fragment).notifyDataSetChanged();
                    } else if (fragment instanceof MessagesFragment) {
                        ((MessagesFragment) fragment).notifyDataSetChanged(true);
                        if (mNewSubsAvailable) {
                            mNewSubsAvailable = false;
                            // Reload so we can correctly display messages from
                            // new users (subscriptions).
                            ((MessagesFragment) fragment).notifyDataSetChanged(false);
                        }
                    }
                }
            });
        }

        @Override
        public void onMetaDesc(final Description<VxCard, PrivateType> desc) {
            runOnUiThread(() -> {
                Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
                if (fragment != null && mTopic != null) {
                    if (fragment instanceof DataSetChangeListener) {
                        ((DataSetChangeListener) fragment).notifyDataSetChanged();
                    } else if (fragment instanceof MessagesFragment) {
                        refreshToolbar();

                        ((MessagesFragment) fragment).notifyDataSetChanged(true);
                    }
                }
            });
        }

        @Override
        public void onMetaSub(Subscription<VxCard, PrivateType> sub) {
            if (mTopic.isGrpType() && sub.user != null && !mKnownSubs.contains(sub.user)) {
                mKnownSubs.add(sub.user);
                mNewSubsAvailable = true;
            }
        }

        @Override
        public void onContUpdate(final Subscription<VxCard, PrivateType> sub) {
            onMetaDesc(null);
        }

        @Override
        public void onMetaTags(String[] tags) {
            runOnUiThread(() -> {
                Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
                if (fragment instanceof DataSetChangeListener) {
                    ((DataSetChangeListener) fragment).notifyDataSetChanged();
                }
            });
        }

        @Override
        public void onMetaAux(Map<String,Object> aux) {
            runOnUiThread(() -> pinnedStateChanged(-1));
        }

        @Override
        public void onOnline(final boolean online) {
            runOnUiThread(() -> {
                if (mTopic != null) {
                    refreshToolbar();
                }
            });

        }
    }

    private class LoginEventListener extends UiUtils.EventListener {
        LoginEventListener(boolean online) {
            super(MessageActivity.this, online);
        }

        @Override
        public void onLogin(int code, String txt) {
            super.onLogin(code, txt);

            UiUtils.attachMeTopic(MessageActivity.this, null);
            topicAttach();
        }
    }
}
