package co.tinode.tindroid;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
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
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import co.tinode.tindroid.db.StoredMessage;
import co.tinode.tindroid.media.VxCard;
import co.tinode.tindroid.widgets.HorizontalListDivider;
import co.tinode.tinodesdk.ComTopic;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.PrivateType;
import co.tinode.tinodesdk.model.Subscription;

final class MessageReceiptInfoDialog {
    private MessageReceiptInfoDialog() {
    }

    static void show(@NonNull MessageActivity activity, @NonNull ComTopic<VxCard> topic,
                     @NonNull StoredMessage message) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        List<ReceiptEntry> entries = buildEntries(activity, topic, message);

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_message_info, null);
        TextView summary = content.findViewById(R.id.messageInfoSummary);
        TextView empty = content.findViewById(R.id.messageInfoEmpty);
        RecyclerView list = content.findViewById(R.id.messageInfoList);

        int recipientCount = entries.size();
        if (recipientCount > 0) {
            int readCount = 0;
            int deliveredCount = 0;
            for (ReceiptEntry entry : entries) {
                if (entry.delivered) {
                    deliveredCount++;
                }
                if (entry.read) {
                    readCount++;
                }
            }
            summary.setText(activity.getString(R.string.message_info_summary,
                    readCount, recipientCount, deliveredCount, recipientCount));
            summary.setVisibility(View.VISIBLE);
        } else {
            summary.setVisibility(View.GONE);
        }

        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.VERTICAL, false));
        list.setNestedScrollingEnabled(false);
        if (list.getItemDecorationCount() == 0) {
            list.addItemDecoration(new HorizontalListDivider(activity));
        }
        list.setAdapter(new ReceiptAdapter(entries));

        boolean emptyList = entries.isEmpty();
        empty.setVisibility(emptyList ? View.VISIBLE : View.GONE);
        list.setVisibility(emptyList ? View.GONE : View.VISIBLE);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.message_info_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @NonNull
    private static List<ReceiptEntry> buildEntries(@NonNull Context context,
                                                   @NonNull ComTopic<VxCard> topic,
                                                   @NonNull StoredMessage message) {
        int seq = message.seq;
        Collection<Subscription<VxCard, PrivateType>> subs = topic.getSubscriptions();
        List<ReceiptEntry> entries = new ArrayList<>();
        if (subs == null || seq <= 0) {
            return entries;
        }

        Map<String, Date> deliveredReceipts = extractReceiptTimes(message.getHeader("rcpt"), "recv");
        Map<String, Date> readReceipts = extractReceiptTimes(message.getHeader("rcpt"), "read");

        for (Subscription<VxCard, PrivateType> sub : subs) {
            if (sub == null || TextUtils.isEmpty(sub.user) || Cache.getTinode().isMe(sub.user)) {
                continue;
            }

            ComTopic<VxCard> peerTopic = resolvePeerTopic(sub.user);
            Date readAt = readReceipts.get(sub.user);
            Date deliveredAt = deliveredReceipts.get(sub.user);
            boolean read = readAt != null || sub.read >= seq;
            boolean delivered = read || deliveredAt != null || sub.recv >= seq;
            deliveredAt = delivered ? firstNonNull(deliveredAt, readAt) : null;

            entries.add(new ReceiptEntry(
                    sub.user,
                    resolveDisplayName(context, sub, peerTopic),
                    resolveAvatarCard(sub, peerTopic),
                    delivered,
                    deliveredAt,
                    read,
                    readAt
            ));
        }

        entries.sort(Comparator
                .comparingInt((ReceiptEntry item) -> item.read ? 0 : item.delivered ? 1 : 2)
                .thenComparingLong(item -> -sortTimestamp(item.readAt, item.deliveredAt))
                .thenComparing(item -> item.displayName.toLowerCase(Locale.getDefault())));
        return entries;
    }

    @NonNull
    private static Map<String, Date> extractReceiptTimes(@Nullable Object rcpt,
                                                         @NonNull String key) {
        if (!(rcpt instanceof Map<?, ?> receiptRoot)) {
            return java.util.Collections.emptyMap();
        }

        Object typedMap = receiptRoot.get(key);
        if (!(typedMap instanceof Map<?, ?> receiptMap)) {
            return java.util.Collections.emptyMap();
        }

        Map<String, Date> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : receiptMap.entrySet()) {
            if (!(entry.getKey() instanceof String userId) || TextUtils.isEmpty(userId)) {
                continue;
            }

            Date when = parseReceiptTime(entry.getValue());
            if (when != null) {
                result.put(userId, when);
            }
        }
        return result;
    }

    @Nullable
    private static Date parseReceiptTime(@Nullable Object value) {
        if (value instanceof Number number) {
            return new Date(number.longValue());
        }
        if (value instanceof String stringValue) {
            try {
                return new Date(Long.parseLong(stringValue));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static Date firstNonNull(@Nullable Date primary, @Nullable Date fallback) {
        return primary != null ? primary : fallback;
    }

    private static long sortTimestamp(@Nullable Date primary, @Nullable Date fallback) {
        Date actual = firstNonNull(primary, fallback);
        return actual != null ? actual.getTime() : 0L;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static ComTopic<VxCard> resolvePeerTopic(@Nullable String uid) {
        if (TextUtils.isEmpty(uid)) {
            return null;
        }

        Topic<?, ?, ?, ?> cached = Cache.getTinode().getTopic(uid);
        if (cached instanceof ComTopic<?> topic && topic.isP2PType()) {
            return (ComTopic<VxCard>) topic;
        }
        return null;
    }

    @Nullable
    private static String getAddressBookName(@Nullable ComTopic<VxCard> topic) {
        if (topic == null || topic.getPriv() == null) {
            return null;
        }

        Object value = topic.getPriv().get(ChatsActivity.PRIV_ADDRESS_BOOK_NAME);
        return value instanceof CharSequence ? value.toString() : null;
    }

    @NonNull
    private static String resolveDisplayName(@NonNull Context context,
                                             @NonNull Subscription<VxCard, PrivateType> sub,
                                             @Nullable ComTopic<VxCard> peerTopic) {
        String addressBookName = getAddressBookName(peerTopic);
        if (!TextUtils.isEmpty(addressBookName)) {
            return addressBookName;
        }

        if (peerTopic != null) {
            VxCard peerPub = peerTopic.getPub();
            if (peerPub != null && !TextUtils.isEmpty(peerPub.fn)) {
                return peerPub.fn.trim();
            }
        }

        if (sub.pub != null && !TextUtils.isEmpty(sub.pub.fn)) {
            return sub.pub.fn.trim();
        }

        return !TextUtils.isEmpty(sub.user) ? sub.user : context.getString(R.string.placeholder_contact_title);
    }

    @Nullable
    private static VxCard resolveAvatarCard(@NonNull Subscription<VxCard, PrivateType> sub,
                                            @Nullable ComTopic<VxCard> peerTopic) {
        VxCard peerPub = peerTopic != null ? peerTopic.getPub() : null;
        return peerPub != null ? peerPub : sub.pub;
    }

    @NonNull
    private static CharSequence formatStatus(@NonNull Context context, boolean completed, @Nullable Date when,
                                             int doneRes, int doneAtRes, int pendingRes) {
        if (!completed) {
            return context.getString(pendingRes);
        }
        if (when != null) {
            return context.getString(doneAtRes, UtilsString.shortDate(when));
        }
        return context.getString(doneRes);
    }

    private static final class ReceiptEntry {
        final String userId;
        final String displayName;
        @Nullable
        final VxCard avatarPub;
        final boolean delivered;
        @Nullable
        final Date deliveredAt;
        final boolean read;
        @Nullable
        final Date readAt;

        private ReceiptEntry(@NonNull String userId, @NonNull String displayName,
                             @Nullable VxCard avatarPub, boolean delivered,
                             @Nullable Date deliveredAt, boolean read, @Nullable Date readAt) {
            this.userId = userId;
            this.displayName = displayName;
            this.avatarPub = avatarPub;
            this.delivered = delivered;
            this.deliveredAt = deliveredAt;
            this.read = read;
            this.readAt = readAt;
        }
    }

    private static final class ReceiptViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView delivered;
        final TextView read;

        private ReceiptViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.receiptAvatar);
            name = itemView.findViewById(R.id.receiptName);
            delivered = itemView.findViewById(R.id.receiptDelivered);
            read = itemView.findViewById(R.id.receiptRead);
        }
    }

    private static final class ReceiptAdapter extends RecyclerView.Adapter<ReceiptViewHolder> {
        private final List<ReceiptEntry> items;

        private ReceiptAdapter(@NonNull List<ReceiptEntry> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ReceiptViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_receipt, parent, false);
            return new ReceiptViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ReceiptViewHolder holder, int position) {
            ReceiptEntry item = items.get(position);
            Context context = holder.itemView.getContext();

            UiUtils.setAvatar(holder.avatar, item.avatarPub, item.userId, false);
            holder.name.setText(item.displayName);
            holder.delivered.setText(formatStatus(context, item.delivered, item.deliveredAt,
                    R.string.message_info_delivered,
                    R.string.message_info_delivered_at,
                    R.string.message_info_not_delivered));
            holder.read.setText(formatStatus(context, item.read, item.readAt,
                    R.string.message_info_read,
                    R.string.message_info_read_at,
                    R.string.message_info_not_read));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
