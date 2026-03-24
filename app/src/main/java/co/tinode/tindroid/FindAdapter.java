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
import androidx.recyclerview.widget.RecyclerView;

import co.tinode.tindroid.media.VxCard;
import co.tinode.tinodesdk.Topic;
import co.tinode.tinodesdk.model.Subscription;

import coil.Coil;
import coil.request.ImageRequest;
import coil.size.Scale;

/**
 * FindAdapter merges results from searching local Contacts with remote 'fnd' topic.
 */
public class FindAdapter extends RecyclerView.Adapter<FindAdapter.ViewHolder>
        implements ContactsLoaderCallback.CursorSwapper {

    private final TextAppearanceSpan mHighlightTextSpan;
    private final ClickListener mClickListener;
    private List<FoundMember> mFound;
    private List<AddressBookEntry> mAddressBook;
    private Cursor mCursor;
    private String mSearchTerm;
    // TRUE is user granted access to contacts, FALSE otherwise.
    private boolean mPermissionGranted = false;

    FindAdapter(Context context, @NonNull ClickListener clickListener) {
        super();

        mCursor = null;

        mClickListener = clickListener;

        setHasStableIds(true);

        mHighlightTextSpan = new TextAppearanceSpan(context, R.style.searchTextHighlight);
    }

    void resetFound(Activity activity, String searchTerm) {
        mFound = new LinkedList<>();
        Collection<Subscription<Object,String[]>> subs = Cache.getTinode().getFndTopic().getSubscriptions();
        if (subs != null) {
            for (Subscription<Object,String[]> s: subs) {
                mFound.add(new FoundMember(s.user == null ? s.topic : s.user, (VxCard) s.pub, s.priv));
            }
        }

        mSearchTerm = searchTerm;
        rebuildAddressBook();
        if (activity != null) {
            activity.runOnUiThread(this::notifyDataSetChanged);
        }
    }

    void setContactsPermission(boolean granted) {
        mPermissionGranted = granted;
    }

    void setSearchTerm(String searchTerm) {
        if (TextUtils.equals(mSearchTerm, searchTerm)) {
            return;
        }
        mSearchTerm = searchTerm;
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

        // Notify the observers about the new cursor
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
        } else if (viewType == R.layout.contact_section) {
            return new ViewHolderSection(view);
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

    // Clear the avatar: there is some bug(?) in RecyclerView(?) which causes avatars to be
    // displayed in the wrong places.
    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder instanceof ViewHolderItem) {
            ImageView avatar = ((ViewHolderItem) holder).avatar;
            if (avatar != null) {
                avatar.setImageDrawable(null);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (TextUtils.isEmpty(mSearchTerm)) {
            // Self topic is present.
            if (position == 0) {
                return R.layout.contact;
            }

            position--;
        }

        if (position == 0) {
            // Phone contacts section title.
            return R.layout.contact_section;
        }

        // Subtract section title.
        position--;

        int count = getAddressBookItemCount();
        if (count == 0) {
            if (position == 0) {
                // The 'empty' element in the 'PHONE CONTACTS' section.
                return mPermissionGranted ? R.layout.not_found : R.layout.no_permission;
            }
            // One 'empty' element
            count = 1;
        } else if (position < count) {
            return R.layout.contact;
        }

        position -= count;

        if (position == 0) {
            return R.layout.contact_section;
        }

        position--;

        count = getFoundItemCount();
        if (count == 0 && position == 0) {
            return TextUtils.isEmpty(mSearchTerm) ? R.layout.no_search_query : R.layout.not_found;
        }

        return R.layout.contact;
    }

    @Override
    public long getItemId(int position) {
        if (TextUtils.isEmpty(mSearchTerm)) {
            // Self topic is present.
            if (position == 0) {
                return "slf".hashCode();
            }

            position--;
        }

        if (position == 0) {
            return "section_one".hashCode();
        }

        // Subtract section title.
        position--;

        int count = getAddressBookItemCount();
        if (count == 0) {
            if (position == 0) {
                // The 'empty' element in the 'PHONE CONTACTS' section.
                return ("empty_one" + mPermissionGranted).hashCode();
            }

            count = 1;
        } else if (position < count) {
            // Element from address book list.
            String unique = mAddressBook.get(position).stableKey();
            return ("contact:" + unique).hashCode();
        }

        // Skip all address book elements
        position -= count;

        if (position == 0) {
            // Section title DIRECTORY;
            return "section_two".hashCode();
        }

        // Subtract section title.
        position--;

        count = getFoundItemCount();
        if (count == 0 && position == 0) {
            // The 'empty' element in the DIRECTORY section.
            return ("empty_two" + TextUtils.isEmpty(mSearchTerm)).hashCode();
        }

        return ("found:" + mFound.get(position).id).hashCode();
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
        if (TextUtils.isEmpty(mSearchTerm)) {
            // Self topic is present.
            if (position == 0) {
                return new FoundMember("slf", null, null);
            }

            position--;
        }

        if (position == 0) {
            // Section title 'PHONE CONTACTS';
            return R.string.contacts_section_contacts;
        }

        // Subtract section title.
        position--;

        // Count the section title element.
        int count = getAddressBookItemCount();
        if (count == 0) {
            if (position == 0) {
                // The 'empty' element in the 'PHONE CONTACTS' section.
                return null;
            }
            count = 1;
        } else if (position < count) {
            return mAddressBook.get(position);
        }

        position -= count;

        if (position == 0) {
            // Section title DIRECTORY;
            return R.string.contacts_section_directory;
        }

        // Skip the 'DIRECTORY' element;
        position--;

        count = getFoundItemCount();
        if (count == 0 && position == 0) {
            // The 'empty' element in the DIRECTORY section.
            return null;
        }

        return mFound.get(position);
    }

    @Override
    public int getItemCount() {
        // At least 2 section titles.
        int itemCount = 2;

        if (TextUtils.isEmpty(mSearchTerm)) {
            // Self topic is present.
            itemCount++;
        }

        int count = getFoundItemCount();
        itemCount += count == 0 ? 1 : count;
        count = getAddressBookItemCount();
        itemCount += count == 0 ? 1 : count;

        return itemCount;
    }

    interface ClickListener {
        void onClick(String topicName);
    }

    static class ViewHolderSection extends ViewHolder {
        ViewHolderSection(@NonNull View item) {
            super(item);
        }

        public void bind(int position, Object data) {
            ((TextView) itemView).setText((int) data);
        }
    }

    static class ViewHolderEmpty extends ViewHolder {
        ViewHolderEmpty(@NonNull View item) {
            super(item);
        }

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

            avatar.setImageDrawable(fallbackAvatar);

            final int startIndex = UtilsString.indexOfSearchQuery(displayName, mSearchTerm);

            if (startIndex == -1) {
                // If the user didn't do a search, or the search string didn't match a display
                // name, show the display name without highlighting
                name.setText(displayName);

                if (TextUtils.isEmpty(description)) {
                    contactPriv.setVisibility(View.GONE);
                } else {
                    contactPriv.setText(description);
                    contactPriv.setVisibility(View.VISIBLE);
                }
            } else {
                // If the search string matched the display name, applies a SpannableString to
                // highlight the search string with the displayed display name

                // Wraps the display name in the SpannableString
                final SpannableString highlightedName = new SpannableString(displayName);

                // Sets the span to start at the starting point of the match and end at "length"
                // characters beyond the starting point.
                highlightedName.setSpan(mHighlightTextSpan, startIndex,
                        startIndex + mSearchTerm.length(), 0);

                // Binds the SpannableString to the display name View object
                name.setText(highlightedName);

                if (TextUtils.isEmpty(description)) {
                    contactPriv.setVisibility(View.GONE);
                } else {
                    contactPriv.setText(description);
                    contactPriv.setVisibility(View.VISIBLE);
                }
            }

            if (!TextUtils.isEmpty(photoUri)) {
                Coil.imageLoader(context).enqueue(
                        new ImageRequest.Builder(context)
                            .data(photoUri)
                            .placeholder(fallbackAvatar)
                            .error(fallbackAvatar)
                            .target(avatar)
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
            final String userId = member.id;

            avatar.setImageDrawable(null);
            UiUtils.setAvatar(avatar, member.pub, userId, false);
            if (member.pub != null) {
                name.setText(member.pub.fn);
                name.setTypeface(null, Typeface.NORMAL);
            } else if (Topic.isSlfType(userId)) {
                name.setText(R.string.self_topic_title);
                name.setTypeface(null, Typeface.NORMAL);
            } else {
                name.setText(R.string.placeholder_contact_title);
                name.setTypeface(null, Typeface.ITALIC);
            }

            if (member.priv != null) {
                String matched = TextUtils.join(", ", member.priv);
                final SpannableString highlightedName = new SpannableString(matched);
                final int startIndex = UtilsString.indexOfSearchQuery(matched, mSearchTerm);
                if (startIndex >= 0) {
                    highlightedName.setSpan(mHighlightTextSpan, startIndex,
                            startIndex + mSearchTerm.length(), 0);
                }
                contactPriv.setText(highlightedName);
            } else if (Topic.isSlfType(userId)) {
                contactPriv.setText(R.string.self_topic_description);
            } else {
                contactPriv.setText("");
            }

            itemView.setOnClickListener(view -> clickListener.onClick(userId));
        }
    }

    private void rebuildAddressBook() {
        mAddressBook = new LinkedList<>();
        if (getCursorItemCount() == 0 || getFoundItemCount() == 0) {
            return;
        }

        Map<String, FoundMember> byPhone = new HashMap<>();
        for (FoundMember member : mFound) {
            if (member == null || TextUtils.isEmpty(member.id)) {
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

            String contactId = mCursor.getString(ContactsLoaderCallback.ContactsQuery.CONTACT_ID);
            String dedupeKey = contactId + ":" + matched.id;
            if (!seen.add(dedupeKey)) {
                continue;
            }

            String displayName = mCursor.getString(ContactsLoaderCallback.ContactsQuery.DISPLAY_NAME);
            String photoUri = mCursor.getString(ContactsLoaderCallback.ContactsQuery.PHOTO_THUMBNAIL_DATA);
            mAddressBook.add(new AddressBookEntry(
                    matched.id,
                    displayName,
                    photoUri,
                    addressBookDescription(matched, displayName),
                    dedupeKey,
                    matched.pub));
        }
    }

    private static String addressBookDescription(FoundMember member, String displayName) {
        if (member.pub != null && !TextUtils.isEmpty(member.pub.fn) && !member.pub.fn.equals(displayName)) {
            return member.pub.fn;
        }
        if (member.priv != null && member.priv.length > 0) {
            List<String> tags = new LinkedList<>();
            for (String val : member.priv) {
                if (!TextUtils.isEmpty(val) && !val.startsWith("tel:")) {
                    tags.add(val);
                }
            }
            if (!tags.isEmpty()) {
                return TextUtils.join(", ", tags);
            }
        }
        return member.id;
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
        if (member.pub != null && member.pub.tel != null) {
            for (VxCard.Contact phone : member.pub.tel) {
                for (String variant : normalizedPhoneVariants(stripPhoneScheme(phone.uri))) {
                    byPhone.putIfAbsent(variant, member);
                }
            }
        }
        if (member.priv != null) {
            for (String match : member.priv) {
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

    private record FoundMember(String id, VxCard pub, String[] priv) {
    }

    private record AddressBookEntry(String id, String displayName, String photoUri, String description,
                                    String stableKey, VxCard pub) {
    }
}
