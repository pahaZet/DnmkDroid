package co.tinode.tindroid;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class ChatsSearchViewModel extends ViewModel {
    private final MutableLiveData<String> mQuery = new MutableLiveData<>("");

    LiveData<String> getQuery() {
        return mQuery;
    }

    void setQuery(String query) {
        mQuery.setValue(query != null ? query : "");
    }
}
