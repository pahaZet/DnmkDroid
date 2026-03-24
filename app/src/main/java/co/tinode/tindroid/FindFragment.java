package co.tinode.tindroid;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.Toast;

import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ShareActionProvider;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.CircleProgressView;
import co.tinode.tindroid.widgets.HorizontalListDivider;
import co.tinode.tinodesdk.FndTopic;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.model.MetaSetDesc;
import co.tinode.tinodesdk.model.MsgGetMeta;
import co.tinode.tinodesdk.model.MsgSetMeta;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.Subscription;

public class FindFragment extends Fragment implements UiUtils.ProgressIndicator, MenuProvider {

    private static final String TAG = "FindFragment";
    private static final int SEARCH_REQUEST_DELAY = 1000;
    private static final int MIN_TAG_LENGTH = 4;
    private static final Pattern SINGLE_TAG_TEST = Pattern.compile("[\\s,:]");

    private FndTopic<VxCard> mFndTopic;
    private FndListener mFndListener;
    private LoginEventListener mLoginListener;

    private String mSearchTerm;
    private FindAdapter mAdapter;
    private CircleProgressView mProgress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFndTopic = Cache.getTinode().getOrCreateFndTopic();
        mFndListener = new FndListener();

        if (savedInstanceState != null) {
            mSearchTerm = savedInstanceState.getString(SearchManager.QUERY);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View fragment, Bundle savedInstanceState) {
        final FragmentActivity activity = requireActivity();
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        RecyclerView rv = fragment.findViewById(R.id.chat_list);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setHasFixedSize(true);
        rv.addItemDecoration(new HorizontalListDivider(activity));

        mAdapter = new FindAdapter(activity, new ContactClickListener(), FindAdapter.DisplayMode.DIRECTORY_ONLY);
        mAdapter.clearFound(activity, mSearchTerm);
        rv.setAdapter(mAdapter);

        mProgress = fragment.findViewById(R.id.progressCircle);
    }

    @Override
    public void onResume() {
        super.onResume();

        final Tinode tinode = Cache.getTinode();
        mLoginListener = new LoginEventListener(tinode.isConnected());
        tinode.addListener(mLoginListener);

        if (!tinode.isAuthenticated()) {
            return;
        }

        topicAttach();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mFndTopic != null) {
            mFndTopic.remListener(mFndListener);
        }

        if (mLoginListener != null) {
            Cache.getTinode().removeListener(mLoginListener);
            mLoginListener = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (!TextUtils.isEmpty(mSearchTerm)) {
            outState.putString(SearchManager.QUERY, mSearchTerm);
        }
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.menu_contacts, menu);

        final FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        final SearchManager searchManager =
                (SearchManager) activity.getSystemService(Activity.SEARCH_SERVICE);

        if (searchManager == null) {
            return;
        }

