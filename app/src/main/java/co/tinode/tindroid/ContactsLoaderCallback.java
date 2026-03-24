package co.tinode.tindroid;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import co.tinode.tindroid.account.Utils;

class ContactsLoaderCallback implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final String TAG = "ContactsLoader";
    static final String ARG_SEARCH_TERM = "searchTerm";

    enum LoaderMode {
        TINODE_PROFILE,
        PHONE_BOOK
    }

    private final int mID;
    private final Context mContext;
    private final CursorSwapper mAdapter;
    private final LoaderMode mMode;
    private String mSearchTerm;

    ContactsLoaderCallback(int loaderID, Context context, CursorSwapper adapter) {
        this(loaderID, context, adapter, LoaderMode.TINODE_PROFILE);
    }

    ContactsLoaderCallback(int loaderID, Context context, CursorSwapper adapter, LoaderMode mode) {
        mID = loaderID;
        mContext = context;
        mAdapter = adapter;
        mMode = mode;
    }

    void setSearchTerm(String searchTerm) {
        mSearchTerm = searchTerm;
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // If this is the loader for finding contacts in the Contacts Provider
        if (id == mID) {
            mSearchTerm = args != null ? args.getString(ARG_SEARCH_TERM) : mSearchTerm;

            String[] selectionArgs = null;
            String selection = mMode == LoaderMode.PHONE_BOOK ?
                    ContactsQuery.SELECTION_PHONE_BOOK : ContactsQuery.SELECTION_TINODE_PROFILE;

            if (mSearchTerm != null && mMode != LoaderMode.PHONE_BOOK) {
                selection += ContactsQuery.SELECTION_FILTER;
                selectionArgs = new String[]{mSearchTerm + "%"};
            }

            return new CursorLoader(mContext,
                    ContactsQuery.CONTENT_URI,
                    ContactsQuery.PROJECTION,
                    selection,
                    selectionArgs,
                    ContactsQuery.SORT_ORDER);
        }

        throw new IllegalArgumentException("Unknown loader ID " + id);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor data) {
        // This swaps the new cursor into the adapter.
        if (loader.getId() == mID) {
            logLoadedContacts(data);
            mAdapter.swapCursor(data, mSearchTerm);
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        if (loader.getId() == mID) {
            // When the loader is being reset, clear the cursor from the adapter. This allows the
            // cursor resources to be freed.
            mAdapter.swapCursor(null, mSearchTerm);
        }
    }

    /**
     * This interface defines constants for the Cursor and CursorLoader, based on constants defined
     * in the {@link android.provider.ContactsContract.Contacts} class.
     */
    interface ContactsQuery {
        // A content URI for the Contacts table.
        Uri CONTENT_URI = ContactsContract.Data.CONTENT_URI;

        // The selection clause for the CursorLoader query. The search criteria defined here
        // restrict results to contacts that have a display name and are linked to visible groups.
        String SELECTION_TINODE_PROFILE = ContactsContract.Data.DISPLAY_NAME_PRIMARY + "<>'' AND " +
                ContactsContract.Data.MIMETYPE + "='" + Utils.MIME_TINODE_PROFILE + "'";

        // Restrict results to phone rows with non-empty display name.
        String SELECTION_PHONE_BOOK = ContactsContract.Data.DISPLAY_NAME_PRIMARY + "<>'' AND " +
                ContactsContract.Data.MIMETYPE + "='" +
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE + "'";

        // Search by keystrokes.
        String SELECTION_FILTER = " AND " + ContactsContract.Data.DISPLAY_NAME_PRIMARY + " LIKE ?";

        // Search by name or phone in phone book mode.
        String SELECTION_FILTER_PHONE_BOOK = " AND (" + ContactsContract.Data.DISPLAY_NAME_PRIMARY +
                " LIKE ? OR " + ContactsContract.Data.DATA1 + " LIKE ?)";

        // The desired sort order for the returned Cursor.
        String SORT_ORDER = ContactsContract.Data.SORT_KEY_PRIMARY;

        // A list of columns that the Contacts Provider should return in the Cursor.
        String[] PROJECTION = {
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.PHOTO_THUMBNAIL_URI,
                ContactsContract.Data.DATA1,

                // The sort order column for the returned Cursor, used by the AlphabetIndexer
                SORT_ORDER,
        };

        // The query column numbers which map to each value in the projection
        int ID = 0;
        int CONTACT_ID = 1;
        int RAW_CONTACT_ID = 2;
        // int LOOKUP_KEY = 3;
        int DISPLAY_NAME = 4;
        int PHOTO_THUMBNAIL_DATA = 5;
        // Server user id in TINODE_PROFILE mode, phone number in PHONE_BOOK mode.
        int IM_ADDRESS = 6;
        int PHONE_NUMBER = 6;

        int SORT_KEY = 7;
    }

    interface CursorSwapper {
        void swapCursor(Cursor cursor, String searchQuery);
    }

    private void logLoadedContacts(Cursor cursor) {
        if (cursor == null) {
            Log.d(TAG, "mode=" + mMode + ": cursor is null");
            return;
        }

        int oldPosition = cursor.getPosition();
        Log.d(TAG, "mode=" + mMode + ": loaded " + cursor.getCount() + " rows");
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            long contactId = cursor.getLong(ContactsQuery.CONTACT_ID);
            long rawContactId = cursor.getLong(ContactsQuery.RAW_CONTACT_ID);
            String lookupKey = cursor.getString(3);
            String displayName = cursor.getString(ContactsQuery.DISPLAY_NAME);
            String address = cursor.getString(ContactsQuery.IM_ADDRESS);
            String photoThumbUri = cursor.getString(ContactsQuery.PHOTO_THUMBNAIL_DATA);
            Uri lookupUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey);

            Log.d(TAG, "mode=" + mMode
                    + ", rowId=" + cursor.getLong(ContactsQuery.ID)
                    + ", contactId=" + contactId
                    + ", rawContactId=" + rawContactId
                    + ", lookupKey=" + lookupKey
                    + ", lookupUri=" + lookupUri
                    + ", displayName=" + displayName
                    + ", address=" + address
                    + ", photoThumbnailUri=" + photoThumbUri);
        }
        cursor.moveToPosition(oldPosition);
    }
}
