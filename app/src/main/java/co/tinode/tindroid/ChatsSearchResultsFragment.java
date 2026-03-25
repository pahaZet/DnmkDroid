package co.tinode.tindroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.HorizontalListDivider;
import co.tinode.tinodesdk.ComTopic;

public class ChatsSearchResultsFragment extends Fragment implements ChatListDataSetListener {
    private static final String ARG_SHOW_GROUPS = "show_groups";

    private ChatsAdapter mAdapter;
    private boolean mShowGroups;

    public static ChatsSearchResultsFragment newInstance(boolean showGroups) {
        ChatsSearchResultsFragment fragment = new ChatsSearchResultsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_SHOW_GROUPS, showGroups);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        mShowGroups = args != null && args.getBoolean(ARG_SHOW_GROUPS);

        final AppCompatActivity activity = (AppCompatActivity) requireActivity();
        RecyclerView rv = view.findViewById(R.id.chat_list);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setHasFixedSize(true);
        rv.addItemDecoration(new HorizontalListDivider(activity));
        mAdapter = new ChatsAdapter(activity, topicName -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            Intent intent = new Intent(activity, MessageActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(Const.INTENT_EXTRA_TOPIC, topicName);
            activity.startActivity(intent);
        }, topic -> isIncludedInSearch((ComTopic<VxCard>) topic),
                mShowGroups ? R.string.no_groups : R.string.no_chats);
        rv.setAdapter(mAdapter);

        ChatsSearchViewModel vm = new ViewModelProvider(requireParentFragment()).get(ChatsSearchViewModel.class);
        vm.getQuery().observe(getViewLifecycleOwner(), this::applyQuery);
    }

    @Override
    public void onResume() {
        super.onResume();
        datasetChanged();
    }

    @Override
    public void datasetChanged() {
        Activity activity = getActivity();
        if (mAdapter != null && activity != null) {
            mAdapter.resetContent(activity);
        }
    }

    private void applyQuery(String query) {
        if (mAdapter == null) {
            return;
        }

        String normalized = !TextUtils.isEmpty(query) ? query.trim().toLowerCase(Locale.getDefault()) : null;
        mAdapter.setTextFilter(normalized);
        datasetChanged();
    }

    private boolean isIncludedInSearch(ComTopic<VxCard> topic) {
        if (topic == null || topic.isArchived() || !topic.isJoiner()) {
            return false;
        }
        if (mShowGroups) {
            return topic.isGrpType() || topic.isChannel();
        }
        return topic.isP2PType() || topic.isSlfType();
    }
}
