package co.tinode.tindroid;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

public class ChatsSearchFragment extends Fragment implements MenuProvider, ChatListDataSetListener {
    private static final int TAB_COUNT = 2;
    private static final int TAB_CHATS = 0;
    private static final int TAB_GROUPS = 1;

    private static final int[] TAB_NAMES = new int[]{
            R.string.search_tab_chats, R.string.search_tab_groups
    };

    private SearchView mSearchView;
    private ChatsSearchViewModel mSearchViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chats_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final AppCompatActivity activity = (AppCompatActivity) requireActivity();
        activity.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        final ActionBar bar = activity.getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setTitle(R.string.action_search);
        }

        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> activity.getSupportFragmentManager().popBackStack());

        mSearchViewModel = new ViewModelProvider(this).get(ChatsSearchViewModel.class);

        mSearchView = view.findViewById(R.id.searchView);
        setupSearchView();

        final TabLayout tabLayout = view.findViewById(R.id.searchTabs);
        final ViewPager2 viewPager = view.findViewById(R.id.searchPager);
        viewPager.setAdapter(new PagerAdapter(this));
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(TAB_NAMES[position])).attach();

        mSearchViewModel.getQuery().observe(getViewLifecycleOwner(), query -> {
            if (mSearchView == null) {
                return;
            }
            String safeQuery = query != null ? query : "";
            if (!TextUtils.equals(mSearchView.getQuery(), safeQuery)) {
                mSearchView.setQuery(safeQuery, false);
            }
        });
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        menu.clear();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        return false;
    }

    @Override
    public void datasetChanged() {
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment instanceof ChatListDataSetListener) {
                ((ChatListDataSetListener) fragment).datasetChanged();
            }
        }
    }

    private void setupSearchView() {
        if (mSearchView == null) {
            return;
        }

        mSearchView.setIconifiedByDefault(false);
        mSearchView.setSubmitButtonEnabled(false);
        mSearchView.setMaxWidth(Integer.MAX_VALUE);
        mSearchView.setQueryHint(getString(R.string.hint_search_chat_list));

        View plate = mSearchView.findViewById(androidx.appcompat.R.id.search_plate);
        if (plate != null) {
            plate.setBackground(null);
        }

        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mSearchViewModel.setQuery(query);
                mSearchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mSearchViewModel.setQuery(newText);
                return true;
            }
        });

        mSearchView.post(() -> {
            mSearchView.requestFocusFromTouch();
            mSearchView.setIconified(false);
        });
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return switch (position) {
                case TAB_CHATS -> ChatsSearchResultsFragment.newInstance(false);
                case TAB_GROUPS -> ChatsSearchResultsFragment.newInstance(true);
                default -> throw new IllegalArgumentException("Invalid tab position " + position);
            };
        }

        @Override
        public int getItemCount() {
            return TAB_COUNT;
        }
    }
}
