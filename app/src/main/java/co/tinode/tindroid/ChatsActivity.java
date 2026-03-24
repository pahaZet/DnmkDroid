package co.tinode.tindroid;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import co.tinode.tindroid.account.ContactsManager;
import co.tinode.tindroid.account.Utils;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.FndTopic;
import co.tinode.tinodesdk.MeTopic;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Credential;
import co.tinode.tinodesdk.model.Description;
import co.tinode.tinodesdk.model.MetaSetDesc;
import co.tinode.tinodesdk.model.MsgGetMeta;
import co.tinode.tinodesdk.model.MsgServerInfo;
import co.tinode.tinodesdk.model.MsgServerPres;
import co.tinode.tinodesdk.model.MsgSetMeta;
import co.tinode.tinodesdk.model.PrivateType;
import co.tinode.tinodesdk.model.Subscription;

/**
 * This activity owns 'me' topic.
 */
public class ChatsActivity extends BaseActivity
        implements UiUtils.ProgressIndicator, UtilsMedia.MediaPreviewer,
        ImageViewFragment.AvatarCompletionHandler {
    private static final String TAG = "ChatsActivity";
    static final String TAG_FRAGMENT_NAME = "fragment";
    static final String FRAGMENT_CHATLIST = "contacts";
    static final String FRAGMENT_ACCOUNT_INFO = "account_info";
    static final String FRAGMENT_AVATAR_PREVIEW = "avatar_preview";
    static final String FRAGMENT_ACC_CREDENTIALS = "acc_credentials";
    static final String FRAGMENT_ACC_HELP = "acc_help";
    static final String FRAGMENT_ACC_GENERAL = "acc_general";
    static final String FRAGMENT_ACC_NOTIFICATIONS = "acc_notifications";
    static final String FRAGMENT_ACC_PERSONAL = "acc_personal";
    static final String FRAGMENT_ACC_SECURITY = "acc_security";
    static final String FRAGMENT_ACC_ABOUT = "acc_about";
    static final String FRAGMENT_ARCHIVE = "archive";
    static final String FRAGMENT_BANNED = "banned";
    static final String FRAGMENT_WALLPAPERS = "wallpapers";
    static final String PRIV_ADDRESS_BOOK_NAME = "addressBookName";

    private static final String ACCKEY_CONTACTS_SYNC_MARKER = "co.tinode.tindroid.sync_marker_contacts";
    private static final String ACCKEY_AUTOCHAT_SYNC_MARKER = "co.tinode.tindroid.sync_marker_autochats";
    private static final String ACCKEY_AUTOCHAT_MATCHED_USERS = "co.tinode.tindroid.sync_users_autochats";
    private static final long AUTOCHAT_SYNC_DELAY_MS = 750L;

    private ContactsEventListener mTinodeListener = null;
    private MeListener mMeTopicListener = null;
    private MeTopic<VxCard> mMeTopic = null;

    private Account mAccount;
    private ContentObserver mContactsObserver = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mAutoChatExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mAutoChatRunning = new AtomicBoolean(false);
    private final AtomicBoolean mAutoChatPending = new AtomicBoolean(false);
    private final Runnable mAutoChatRunnable = () -> {
        mAutoChatPending.set(false);
        runAutoChatSync();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiUtils.setupSystemToolbar(this);

        setContentView(R.layout.activity_contacts);
        applyEdgeToEdgeInsets(findViewById(android.R.id.content));

        setSupportActionBar(findViewById(R.id.toolbar));

        FragmentManager fm = getSupportFragmentManager();

        if (fm.findFragmentByTag(FRAGMENT_CHATLIST) == null) {
            Fragment fragment = new ChatsFragment();
            fm.beginTransaction()
                    .replace(R.id.contentFragment, fragment, FRAGMENT_CHATLIST)
                    .setPrimaryNavigationFragment(fragment)
                    .commit();
        }

        mMeTopic = Cache.getTinode().getOrCreateMeTopic();
        mMeTopicListener = new MeListener();
    }

    /**
     * onResume restores subscription to 'me' topic and sets listener.
     */
    @Override
    public void onResume() {
        super.onResume();

        final Intent intent = getIntent();
        if (!TextUtils.isEmpty(UiUtils.readTopicNameFromLaunchIntent(intent))) {
            Intent launch = UiUtils.createPostLoginIntent(this, intent);
            UiUtils.clearLaunchTopicExtras(intent);
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
            return;
        }

        final Tinode tinode = Cache.getTinode();
        mTinodeListener = new ContactsEventListener(tinode.isConnected());
        tinode.addListener(mTinodeListener);

        Cache.setSelectedTopicName(null);

        UiUtils.setupToolbar(this, null, null, false,
                null, false, 0);
        registerContactsObserver();
        scheduleAutoChatSync(0);

        if (!mMeTopic.isAttached()) {
            toggleProgressIndicator(true);
        }

        // This will issue a subscription request.
        if (!UiUtils.attachMeTopic(this, mMeTopicListener)) {
            toggleProgressIndicator(false);
        }

        String tag = intent.getStringExtra(TAG_FRAGMENT_NAME);
        if (!TextUtils.isEmpty(tag)) {
            showFragment(tag, null);
        }
    }

    private void datasetChanged() {
        Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
        if (fragment instanceof ChatsFragment) {
            ((ChatsFragment) fragment).datasetChanged();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterContactsObserver();
        mMainHandler.removeCallbacks(mAutoChatRunnable);
        mAutoChatPending.set(false);

        if (mTinodeListener != null) {
            Cache.getTinode().removeListener(mTinodeListener);
            mTinodeListener = null;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mMeTopic != null) {
            mMeTopic.remListener(mMeTopicListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAutoChatExecutor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Enable options menu by returning true
        return true;
    }

    @Override
    public void handleMedia(Bundle args) {
        showFragment(FRAGMENT_AVATAR_PREVIEW, args);
    }

    void showFragment(String tag, Bundle args) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        final FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = fm.findFragmentByTag(tag);
        if (fragment == null) {
            switch (tag) {
                case FRAGMENT_ACCOUNT_INFO:
                    fragment = new AccountInfoFragment();
                    break;
                case FRAGMENT_ACC_CREDENTIALS:
                    fragment = new AccCredFragment();
                    break;
                case FRAGMENT_ACC_HELP:
                    fragment = new AccHelpFragment();
                    break;
                case FRAGMENT_ACC_GENERAL:
                    fragment = new AccGeneralFragment();
                    break;
                case FRAGMENT_ACC_NOTIFICATIONS:
                    fragment = new AccNotificationsFragment();
                    break;
                case FRAGMENT_ACC_PERSONAL:
                    fragment = new AccPersonalFragment();
                    break;
                case FRAGMENT_AVATAR_PREVIEW:
                    fragment = new ImageViewFragment();
                    if (args == null) {
                        args = new Bundle();
                    }
                    args.putBoolean(AttachmentHandler.ARG_AVATAR, true);
                    break;
                case FRAGMENT_ACC_SECURITY:
                    fragment = new AccSecurityFragment();
                    break;
                case FRAGMENT_ACC_ABOUT:
                    fragment = new AccAboutFragment();
                    break;
                case FRAGMENT_ARCHIVE:
                case FRAGMENT_BANNED:
                    fragment = new ChatsFragment();
                    if (args == null) {
                        args = new Bundle();
                    }
                    args.putBoolean(tag, true);
                    break;
                case FRAGMENT_CHATLIST:
                    fragment = new ChatsFragment();
                    break;
                case FRAGMENT_WALLPAPERS:
                    fragment = new WallpaperFragment();
                    break;
                default:
                    throw new IllegalArgumentException("Failed to create fragment: unknown tag " + tag);
            }
        } else if (args == null) {
            // Retain old arguments.
            args = fragment.getArguments();
        }

        if (args != null) {
            if (fragment.getArguments() != null) {
                fragment.getArguments().putAll(args);
            } else {
                fragment.setArguments(args);
            }
        }

        FragmentTransaction trx = fm.beginTransaction();
        trx.replace(R.id.contentFragment, fragment, tag)
                .addToBackStack(tag)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .commit();
    }

    @Override
    public void toggleProgressIndicator(boolean on) {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        for (Fragment f : fragments) {
            if (f instanceof UiUtils.ProgressIndicator && (f.isVisible() || !on)) {
                ((UiUtils.ProgressIndicator) f).toggleProgressIndicator(on);
            }
        }
    }

    @Override
    public void onAcceptAvatar(String topicName, Bitmap avatar) {
        if (isDestroyed() || isFinishing()) {
            return;
        }

        UiUtils.updateAvatar(Cache.getTinode().getMeTopic(), avatar);
    }

    interface FormUpdatable {
        void updateFormValues(final FragmentActivity activity, final MeTopic<VxCard> me);
    }

    private void registerContactsObserver() {
        if (mContactsObserver != null || !UiUtils.isPermissionGranted(this, Manifest.permission.READ_CONTACTS)) {
            return;
        }

        mContactsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, android.net.Uri uri) {
                scheduleAutoChatSync(AUTOCHAT_SYNC_DELAY_MS);
            }
        };
        getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, mContactsObserver);
    }

    private void unregisterContactsObserver() {
        if (mContactsObserver != null) {
            getContentResolver().unregisterContentObserver(mContactsObserver);
            mContactsObserver = null;
        }
    }

    private void scheduleAutoChatSync(long delayMs) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        mAutoChatPending.set(true);
        mMainHandler.removeCallbacks(mAutoChatRunnable);
        mMainHandler.postDelayed(mAutoChatRunnable, Math.max(0L, delayMs));
    }

    private void runAutoChatSync() {
        if (mAutoChatRunning.getAndSet(true)) {
            mAutoChatPending.set(true);
            return;
        }

        mAutoChatExecutor.execute(() -> {
            try {
                syncAddressBookChats();
            } finally {
                mAutoChatRunning.set(false);
                if (mAutoChatPending.get()) {
                    mMainHandler.post(mAutoChatRunnable);
                }
            }
        });
    }

    private void syncAddressBookChats() {
        if (!UiUtils.isPermissionGranted(this, Manifest.permission.READ_CONTACTS)) {
            return;
        }

        final Tinode tinode = Cache.getTinode();
        if (!tinode.isAuthenticated()) {
            return;
        }

        final Account account = getSavedAccount(tinode);
        if (account == null) {
            return;
        }

        final AccountManager accountManager = AccountManager.get(this);
        final String syncMarker = accountManager.getUserData(account, ACCKEY_CONTACTS_SYNC_MARKER);
        if (TextUtils.isEmpty(syncMarker)) {
            return;
        }

        final String processedMarker = accountManager.getUserData(account, ACCKEY_AUTOCHAT_SYNC_MARKER);
        if (TextUtils.equals(syncMarker, processedMarker)) {
            return;
        }

        final FndTopic<VxCard> fnd = tinode.getFndTopic();
        if (fnd == null || !fnd.isAttached()) {
            return;
        }

        final LinkedHashMap<String, PendingChatCandidate> candidates =
                collectPendingChatCandidates(fnd.getSubscriptions());
        final Set<String> handledUsers = decodeUserIds(accountManager.getUserData(account, ACCKEY_AUTOCHAT_MATCHED_USERS));
        boolean created = false;
        boolean complete = true;

        for (PendingChatCandidate candidate : candidates.values()) {
            if (handledUsers.contains(candidate.userId())) {
                continue;
            }
            if (tinode.getTopic(candidate.userId()) != null) {
                handledUsers.add(candidate.userId());
                continue;
            }

            try {
                if (createAddressBookChat(tinode, candidate)) {
                    created = true;
                }
                handledUsers.add(candidate.userId());
            } catch (Exception err) {
                complete = false;
                Log.w(TAG, "Failed to auto-create chat for " + candidate.userId(), err);
            }
        }

        storeMatchedUsers(accountManager, account, handledUsers);
        if (complete) {
            storeMatchedUsers(accountManager, account, candidates.keySet());
            accountManager.setUserData(account, ACCKEY_AUTOCHAT_SYNC_MARKER, syncMarker);
        }

        if (created) {
            runOnUiThread(this::datasetChanged);
        }
    }

    private Account getSavedAccount(Tinode tinode) {
        if (mAccount == null && tinode != null && !TextUtils.isEmpty(tinode.getMyId())) {
            mAccount = Utils.getSavedAccount(AccountManager.get(this), tinode.getMyId());
        }
        return mAccount;
    }

    private LinkedHashMap<String, PendingChatCandidate> collectPendingChatCandidates(
            Collection<Subscription<VxCard, String[]>> matches) {
        LinkedHashMap<String, PendingChatCandidate> result = new LinkedHashMap<>();
        Map<String, String> byPhone = new HashMap<>();

        if (matches != null) {
            for (Subscription<VxCard, String[]> sub : matches) {
                String userId = getFoundSubscriptionId(sub);
                if (TextUtils.isEmpty(userId)) {
                    continue;
                }
                indexPhones(byPhone, userId, sub.pub, sub.priv);
            }
        }

        if (byPhone.isEmpty()) {
            return result;
        }

        try (Cursor cursor = getContentResolver().query(
                ContactsLoaderCallback.ContactsQuery.CONTENT_URI,
                ContactsLoaderCallback.ContactsQuery.PROJECTION,
                ContactsLoaderCallback.ContactsQuery.SELECTION_PHONE_BOOK,
                null,
                ContactsLoaderCallback.ContactsQuery.SORT_ORDER)) {
            if (cursor == null) {
                return result;
            }

            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                String phone = cursor.getString(ContactsLoaderCallback.ContactsQuery.PHONE_NUMBER);
                String userId = resolveByPhone(byPhone, phone);
                if (TextUtils.isEmpty(userId) || result.containsKey(userId)) {
                    continue;
                }

                String displayName = cursor.getString(ContactsLoaderCallback.ContactsQuery.DISPLAY_NAME);
                if (TextUtils.isEmpty(displayName)) {
                    displayName = userId;
                }

                result.put(userId, new PendingChatCandidate(userId, displayName));
            }
        } catch (SecurityException ex) {
            Log.w(TAG, "Unable to read phone contacts for auto-created chats", ex);
        }

        return result;
    }

    private boolean createAddressBookChat(Tinode tinode, PendingChatCandidate candidate) throws Exception {
        if (tinode.getTopic(candidate.userId()) != null) {
            return false;
        }

        PrivateType priv = new PrivateType();
        priv.put(PRIV_ADDRESS_BOOK_NAME, candidate.displayName());

        MsgSetMeta<VxCard, PrivateType> set = new MsgSetMeta.Builder<VxCard, PrivateType>()
                .with(new MetaSetDesc<>(null, priv))
                .build();

        MsgGetMeta get = new MsgGetMeta();
        get.setDesc(null);
        get.setSub(null, null);

        tinode.subscribe(candidate.userId(), set, null).getResult();
        tinode.getMeta(candidate.userId(), get).getResult();
        return tinode.getTopic(candidate.userId()) != null;
    }

    private static void indexPhones(Map<String, String> byPhone, String userId,
                                    VxCard pub, String[] priv) {
        if (pub != null && pub.tel != null) {
            for (VxCard.Contact phone : pub.tel) {
                for (String variant : normalizedPhoneVariants(stripPhoneScheme(phone.uri))) {
                    byPhone.putIfAbsent(variant, userId);
                }
            }
        }

        if (priv != null) {
            for (String match : priv) {
                if (match != null && match.startsWith("tel:")) {
                    for (String variant : normalizedPhoneVariants(stripPhoneScheme(match))) {
                        byPhone.putIfAbsent(variant, userId);
                    }
                }
            }
        }
    }

    private static String resolveByPhone(Map<String, String> byPhone, String rawPhone) {
        for (String variant : normalizedPhoneVariants(rawPhone)) {
            String userId = byPhone.get(variant);
            if (!TextUtils.isEmpty(userId)) {
                return userId;
            }
        }
        return null;
    }

    private static String stripPhoneScheme(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return raw;
        }
        int idx = raw.indexOf(':');
        if (idx > 0 && idx + 1 < raw.length()) {
            String prefix = raw.substring(0, idx).toLowerCase(Locale.US);
            if ("tel".equals(prefix) || "phone".equals(prefix)) {
                return raw.substring(idx + 1);
            }
        }
        return raw;
    }

    private static List<String> normalizedPhoneVariants(String raw) {
        List<String> variants = new java.util.LinkedList<>();
        if (TextUtils.isEmpty(raw)) {
            return variants;
        }

        String normalized = PhoneNumberUtils.normalizeNumber(raw);
        if (TextUtils.isEmpty(normalized)) {
            return variants;
        }

        variants.add(normalized);
        if (normalized.startsWith("+")) {
            variants.add(normalized.substring(1));
        }

        String digits = normalized.replaceAll("[^0-9]", "");
        if (!TextUtils.isEmpty(digits)) {
            variants.add(digits);
            if (digits.length() > 10) {
                variants.add(digits.substring(digits.length() - 10));
            }
        }

        return variants;
    }

    private static String getFoundSubscriptionId(Subscription<?, ?> sub) {
        if (sub == null) {
            return null;
        }
        if (Topic.isP2PType(sub.user)) {
            return sub.user;
        }
        if (Topic.isP2PType(sub.topic)) {
            return sub.topic;
        }
        return null;
    }

    private static Set<String> decodeUserIds(String encoded) {
        Set<String> result = new HashSet<>();
        if (TextUtils.isEmpty(encoded)) {
            return result;
        }

        for (String entry : encoded.split(",")) {
            if (!TextUtils.isEmpty(entry)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static void storeMatchedUsers(AccountManager accountManager, Account account, Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            accountManager.setUserData(account, ACCKEY_AUTOCHAT_MATCHED_USERS, null);
            return;
        }
        accountManager.setUserData(account, ACCKEY_AUTOCHAT_MATCHED_USERS,
                TextUtils.join(",", new TreeSet<>(userIds)));
    }

    private record PendingChatCandidate(String userId, String displayName) {
    }

    // This is called on Websocket thread.
    private class MeListener extends UiUtils.MeEventListener {
        private void updateVisibleInfoFragment() {
            runOnUiThread(() -> {
                List<Fragment> fragments = getSupportFragmentManager().getFragments();
                for (Fragment f : fragments) {
                    if (f != null && f.isVisible() && f instanceof FormUpdatable) {
                        ((FormUpdatable) f).updateFormValues(ChatsActivity.this, mMeTopic);
                    }
                }
            });
        }

        @Override
        public void onInfo(MsgServerInfo info) {
            datasetChanged();
        }

        @Override
        public void onPres(MsgServerPres pres) {
            if ("msg".equals(pres.what)) {
                datasetChanged();
            } else if ("off".equals(pres.what) || "on".equals(pres.what)) {
                datasetChanged();
            }
        }

        @Override
        public void onMetaSub(final Subscription<VxCard, PrivateType> sub) {
            if (sub.deleted == null) {
                if (sub.pub != null) {
                    sub.pub.constructBitmap();
                }

                if (!UiUtils.isPermissionGranted(ChatsActivity.this, Manifest.permission.WRITE_CONTACTS)) {
                    // We can't save contact if we don't have appropriate permission.
                    return;
                }

                Tinode tinode = Cache.getTinode();
                if (mAccount == null) {
                    mAccount = Utils.getSavedAccount(AccountManager.get(ChatsActivity.this), tinode.getMyId());
                }
                if (Topic.isP2PType(sub.topic)) {
                    ContactsManager.processContact(ChatsActivity.this,
                            ChatsActivity.this.getContentResolver(), mAccount, tinode,
                            sub.pub, null, sub.getUnique(), sub.deleted != null,
                            null, false);
                }
            }
        }

        @Override
        public void onMetaDesc(final Description<VxCard, PrivateType> desc) {
            if (desc.pub != null) {
                desc.pub.constructBitmap();
            }

            updateVisibleInfoFragment();
        }

        @Override
        public void onSubsUpdated() {
            datasetChanged();
        }

        @Override
        public void onSubscriptionError(Exception ex) {
            runOnUiThread(() -> {
                Fragment fragment = UiUtils.getVisibleFragment(getSupportFragmentManager());
                if (fragment instanceof UiUtils.ProgressIndicator) {
                    ((UiUtils.ProgressIndicator) fragment).toggleProgressIndicator(false);
                }
            });
        }

        @Override
        public void onContUpdated(final String contact) {
            datasetChanged();
        }

        @Override
        public void onMetaTags(String[] tags) {
            updateVisibleInfoFragment();
        }

        @Override
        public void onCredUpdated(Credential[] cred) {
            updateVisibleInfoFragment();
        }
    }

    private class ContactsEventListener extends UiUtils.EventListener {
        ContactsEventListener(boolean online) {
            super(ChatsActivity.this, online);
        }

        @Override
        public void onLogin(int code, String txt) {
            super.onLogin(code, txt);
            UiUtils.attachMeTopic(ChatsActivity.this, mMeTopicListener);
            scheduleAutoChatSync(0);
        }

        @Override
        public void onDisconnect(boolean byServer, int code, String reason) {
            super.onDisconnect(byServer, code, reason);

            // Update online status of contacts.
            datasetChanged();
        }
    }
}
