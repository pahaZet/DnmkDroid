package co.tinode.tindroid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.selection.ItemDetailsLookup;
import androidx.recyclerview.selection.ItemKeyProvider;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.RecyclerView;
import co.tinode.tindroid.db.StoredMessage;
import co.tinode.tindroid.db.StoredTopic;
import co.tinode.tindroid.format.PreviewFormatter;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.Storage;
import co.tinode.tinodesdk.model.Drafty;
import co.tinode.tinodesdk.model.TheCard;

/**
 * Handling active chats, i.e. 'me' topic.
 */
public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.ViewHolder> {
    private static final int MAX_MESSAGE_PREVIEW_LENGTH = 60;

    private static int sColorOffline;
    private static int sColorOnline;
    private static int sColorNew;
    private final int mEmptyTextResId;
    private final ClickListener mClickListener;
    private List<ComTopic<VxCard>> mTopics;
    private HashMap<String, Integer> mTopicIndex;
    private SelectionTracker<String> mSelectionTracker;
    private final Filter mTopicFilter;
    // Optional filter to find topics by name.
    private Filter mTextFilter = null;
    private int mTitleTextSizeSp = Const.DEFAULT_CHAT_LIST_TITLE_TEXT_SIZE;
    private int mSubtitleTextSizeSp = Const.DEFAULT_CHAT_LIST_SUBTITLE_TEXT_SIZE;

    ChatsAdapter(Context context, ClickListener clickListener, @Nullable Filter filter) {
        this(context, clickListener, filter, R.string.no_chats);
    }

    ChatsAdapter(Context context, ClickListener clickListener, @Nullable Filter filter, int emptyTextResId) {
        super();

        mClickListener = clickListener;
        mTopicFilter = filter != null ? filter : topic -> true;
        mEmptyTextResId = emptyTextResId;

        setHasStableIds(true);
        setTextFilter(null);

        sColorOffline = ResourcesCompat.getColor(context.getResources(),
                R.color.offline, context.getTheme());
        sColorOnline = ResourcesCompat.getColor(context.getResources(),
                R.color.online, context.getTheme());
        sColorNew = ResourcesCompat.getColor(context.getResources(),
                R.color.online, context.getTheme());
    }

    void resetContent(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        refreshTextPreferences(activity);

        final Collection<ComTopic<VxCard>> newTopics = Cache.getTinode().getFilteredTopics(t ->
                t.getTopicType().match(ComTopic.TopicType.USER) &&
                        mTopicFilter.filter((ComTopic) t) &&
                        mTextFilter.filter((ComTopic) t));

        final HashMap<String, Integer> newTopicIndex = new HashMap<>(newTopics.size());
        List<ComTopic<VxCard>> sortedTopics = new ArrayList<>(newTopics);
        sortedTopics.sort(new TopicLastMessageComparator());

        for (ComTopic t : sortedTopics) {
            newTopicIndex.put(t.getName(), newTopicIndex.size());
        }

        mTopics = sortedTopics;
        mTopicIndex = newTopicIndex;

        activity.runOnUiThread(this::notifyDataSetChanged);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new ViewHolder(
                inflater.inflate(viewType, parent, false), mClickListener, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (holder.viewType == R.layout.contact) {
            if (mTopics.size() <= position) {
                // Looks like there is a race condition here.
                return;
            }
            ComTopic<VxCard> topic = mTopics.get(position);
            if (topic == null) {
                // This should not happen.
                return;
            }
            Storage.Message msg = Cache.getTinode().getLastMessage(topic.getName());
            holder.bind(position, topic, msg, mTitleTextSizeSp, mSubtitleTextSizeSp, mSelectionTracker != null &&
                    mSelectionTracker.isSelected(topic.getName()));
        } else if (holder.itemView instanceof TextView) {
            ((TextView) holder.itemView).setText(mEmptyTextResId);
        }
    }

    @Override
    public long getItemId(int position) {
        if (getActualItemCount() == 0) {
            return -2;
        }
        return StoredTopic.getId(mTopics.get(position));
    }

    private String getItemKey(int position) {
        return mTopics.get(position).getName();
    }

    public int getItemPosition(String key) {
        if (mTopicIndex == null) {
            return -1;
        }
        Integer pos = mTopicIndex.get(key);
        return pos == null ? -1 : pos;
    }

    private int getActualItemCount() {
        return mTopics == null ? 0 : mTopics.size();
    }

    @Override
    public int getItemCount() {
        // If there are no contacts, the RV will show a single 'empty' item.
        int count = getActualItemCount();
        return count == 0 ? 1 : count;
    }

    @Override
    public int getItemViewType(int position) {
        if (getActualItemCount() == 0) {
            return R.layout.contact_empty;
        }
        return R.layout.contact;
    }

    void setSelectionTracker(SelectionTracker<String> selectionTracker) {
        mSelectionTracker = selectionTracker;
    }

    void setTextFilter(@Nullable String text) {
        mTextFilter = new Filter() {
            private final String mQuery = !TextUtils.isEmpty(text) ?
                    text.trim().toLowerCase(Locale.getDefault()) : null;
            @Override
            public boolean filter(ComTopic topic) {
                if (TextUtils.isEmpty(mQuery)) {
                    return true;
                }

                ArrayList<String> hayStack = new ArrayList<>();
                TheCard pub = (TheCard) topic.getPub();
                if (pub != null) {
                    hayStack.add(pub.fn);
                    hayStack.add(pub.note);
                }
                hayStack.add(getAddressBookName(topic));
                hayStack.add(topic.getComment());
                Storage.Message msg = Cache.getTinode().getLastMessage(topic.getName());
                if (msg != null && msg.getContent() != null) {
                    hayStack.add(msg.getContent().toPlainText());
                }
                return hayStack.stream()
                        .filter(token -> token != null && token.toLowerCase(Locale.getDefault()).contains(mQuery))
                        .findAny()
                        .orElse(null) != null;
            }
        };
    }

    private static String getAddressBookName(ComTopic<VxCard> topic) {
        if (topic == null || topic.getPriv() == null) {
            return null;
        }

        Object value = topic.getPriv().get(ChatsActivity.PRIV_ADDRESS_BOOK_NAME);
        return value instanceof CharSequence ? value.toString() : null;
    }

    private static boolean hasMessages(ComTopic<VxCard> topic, @Nullable Storage.Message msg) {
        return msg != null || (topic != null && topic.getSeq() > 0);
    }

    @Nullable
    private static Date getLastMessageDate(ComTopic<VxCard> topic, @Nullable Storage.Message msg) {
        if (msg instanceof StoredMessage stored && stored.ts != null) {
            return stored.ts;
        }
        if (topic != null && topic.getSeq() > 0) {
            return topic.getTouched();
        }
        return null;
    }

    private static CharSequence formatDisplayName(Context context, @Nullable String baseName, boolean isNewChat) {
        String title = !TextUtils.isEmpty(baseName) ? baseName : context.getString(R.string.placeholder_contact_title);
        if (!isNewChat) {
            return title;
        }

        SpannableStringBuilder builder = new SpannableStringBuilder(title).append(" ").append("new");
        int start = builder.length() - 3;
        builder.setSpan(new ForegroundColorSpan(sColorNew), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new RelativeSizeSpan(0.72f), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private void refreshTextPreferences(@NonNull Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        mTitleTextSizeSp = readBoundedPreference(preferences,
                Const.PREF_CHAT_LIST_TITLE_TEXT_SIZE,
                Const.DEFAULT_CHAT_LIST_TITLE_TEXT_SIZE);
        mSubtitleTextSizeSp = readBoundedPreference(preferences,
                Const.PREF_CHAT_LIST_SUBTITLE_TEXT_SIZE,
                Const.DEFAULT_CHAT_LIST_SUBTITLE_TEXT_SIZE);
    }

    private static int readBoundedPreference(@NonNull SharedPreferences preferences,
                                             @NonNull String key, int defaultValue) {
        try {
            return clamp(preferences.getInt(key, defaultValue));
        } catch (ClassCastException ignored) {
            String value = preferences.getString(key, null);
            if (!TextUtils.isEmpty(value)) {
                try {
                    return clamp(Integer.parseInt(value));
                } catch (NumberFormatException ignored2) {
                    // Ignore malformed value and fall back to default.
                }
            }
        }
        return clamp(defaultValue);
    }

    private static int clamp(int value) {
        return Math.max(Const.MIN_CHAT_LIST_TEXT_SIZE, Math.min(Const.MAX_CHAT_LIST_TEXT_SIZE, value));
    }

    private static class TopicLastMessageComparator implements Comparator<ComTopic<VxCard>> {
        @Override
        public int compare(ComTopic<VxCard> left, ComTopic<VxCard> right) {
            int pinDiff = Integer.compare(right.getPinnedRank(), left.getPinnedRank());
            if (pinDiff != 0) {
                return pinDiff;
            }

            Storage.Message leftMsg = Cache.getTinode().getLastMessage(left.getName());
            Storage.Message rightMsg = Cache.getTinode().getLastMessage(right.getName());

            boolean leftHasMessages = hasMessages(left, leftMsg);
            boolean rightHasMessages = hasMessages(right, rightMsg);
            if (leftHasMessages != rightHasMessages) {
                return leftHasMessages ? -1 : 1;
            }

            Date leftDate = getLastMessageDate(left, leftMsg);
            Date rightDate = getLastMessageDate(right, rightMsg);
            if (leftDate != null || rightDate != null) {
                if (leftDate == null) {
                    return 1;
                }
                if (rightDate == null) {
                    return -1;
                }
                int dateDiff = rightDate.compareTo(leftDate);
                if (dateDiff != 0) {
                    return dateDiff;
                }
            }

            Date leftTouched = left.getTouched();
            Date rightTouched = right.getTouched();
            if (leftTouched != null || rightTouched != null) {
                if (leftTouched == null) {
                    return 1;
                }
                if (rightTouched == null) {
                    return -1;
                }
                int touchedDiff = rightTouched.compareTo(leftTouched);
                if (touchedDiff != 0) {
                    return touchedDiff;
                }
            }

            return left.getName().compareTo(right.getName());
        }
    }

    interface ClickListener {
        void onClick(String topicName);
    }

    interface Filter {
        // Returns true to keep topic, false to ignore.
        boolean filter(ComTopic topic);
    }

    static class ContactDetailsLookup extends ItemDetailsLookup<String> {
        final RecyclerView mRecyclerView;

        ContactDetailsLookup(RecyclerView rv) {
            mRecyclerView = rv;
        }

        @Nullable
        @Override
        public ItemDetails<String> getItemDetails(@NonNull MotionEvent e) {
            View view = mRecyclerView.findChildViewUnder(e.getX(), e.getY());
            if (view != null) {
                ViewHolder holder = (ViewHolder) mRecyclerView.getChildViewHolder(view);
                return holder.getItemDetails();
            }
            return null;
        }
    }

    static class ContactDetails extends ItemDetailsLookup.ItemDetails<String> {
        int pos;
        String id;

        @Override
        public int getPosition() {
            return pos;
        }

        @Nullable
        @Override
        public String getSelectionKey() {
            return id;
        }
    }

    static class ContactKeyProvider extends ItemKeyProvider<String> {
        final ChatsAdapter mAdapter;

        ContactKeyProvider(ChatsAdapter adapter) {
            super(SCOPE_MAPPED);
            mAdapter = adapter;
        }

        @Nullable
        @Override
        public String getKey(int position) {
            return mAdapter.getItemKey(position);
        }

        @Override
        public int getPosition(@NonNull String key) {
            return mAdapter.getItemPosition(key);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final int viewType;
        TextView name;
        TextView unreadCount;
        TextView priv;
        ImageView messageStatus;
        AppCompatImageView avatarView;
        ImageView online;
        ImageView deleted;
        ImageView channel;
        ImageView group;
        ImageView verified;
        ImageView staff;
        ImageView danger;
        ImageView muted;
        ImageView blocked;
        ImageView archived;
        View pinned;

        final ContactDetails details;
        ClickListener clickListener;

        ViewHolder(@NonNull View item, ClickListener cl, int viewType) {
            super(item);
            this.viewType = viewType;

            if (viewType == R.layout.contact) {
                name = item.findViewById(R.id.contactName);
                unreadCount = item.findViewById(R.id.unreadCount);
                priv = item.findViewById(R.id.contactPriv);
                messageStatus = item.findViewById(R.id.messageStatus);
                avatarView = item.findViewById(R.id.avatar);
                online = item.findViewById(R.id.online);
                deleted = item.findViewById(R.id.deleted);
                channel = item.findViewById(R.id.icon_channel);
                group = item.findViewById(R.id.icon_group);
                verified = item.findViewById(R.id.icon_verified);
                staff = item.findViewById(R.id.icon_staff);
                danger = item.findViewById(R.id.icon_danger);
                muted = item.findViewById(R.id.icon_muted);
                blocked = item.findViewById(R.id.icon_blocked);
                archived = item.findViewById(R.id.icon_archived);
                pinned = item.findViewById(R.id.pinnedChatIndicator);

                details = new ContactDetails();
                clickListener = cl;
            } else {
                details = null;
            }
        }

        ItemDetailsLookup.ItemDetails<String> getItemDetails() {
            return details;
        }

        void bind(int position, final ComTopic<VxCard> topic, Storage.Message msg,
                  int titleTextSizeSp, int subtitleTextSizeSp, boolean selected) {
            final Context context = itemView.getContext();
            final String topicName = topic.getName();

            details.pos = position;
            details.id = topicName;

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSizeSp);
            priv.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSizeSp);

            VxCard pub = topic.getPub();
            String addressBookName = getAddressBookName(topic);
            boolean isNewChat = topic.getSeq() <= 0 && msg == null;
            if (!TextUtils.isEmpty(addressBookName)) {
                name.setText(formatDisplayName(context, addressBookName, isNewChat));
                name.setTypeface(null, Typeface.NORMAL);
            } else if (pub != null && pub.fn != null) {
                name.setText(formatDisplayName(context, pub.fn, isNewChat));
                name.setTypeface(null, Typeface.NORMAL);
            } else if (topic.isSlfType()) {
                name.setText(R.string.self_topic_title);
                name.setTypeface(null, Typeface.NORMAL);
            } else {
                name.setText(formatDisplayName(context, context.getString(R.string.placeholder_contact_title), isNewChat));
                name.setTypeface(null, isNewChat ? Typeface.NORMAL : Typeface.ITALIC);
            }
            Drafty content = (msg != null && !msg.isDeleted()) ? msg.getContent() : null;
            if (content != null) {
                if (msg.isMine()) {
                    messageStatus.setVisibility(View.VISIBLE);
                    UiUtils.setMessageStatusIcon(messageStatus, msg.getStatus(),
                            topic.msgReadCount(msg.getSeqId()), topic.msgRecvCount(msg.getSeqId()));
                } else {
                    messageStatus.setVisibility(View.GONE);
                }
                priv.setText(content.preview(MAX_MESSAGE_PREVIEW_LENGTH)
                        .format(new PreviewFormatter(priv.getContext(), priv.getTextSize())));
            } else {
                messageStatus.setVisibility(View.GONE);
                String subtitle = topic.getComment();
                if (TextUtils.isEmpty(subtitle) && !TextUtils.isEmpty(addressBookName) &&
                        pub != null && !TextUtils.isEmpty(pub.fn) && !TextUtils.equals(addressBookName, pub.fn)) {
                    subtitle = pub.fn;
                }
                priv.setText(subtitle);
            }

            int unread = topic.getUnreadCount();
            if (unread > 0) {
                unreadCount.setText(unread > 9 ? "9+" : String.valueOf(unread));
                unreadCount.setVisibility(View.VISIBLE);
            } else {
                unreadCount.setVisibility(View.GONE);
            }

            UiUtils.setAvatar(avatarView, pub, topicName, topic.isDeleted());

            if (topic.isChannel()) {
                online.setVisibility(View.INVISIBLE);
                channel.setVisibility(View.VISIBLE);
            } else if (topic.isSlfType()) {
                online.setVisibility(View.INVISIBLE);
                channel.setVisibility(View.GONE);
            } else {
                channel.setVisibility(View.GONE);
                if (topic.isGrpType()) {
                   group.setVisibility(View.VISIBLE);
                } else {
                    group.setVisibility(View.GONE);
                }
                if (topic.isDeleted()) {
                    online.setVisibility(View.GONE);
                } else {
                    online.setVisibility(View.VISIBLE);
                    online.setColorFilter(topic.getOnline() ? sColorOnline : sColorOffline);
                }
            }

            if (topic.isDeleted()) {
                itemView.setAlpha(0.8f);
                deleted.setVisibility(View.VISIBLE);
            } else {
                deleted.setVisibility(View.GONE);
                itemView.setAlpha(1.0f);
            }

            verified.setVisibility(topic.isTrustedVerified() ? View.VISIBLE : View.GONE);
            staff.setVisibility(topic.isTrustedStaff() ? View.VISIBLE : View.GONE);
            danger.setVisibility(topic.isTrustedDanger() ? View.VISIBLE : View.GONE);

            if (topic.isSlfType()) {
                muted.setVisibility(View.GONE);
            } else {
                muted.setVisibility(topic.isMuted() ? View.VISIBLE : View.GONE);
            }
            archived.setVisibility(topic.isArchived() ? View.VISIBLE : View.GONE);
            blocked.setVisibility(!topic.isJoiner() ? View.VISIBLE : View.GONE);

            pinned.setVisibility(topic.getPinnedRank() > 0 ? View.VISIBLE : View.GONE);

            if (selected) {
                itemView.setBackgroundResource(R.drawable.contact_background);
                itemView.setOnClickListener(null);

                itemView.setActivated(true);
            } else {
                if (topic.getPinnedRank() > 0) {
                    itemView.setBackgroundResource(R.drawable.contact_background_pinned);
                } else {
                    TypedArray typedArray = context.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
                    itemView.setBackgroundResource(typedArray.getResourceId(0, 0));
                    typedArray.recycle();
                }
                itemView.setOnClickListener(view -> clickListener.onClick(topicName));

                itemView.setActivated(false);
            }

            // Field lengths may have changed.
            itemView.invalidate();
        }
    }
}