        MenuItem searchItem = menu.findItem(R.id.action_search);
        final SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) {
            return;
        }
        searchView.setQueryHint(getResources().getString(R.string.hint_search_tags));
        searchView.setSearchableInfo(searchManager.getSearchableInfo(activity.getComponentName()));
        searchView.setFocusable(true);
        searchView.setFocusableInTouchMode(true);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            private Handler mHandler;

            @Override
            public boolean onQueryTextSubmit(String queryText) {
                if (mHandler != null) {
                    mHandler.removeCallbacksAndMessages(null);
                }

                mSearchTerm = doSearch(queryText);
                return true;
            }

            @Override
            public boolean onQueryTextChange(final String queryText) {

                if (mHandler == null) {
                    mHandler = new Handler();
                } else {
                    mHandler.removeCallbacksAndMessages(null);
                }

                mHandler.postDelayed(() -> mSearchTerm = doSearch(queryText), SEARCH_REQUEST_DELAY);
                return true;
            }
        });

        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(@NonNull MenuItem menuItem) {
                searchView.setIconified(false);
                searchView.requestFocus();
                searchView.requestFocusFromTouch();
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(@NonNull MenuItem menuItem) {
                searchView.clearFocus();
                mSearchTerm = doSearch("");
                return true;
            }
        });


        if (mSearchTerm != null) {
            final String savedSearchTerm = mSearchTerm;
            searchItem.expandActionView();
            searchView.setQuery(savedSearchTerm, false);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        final FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return true;
        }

        Intent intent;
        int id = item.getItemId();
        if (id == R.id.action_add_contact) {
            intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                Log.w(TAG, "No application can add contact");
                Toast.makeText(activity, R.string.action_failed, Toast.LENGTH_LONG).show();
            }
            return true;
        } else if (id == R.id.action_invite) {
            ShareActionProvider provider = (ShareActionProvider) MenuItemCompat.getActionProvider(item);
            if (provider == null) {
                return false;
            }
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, activity.getResources().getString(R.string.tinode_invite_subject));
            intent.putExtra(Intent.EXTRA_TEXT, activity.getResources().getString(R.string.tinode_invite_body));
            provider.setShareIntent(intent);
            return true;
        } else if (id == R.id.action_offline) {
            Cache.getTinode().reconnectNow(true, false, false);
            return true;
        }

        return false;
    }

    private void topicAttach() {
        Cache.attachFndTopic(mFndListener)
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                        final FragmentActivity activity = getActivity();
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                            return null;
                        }

                        if (TextUtils.isEmpty(mSearchTerm)) {
                            mAdapter.clearFound(activity, null);
                        } else {
                            mSearchTerm = doSearch(mSearchTerm);
                        }
                        return null;
                    }
                })
                .thenCatch(new UiUtils.ToastFailureListener(getActivity()));
    }

    private void onFindQueryResult() {
        mAdapter.resetFound(getActivity(), mSearchTerm);
    }

    private String doSearch(String query) {
        query = normalizeSearchTerm(query);

        if (mFndTopic == null || !mFndTopic.isAttached()) {
            if (mAdapter != null) {
                mAdapter.clearFound(getActivity(), query);
            }
            toggleProgressIndicator(false);
            return query;
        }

        if (TextUtils.isEmpty(query) || query.length() < MIN_TAG_LENGTH) {
            mFndTopic.setMeta(new MsgSetMeta.Builder<String, String>()
                    .with(new MetaSetDesc<>(Tinode.NULL_VALUE, null)).build());
            if (mAdapter != null) {
                mAdapter.clearFound(getActivity(), query);
            }
            toggleProgressIndicator(false);
            return query;
        }

        String serverQuery = query;
        if (!SINGLE_TAG_TEST.matcher(serverQuery).find()) {
            String email = UtilsString.asEmail(serverQuery);
            if (email != null) {
                serverQuery = Tinode.TAG_EMAIL + email;
            } else {
                String tel = UtilsString.asPhone(serverQuery);
                if (tel != null) {
                    serverQuery = Tinode.TAG_PHONE + tel;
                } else {
                    if (serverQuery.charAt(0) == '@') {
                        serverQuery = serverQuery.substring(1);
                    }
                    serverQuery = Tinode.TAG_ALIAS + serverQuery + "," + serverQuery;
                }
            }
        }

        mFndTopic.setMeta(new MsgSetMeta.Builder<String, String>()
                .with(new MetaSetDesc<>(serverQuery, null)).build());
        toggleProgressIndicator(true);
        mFndTopic.getMeta(MsgGetMeta.sub()).thenFinally(new PromisedReply.FinalListener() {
            @Override
            public void onFinally() {
                toggleProgressIndicator(false);
            }
        });

        return query;
    }

    private static String normalizeSearchTerm(@Nullable String query) {
        if (query == null) {
            return null;
        }

        query = query.trim();
        return !TextUtils.isEmpty(query) ? query : null;
    }

    @Override
    public void toggleProgressIndicator(final boolean visible) {
        if (mProgress == null) {
            return;
        }

        FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        activity.runOnUiThread(() -> {
            if (visible) {
                mProgress.show();
            } else {
                mProgress.hide();
            }
        });
    }

    private class FndListener extends FndTopic.FndListener<VxCard> {
        @Override
        public void onMetaSub(final Subscription<VxCard, String[]> sub) {
            if (sub.pub != null) {
                sub.pub.constructBitmap();
            }
        }

        @Override
        public void onSubsUpdated() {
            if (!TextUtils.isEmpty(mSearchTerm) && mSearchTerm.length() >= MIN_TAG_LENGTH) {
                onFindQueryResult();
            }
        }
    }

    private class ContactClickListener implements FindAdapter.ClickListener {
        @Override
        public void onClick(String topicName) {
            FragmentActivity activity = getActivity();

            if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                return;
            }
            Intent initial = activity.getIntent();
            Intent launcher = new Intent(activity, MessageActivity.class);
            Uri uri = initial != null ? initial.getParcelableExtra(Intent.EXTRA_STREAM) : null;
            if (uri != null) {
                launcher.setDataAndType(uri, initial.getType());
                launcher.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            launcher.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            launcher.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
            startActivity(launcher);
            activity.finish();
        }
    }

    private class LoginEventListener extends UiUtils.EventListener {
        LoginEventListener(boolean online) {
            super(getActivity(), online);
        }

        @Override
        public void onLogin(int code, String txt) {
            super.onLogin(code, txt);
            topicAttach();
        }
    }
}
