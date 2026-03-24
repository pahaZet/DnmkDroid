package co.tinode.tindroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Subscription;

import coil.Coil;
import coil.request.ImageRequest;
import coil.size.Scale;
import coil.target.Target;

public class FindAdapter extends RecyclerView.Adapter<FindAdapter.ViewHolder>
        implements ContactsLoaderCallback.CursorSwapper {

    enum DisplayMode {
        CONTACTS_ONLY,
        DIRECTORY_ONLY
    }

    static record FoundMember(String id, VxCard pub, String[] priv) {
    }

    private final DisplayMode mDisplayMode;
    private final TextAppearanceSpan mHighlightTextSpan;
    private final ClickListener mClickListener;

    private List<FoundMember> mFound;
    private List<AddressBookEntry> mAddressBook;
    private Cursor mCursor;
    private String mSearchTerm;
    private boolean mPermissionGranted = false;

    FindAdapter(Context context, @NonNull ClickListener clickListener, @NonNull DisplayMode displayMode) {
        super();

        mCursor = null;
        mFound = new LinkedList<>();
        mAddressBook = new LinkedList<>();
        mClickListener = clickListener;
        mDisplayMode = displayMode;

        setHasStableIds(true);

        mHighlightTextSpan = new TextAppearanceSpan(context, R.style.searchTextHighlight);
    }

    void resetFound(Activity activity, String searchTerm) {
        List<FoundMember> found = new LinkedList<>();
        Collection<Subscription<Object, String[]>> subs = Cache.getTinode().getFndTopic().getSubscriptions();
        if (subs != null) {
            for (Subscription<Object, String[]> s : subs) {
                found.add(new FoundMember(s.user == null ? s.topic : s.user, (VxCard) s.pub, s.priv));
            }
        }

        setFoundMembers(activity, searchTerm, found);
    }

    void setFoundMembers(Activity activity, String searchTerm, @Nullable List<FoundMember> found) {
        mFound = found != null ? new LinkedList<>(found) : new LinkedList<>();
        mSearchTerm = searchTerm;
        rebuildAddressBook();
        if (activity != null) {
            activity.runOnUiThread(this::notifyDataSetChanged);
        }
    }

    void clearFound(Activity activity, String searchTerm) {
        setFoundMembers(activity, searchTerm, null);
    }

    void setContactsPermission(boolean granted) {
        mPermissionGranted = granted;
    }

    void setSearchTerm(String searchTerm) {
        if (TextUtils.equals(mSearchTerm, searchTerm)) {
            return;
        }
        mSearchTerm = searchTerm;
        rebuildAddressBook();
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void swapCursor(Cursor newCursor, String searchTerm) {
        mSearchTerm = searchTerm;

        if (newCursor == mCursor) {
            return;
        }

        final Cursor oldCursor = mCursor;
        mCursor = newCursor;
        rebuildAddressBook();
        notifyDataSetChanged();

        if (oldCursor != null) {
            oldCursor.close();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(viewType, parent, false);
        if (viewType == R.layout.not_found ||
                viewType == R.layout.no_permission ||
                viewType == R.layout.no_search_query) {
            return new ViewHolderEmpty(view);
        }

        return new ViewHolderItem(view, mClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(position, getItemAt(position));
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder instanceof ViewHolderItem) {
            ImageView avatar = ((ViewHolderItem) holder).avatar;
            if (avatar != null) {
                avatar.setTag(R.id.avatar, null);
                avatar.setImageDrawable(null);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return switch (mDisplayMode) {
            case CONTACTS_ONLY -> {
                if (getAddressBookItemCount() == 0) {
                    yield mPermissionGranted ? R.layout.not_found : R.layout.no_permission;
                }
                yield R.layout.contact;
            }
            case DIRECTORY_ONLY -> {
                if (getFoundItemCount() == 0) {
                    yield TextUtils.isEmpty(mSearchTerm) ? R.layout.no_search_query : R.layout.not_found;
                }
                yield R.layout.contact;
            }
        };
    }

    @Override
    public long getItemId(int position) {
        return switch (mDisplayMode) {
            case CONTACTS_ONLY -> {
                if (getAddressBookItemCount() == 0) {
                    yield ("empty_contacts" + mPermissionGranted).hashCode();
                }
                yield ("contact:" + mAddressBook.get(position).stableKey()).hashCode();
            }
            case DIRECTORY_ONLY -> {
                if (getFoundItemCount() == 0) {
                    yield ("empty_directory" + TextUtils.isEmpty(mSearchTerm)).hashCode();
                }
                yield ("found:" + mFound.get(position).id()).hashCode();
            }
        };
    }

    @Override
    public int getItemCount() {
        return switch (mDisplayMode) {
            case CONTACTS_ONLY -> Math.max(getAddressBookItemCount(), 1);
            case DIRECTORY_ONLY -> Math.max(getFoundItemCount(), 1);
        };
    }

    interface ClickListener {
        void onClick(String topicName);
    }

    static class ViewHolderEmpty extends ViewHolder {
        ViewHolderEmpty(@NonNull View item) {
            super(item);
        }

        @Override
        public void bind(int position, Object data) {
        }
    }

    public static abstract class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        abstract void bind(int position, Object data);
    }

    public class ViewHolderItem extends ViewHolder {
        final TextView name;
        final TextView contactPriv;
        final ImageView avatar;
        final ClickListener clickListener;

        ViewHolderItem(@NonNull View item, ClickListener cl) {
            super(item);

            name = item.findViewById(R.id.contactName);
            contactPriv = item.findViewById(R.id.contactPriv);
            avatar = item.findViewById(R.id.avatar);

            item.findViewById(R.id.online).setVisibility(View.GONE);
            item.findViewById(R.id.unreadCount).setVisibility(View.GONE);

            clickListener = cl;
        }

        @Override
        public void bind(int position, final Object data) {
            if (data instanceof FoundMember) {
                bind((FoundMember) data);
            } else if (data instanceof AddressBookEntry) {
                bind((AddressBookEntry) data);
            }
        }

        private void bind(final AddressBookEntry entry) {
            final String photoUri = entry.photoUri();
            final String displayName = entry.displayName();
            final String unique = entry.id();
            final String description = entry.description();
            final Context context = itemView.getContext();
            final Drawable fallbackAvatar = UiUtils.avatarDrawable(context, null, displayName, unique, false);
            final String avatarRequestKey = "addressbook:" + entry.stableKey();

            name.setTypeface(null, Typeface.NORMAL);
            avatar.setTag(R.id.avatar, avatarRequestKey);
            avatar.setImageDrawable(fallbackAvatar);
            name.setText(highlight(displayName));

            if (TextUtils.isEmpty(description)) {
                contactPriv.setText("");
                contactPriv.setVisibility(View.GONE);
            } else {
                contactPriv.setText(highlight(description));
                contactPriv.setVisibility(View.VISIBLE);
            }

            if (!TextUtils.isEmpty(photoUri)) {
                Coil.imageLoader(context).enqueue(
                        new ImageRequest.Builder(context)
                                .data(photoUri)
                                .placeholder(fallbackAvatar)
                                .error(fallbackAvatar)
                                .target(new Target() {
                                    @Override
                                    public void onStart(@Nullable Drawable placeholder) {
                                        if (matchesAvatarRequest(avatar, avatarRequestKey)) {
                                            avatar.setImageDrawable(placeholder != null ? placeholder : fallbackAvatar);
                                        }
                                    }

                                    @Override
                                    public void onSuccess(@NonNull Drawable result) {
                                        if (matchesAvatarRequest(avatar, avatarRequestKey)) {
                                            avatar.setImageDrawable(result);
                                        }
                                    }

                                    @Override
                                    public void onError(@Nullable Drawable error) {
                                        if (matchesAvatarRequest(avatar, avatarRequestKey)) {
                                            avatar.setImageDrawable(error != null ? error : fallbackAvatar);
                                        }
                                    }
                                })
                                .scale(Scale.FIT)
                                .build());
            } else if (entry.pub() != null) {
                UiUtils.setAvatar(avatar, entry.pub(), unique, false);
            } else {
                avatar.setImageDrawable(fallbackAvatar);
            }

            itemView.setOnClickListener(view -> clickListener.onClick(unique));
        }

        private void bind(final FoundMember member) {
            final String userId = member.id();

            avatar.setImageDrawable(null);
            UiUtils.setAvatar(avatar, member.pub(), userId, false);
            if (member.pub() != null && !TextUtils.isEmpty(member.pub().fn)) {
                name.setText(highlight(member.pub().fn));
                name.setTypeface(null, Typeface.NORMAL);
            } else if (Topic.isSlfType(userId)) {
                name.setText(R.string.self_topic_title);
                name.setTypeface(null, Typeface.NORMAL);
            } else {
                name.setText(R.string.placeholder_contact_title);
                name.setTypeface(null, Typeface.ITALIC);
            }

            if (member.priv() != null && member.priv().length > 0) {
                contactPriv.setText(highlight(TextUtils.join(", ", member.priv())));
                contactPriv.setVisibility(View.VISIBLE);
            } else if (Topic.isSlfType(userId)) {
                contactPriv.setText(R.string.self_topic_description);
                contactPriv.setVisibility(View.VISIBLE);
            } else {
                contactPriv.setText("");
                contactPriv.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(view -> clickListener.onClick(userId));
        }
    }

    private int getCursorItemCount() {
        return mCursor == null || mCursor.isClosed() ? 0 : mCursor.getCount();
    }

    private int getFoundItemCount() {
        return mFound != null ? mFound.size() : 0;
    }

    private int getAddressBookItemCount() {
        return mAddressBook != null ? mAddressBook.size() : 0;
    }

    private Object getItemAt(int position) {
        return switch (mDisplayMode) {
            case CONTACTS_ONLY -> getAddressBookItemCount() == 0 ? null : mAddressBook.get(position);
            case DIRECTORY_ONLY -> getFoundItemCount() == 0 ? null : mFound.get(position);
        };
    }

    private CharSequence highlight(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        int startIndex = UtilsString.indexOfSearchQuery(value, mSearchTerm);
        if (startIndex < 0 || TextUtils.isEmpty(mSearchTerm)) {
            return value;
        }

        SpannableString highlighted = new SpannableString(value);
        highlighted.setSpan(mHighlightTextSpan, startIndex, startIndex + mSearchTerm.length(), 0);
        return highlighted;
    }

    private void rebuildAddressBook() {
        mAddressBook = new LinkedList<>();
        if (getCursorItemCount() == 0 || getFoundItemCount() == 0) {
            return;
        }

        Map<String, FoundMember> byPhone = new HashMap<>();
        for (FoundMember member : mFound) {
            if (member == null || TextUtils.isEmpty(member.id())) {
                continue;
            }
            indexMemberPhones(byPhone, member);
        }

        Set<String> seen = new HashSet<>();
        for (mCursor.moveToFirst(); !mCursor.isAfterLast(); mCursor.moveToNext()) {
            String number = mCursor.getString(ContactsLoaderCallback.ContactsQuery.PHONE_NUMBER);
            FoundMember matched = resolveByPhone(byPhone, number);
            if (matched == null) {
                continue;
            }

            String displayName = mCursor.getString(ContactsLoaderCallback.ContactsQuery.DISPLAY_NAME);
            String description = addressBookDescription(matched, displayName);
            if (!matchesAddressBookSearch(displayName, number, description, matched.id())) {
                continue;
            }

            String contactId = mCursor.getString(ContactsLoaderCallback.ContactsQuery.CONTACT_ID);
            String dedupeKey = contactId + ":" + matched.id();
            if (!seen.add(dedupeKey)) {
                continue;
            }

            String photoUri = mCursor.getString(ContactsLoaderCallback.ContactsQuery.PHOTO_THUMBNAIL_DATA);
            mAddressBook.add(new AddressBookEntry(
                    matched.id(),
                    displayName,
                    photoUri,
                    description,
                    dedupeKey,
                    matched.pub()));
        }
    }

    private boolean matchesAddressBookSearch(String displayName, String phone, String description, String id) {
        if (TextUtils.isEmpty(mSearchTerm)) {
            return true;
        }
        return matchesSearch(displayName) ||
                matchesSearch(phone) ||
                matchesSearch(description) ||
                matchesSearch(id);
    }

    private boolean matchesSearch(String value) {
        return !TextUtils.isEmpty(value) && UtilsString.indexOfSearchQuery(value, mSearchTerm) >= 0;
    }

    private static String addressBookDescription(FoundMember member, String displayName) {
        if (member.pub() != null && !TextUtils.isEmpty(member.pub().fn) && !member.pub().fn.equals(displayName)) {
            return member.pub().fn;
        }
        if (member.priv() != null && member.priv().length > 0) {
            List<String> tags = new LinkedList<>();
            for (String val : member.priv()) {
                if (!TextUtils.isEmpty(val) && !val.startsWith("tel:")) {
                    tags.add(val);
                }
            }
            if (!tags.isEmpty()) {
                return TextUtils.join(", ", tags);
            }
        }
        return member.id();
    }

    private static FoundMember resolveByPhone(Map<String, FoundMember> byPhone, String phone) {
        for (String variant : normalizedPhoneVariants(phone)) {
            FoundMember matched = byPhone.get(variant);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private static void indexMemberPhones(Map<String, FoundMember> byPhone, FoundMember member) {
        if (member.pub() != null && member.pub().tel != null) {
            for (VxCard.Contact phone : member.pub().tel) {
                for (String variant : normalizedPhoneVariants(stripPhoneScheme(phone.uri))) {
                    byPhone.putIfAbsent(variant, member);
                }
            }
        }
        if (member.priv() != null) {
            for (String match : member.priv()) {
                if (match != null && match.startsWith("tel:")) {
                    for (String variant : normalizedPhoneVariants(stripPhoneScheme(match))) {
                        byPhone.putIfAbsent(variant, member);
                    }
                }
            }
        }
    }

    private static String stripPhoneScheme(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return raw;
        }
        int idx = raw.indexOf(':');
        if (idx > 0 && idx + 1 < raw.length()) {
            String prefix = raw.substring(0, idx).toLowerCase();
            if ("tel".equals(prefix) || "phone".equals(prefix)) {
                return raw.substring(idx + 1);
            }
        }
        return raw;
    }

    private static List<String> normalizedPhoneVariants(String raw) {
        List<String> variants = new LinkedList<>();
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

    private record AddressBookEntry(String id, String displayName, String photoUri, String description,
                                    String stableKey, VxCard pub) {
    }

    private static boolean matchesAvatarRequest(ImageView avatarView, String requestKey) {
        Object tag = avatarView.getTag(R.id.avatar);
        return requestKey.equals(tag);
    }
}
