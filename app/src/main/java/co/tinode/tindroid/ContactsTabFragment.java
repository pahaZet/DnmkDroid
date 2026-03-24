package co.tinode.tindroid;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.loader.app.LoaderManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.HorizontalListDivider;
import co.tinode.tinodesdk.FndTopic;
import co.tinode.tinodesdk.PromisedReply;
import co.tinode.tinodesdk.Tinode;
import co.tinode.tinodesdk.model.MetaSetDesc;
import co.tinode.tinodesdk.model.MsgGetMeta;
import co.tinode.tinodesdk.model.MsgSetMeta;
import co.tinode.tinodesdk.model.ServerMessage;
import co.tinode.tinodesdk.model.Subscription;

public class ContactsTabFragment extends Fragment {
    private static final int LOADER_ID = 104;

    private final List<FindAdapter.FoundMember> mPrivateMatches = new LinkedList<>();

    private FndTopic<VxCard> mFndTopic;
    private FndTopic.FndListener<VxCard> mFndListener;
    private FindAdapter mAdapter;
    private ContactsLoaderCallback mContactsLoaderCallback;

    private final ActivityResultLauncher<String[]> mRequestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                for (Map.Entry<String, Boolean> e : result.entrySet()) {
                    if (!e.getValue()) {
                        return;
                    }
                }
                FragmentActivity activity = getActivity();
                UiUtils.onContactsPermissionsGranted(activity);
                restartLoader(activity);
                loadAddressBookMatches();
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFndTopic = Cache.getTinode().getOrCreateFndTopic();
        mFndListener = new ContactsFndListener();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentActivity activity = requireActivity();

        RecyclerView rv = view.findViewById(R.id.chat_list);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setHasFixedSize(true);
        rv.addItemDecoration(new HorizontalListDivider(activity));

        mAdapter = new FindAdapter(activity, new ContactClickListener(), FindAdapter.DisplayMode.CONTACTS_ONLY);
        mAdapter.swapCursor(null, null);
        mAdapter.setContactsPermission(UiUtils.isPermissionGranted(activity, Manifest.permission.READ_CONTACTS));
        mAdapter.setFoundMembers(activity, null, mPrivateMatches);
        rv.setAdapter(mAdapter);

        mContactsLoaderCallback = new ContactsLoaderCallback(LOADER_ID, activity, mAdapter,
                ContactsLoaderCallback.LoaderMode.PHONE_BOOK);
    }

    @Override
    public void onResume() {
        super.onResume();
        restartLoader(getActivity());

        if (Cache.getTinode().isAuthenticated()) {
            loadAddressBookMatches();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mFndTopic != null) {
            mFndTopic.remListener(mFndListener);
        }
    }

    private void restartLoader(@Nullable FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (UiUtils.isPermissionGranted(activity, Manifest.permission.READ_CONTACTS)) {
            mAdapter.setContactsPermission(true);
            LoaderManager lm = LoaderManager.getInstance(activity);
            if (lm.getLoader(LOADER_ID) == null) {
                lm.initLoader(LOADER_ID, null, mContactsLoaderCallback);
            } else {
                lm.restartLoader(LOADER_ID, null, mContactsLoaderCallback);
            }
        } else if (((ReadContactsPermissionChecker) activity).shouldRequestReadContactsPermission()) {
            mAdapter.setContactsPermission(false);
            ((ReadContactsPermissionChecker) activity).setReadContactsPermissionRequested();
            mRequestPermissionLauncher.launch(new String[] {
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS});
        }
    }

    private void loadAddressBookMatches() {
        Cache.attachFndTopic(mFndListener)
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage ignored) {
                        return mFndTopic.setMeta(new MsgSetMeta.Builder<String, String>()
                                .with(new MetaSetDesc<>(Tinode.NULL_VALUE, null))
                                .build());
                    }
                })
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage ignored) {
                        return mFndTopic.getMeta(MsgGetMeta.sub());
                    }
                })
                .thenApply(new PromisedReply.SuccessListener<>() {
                    @Override
                    public PromisedReply<ServerMessage> onSuccess(ServerMessage result) {
                        applyPrivateMatches(result);
                        return null;
                    }
                })
                .thenCatch(new UiUtils.ToastFailureListener(getActivity()));
    }

    private void applyPrivateMatches(@Nullable ServerMessage result) {
        mPrivateMatches.clear();
        if (result != null && result.meta != null && result.meta.sub != null) {
            for (Subscription<VxCard, String[]> sub : result.meta.sub) {
                if (sub.pub != null) {
                    sub.pub.constructBitmap();
                }
                mPrivateMatches.add(new FindAdapter.FoundMember(
                        sub.user == null ? sub.topic : sub.user, sub.pub, sub.priv));
            }
        }

        FragmentActivity activity = getActivity();
        if (mAdapter != null) {
            mAdapter.setFoundMembers(activity, null, mPrivateMatches);
        }
    }

    interface ReadContactsPermissionChecker {
        boolean shouldRequestReadContactsPermission();

        void setReadContactsPermissionRequested();
    }

    private class ContactsFndListener extends FndTopic.FndListener<VxCard> {
        @Override
        public void onMetaSub(Subscription<VxCard, String[]> sub) {
            if (sub.pub != null) {
                sub.pub.constructBitmap();
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
}
