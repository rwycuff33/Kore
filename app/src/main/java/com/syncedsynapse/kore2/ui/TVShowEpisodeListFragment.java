/*
 * Copyright 2015 Synced Synapse. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.syncedsynapse.kore2.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorTreeAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.syncedsynapse.kore2.R;
import com.syncedsynapse.kore2.Settings;
import com.syncedsynapse.kore2.host.HostInfo;
import com.syncedsynapse.kore2.host.HostManager;
import com.syncedsynapse.kore2.jsonrpc.event.MediaSyncEvent;
import com.syncedsynapse.kore2.provider.MediaContract;
import com.syncedsynapse.kore2.service.LibrarySyncService;
import com.syncedsynapse.kore2.utils.LogUtils;
import com.syncedsynapse.kore2.utils.UIUtils;

import butterknife.ButterKnife;
import butterknife.InjectView;
import de.greenrobot.event.EventBus;

/**
 * Presents a list of episodes for a TV show
 */
public class TVShowEpisodeListFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor>,
        SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = LogUtils.makeLogTag(TVShowEpisodeListFragment.class);

    public interface OnEpisodeSelectedListener {
        public void onEpisodeSelected(int tvshowId, int episodeId);
    }

    public static final String TVSHOWID = "tvshow_id";
    public static final String SEASON = "season";

    // Loader IDs. Must be -1 to differentiate from group position
    private static final int LOADER_SEASONS = -1;

    // Displayed show id
    private int tvshowId = -1;

    // Activity listener
    private OnEpisodeSelectedListener listenerActivity;

    private CursorTreeAdapter adapter;

    private HostManager hostManager;
    private HostInfo hostInfo;
    private EventBus bus;

    @InjectView(R.id.list) ExpandableListView seasonsEpisodesListView;
    @InjectView(R.id.swipe_refresh_layout) SwipeRefreshLayout swipeRefreshLayout;
    @InjectView(android.R.id.empty) TextView emptyView;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        tvshowId = getArguments().getInt(TVSHOWID, -1);
        if ((container == null) || (tvshowId == -1)) {
            // We're not being shown or there's nothing to show
            return null;
        }

        bus = EventBus.getDefault();
        hostManager = HostManager.getInstance(getActivity());
        hostInfo = hostManager.getHostInfo();

        ViewGroup root = (ViewGroup) inflater.inflate(R.layout.fragment_tvshow_episodes_list, container, false);
        ButterKnife.inject(this, root);

        swipeRefreshLayout.setOnRefreshListener(this);
        //UIUtils.setSwipeRefreshLayoutColorScheme(swipeRefreshLayout);

        return root;
    }

    @Override
    public void onActivityCreated (Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        seasonsEpisodesListView.setEmptyView(emptyView);
        seasonsEpisodesListView.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            @Override
            public boolean onGroupClick(ExpandableListView parent, View v, int groupPosition, long id) {
                if (parent.isGroupExpanded(groupPosition)) {
                    parent.collapseGroup(groupPosition);
                } else {
                    parent.expandGroup(groupPosition);
                }
                return true;
            }
        });
        seasonsEpisodesListView.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
                // Get the movie id from the tag
                EpisodeViewHolder tag = (EpisodeViewHolder)v.getTag();
                // Notify the activity
                listenerActivity.onEpisodeSelected(tvshowId, tag.episodeId);
                return true;
            }
        });

        // Configure the adapter and start the loader
        adapter = new SeasonsEpisodesAdapter(getActivity());
        getLoaderManager().initLoader(LOADER_SEASONS, null, this);
        seasonsEpisodesListView.setAdapter(adapter);

        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            listenerActivity = (OnEpisodeSelectedListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement OnEpisodeSelectedListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listenerActivity = null;
    }

    @Override
    public void onResume() {
        bus.register(this);

        super.onResume();
    }

    @Override
    public void onPause() {
        bus.unregister(this);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
//        LogUtils.LOGD(TAG, "onSaveInstanceState");
//        outState.putInt(TVSHOWID, tvshowId);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.tvshow_episode_list, menu);

        // Setup filters
        Settings settings = Settings.getInstance(getActivity());
        menu.findItem(R.id.action_hide_watched)
            .setChecked(settings.tvshowEpisodesFilterHideWatched);

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_hide_watched:
                if (item.isChecked())
                    item.setChecked(false);
                else
                    item.setChecked(true);
                Settings settings = Settings.getInstance(getActivity());
                settings.tvshowEpisodesFilterHideWatched = item.isChecked();
                settings.save();
                getLoaderManager().restartLoader(LOADER_SEASONS, null, this);
                break;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }


    /**
     * Swipe refresh layout callback
     */
    /** {@inheritDoc} */
    @Override
    public void onRefresh () {
        if (hostInfo != null) {
            startSync(false);
        } else {
            swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(getActivity(), R.string.no_xbmc_configured, Toast.LENGTH_SHORT)
                 .show();
        }
    }

    private void startSync(boolean silentRefresh) {
        // Start the syncing process
        Intent syncIntent = new Intent(this.getActivity(), LibrarySyncService.class);
        syncIntent.putExtra(LibrarySyncService.SYNC_SINGLE_TVSHOW, true);
        syncIntent.putExtra(LibrarySyncService.SYNC_TVSHOWID, tvshowId);

        Bundle syncExtras = new Bundle();
        syncExtras.putBoolean(LibrarySyncService.SILENT_SYNC, silentRefresh);
        syncIntent.putExtra(LibrarySyncService.SYNC_EXTRAS, syncExtras);

        getActivity().startService(syncIntent);
    }

    /**
     * Event bus post. Called when the syncing process ended
     *
     * @param event Refreshes data
     */
    public void onEventMainThread(MediaSyncEvent event) {
        if (event.syncType.equals(LibrarySyncService.SYNC_SINGLE_TVSHOW) ||
            event.syncType.equals(LibrarySyncService.SYNC_ALL_TVSHOWS)) {
            swipeRefreshLayout.setRefreshing(false);
            if (event.status == MediaSyncEvent.STATUS_SUCCESS) {
                getLoaderManager().restartLoader(LOADER_SEASONS, null, this);
                // This message will be displayed by the fragment TVShowOverview,
                // which is already loaded, and this event also gets handled by it,
                // so there's no need to show this message here.
//                Toast.makeText(getActivity(), R.string.sync_successful, Toast.LENGTH_SHORT)
//                     .show();
//            } else {
//                Toast.makeText(getActivity(),
//                        String.format(getString(R.string.error_while_syncing), event.errorMessage),
//                        Toast.LENGTH_SHORT)
//                     .show();
            }
        }
    }

    /**
     * Loader callbacks
     */
    /** {@inheritDoc} */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle bundle) {
        if (!isAdded()) {
            LogUtils.LOGD(TAG, "Trying to create a loader, but the fragment isn't added. " +
                               "Loader Id: " + id);
            return null;
        }

        Uri uri;
        StringBuilder selection = new StringBuilder();
        Settings settings = Settings.getInstance(getActivity());
        switch (id) {
            case LOADER_SEASONS:
                // Load seasons
                uri = MediaContract.Seasons.buildTVShowSeasonsListUri(hostInfo.getId(), tvshowId);

                // Filters
                if (settings.tvshowEpisodesFilterHideWatched) {
                    selection.append(MediaContract.SeasonsColumns.WATCHEDEPISODES)
                             .append("!=")
                             .append(MediaContract.SeasonsColumns.EPISODE);
                }

                return new CursorLoader(getActivity(), uri,
                        SeasonsListQuery.PROJECTION, selection.toString(), null, SeasonsListQuery.SORT);
            default:
                // Load episodes for a season. Season is in bundle
                int season = bundle.getInt(SEASON);
                uri = MediaContract.Episodes.buildTVShowSeasonEpisodesListUri(hostInfo.getId(), tvshowId, season);

                // Filters
                if (settings.tvshowEpisodesFilterHideWatched) {
                    selection.append(MediaContract.EpisodesColumns.PLAYCOUNT)
                             .append("=0");
                }

                return new CursorLoader(getActivity(), uri,
                        EpisodesListQuery.PROJECTION, selection.toString(), null, EpisodesListQuery.SORT);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        LogUtils.LOGD(TAG, "onLoadFinished, Loader id: " + cursorLoader.getId() + ". Rows: " +
                           cursor.getCount());
        switch (cursorLoader.getId()) {
            case LOADER_SEASONS:
                adapter.setGroupCursor(cursor);
                if (cursor.getCount() == 1) {
                    // Force collapse and expand the group, to force a reload of the episodes
                    // cursor, otherwise if it is already expanded it won't reload (and won't
                    // apply the filters to the episodes list)
                    seasonsEpisodesListView.collapseGroup(0);
                    seasonsEpisodesListView.expandGroup(0);
                } else if (cursor.getCount() > 0) {
                    // Expand the first season that has unseen episodes
                    cursor.moveToFirst();
                    do {
                        int unwatched = cursor.getInt(SeasonsListQuery.EPISODE) - cursor.getInt(SeasonsListQuery.WATCHEDEPISODES);
                        if (unwatched > 0) {
                            LogUtils.LOGD(TAG, "Expanding group: " + cursor.getPosition());
                            seasonsEpisodesListView.collapseGroup(cursor.getPosition());
                            seasonsEpisodesListView.expandGroup(cursor.getPosition());
                            break;
                        }
                    } while (cursor.moveToNext());
                }
                // To prevent the empty text from appearing on the first load, set it now
                emptyView.setText(getString(R.string.no_episodes_found));
                break;
            default:
                // Check if the group cursor is set before setting the children cursor
                // Somehow, when poping the back stack, the children cursor are reloaded first...
                if (adapter.getCursor() != null) {
                    adapter.setChildrenCursor(cursorLoader.getId(), cursor);
                }
                break;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        switch (cursorLoader.getId()) {
            case LOADER_SEASONS:
                adapter.setGroupCursor(null);
                break;
            default:
                // Check if the group cursor is set before setting the children cursor
                // Somehow, when poping the back stack, the children cursor are reloaded first...
                if (adapter.getCursor() != null) {
                    adapter.setChildrenCursor(cursorLoader.getId(), null);
                }
                break;
        }
    }

    /**
     * Seasons list query parameters.
     */
    private interface SeasonsListQuery {
        String[] PROJECTION = {
                BaseColumns._ID,
                MediaContract.Seasons.SEASON,
                MediaContract.Seasons.THUMBNAIL,
                MediaContract.Seasons.EPISODE,
                MediaContract.Seasons.WATCHEDEPISODES
        };

        String SORT = MediaContract.Seasons.SEASON + " ASC";

        final int ID = 0;
        final int SEASON = 1;
        final int THUMBNAIL = 2;
        final int EPISODE = 3;
        final int WATCHEDEPISODES = 4;
    }

    /**
     * Episodes list query parameters.
     */
    private interface EpisodesListQuery {
        String[] PROJECTION = {
                BaseColumns._ID,
                MediaContract.Episodes.EPISODEID,
                MediaContract.Episodes.EPISODE,
                MediaContract.Episodes.THUMBNAIL,
                MediaContract.Episodes.PLAYCOUNT,
                MediaContract.Episodes.TITLE,
                MediaContract.Episodes.RUNTIME,
                MediaContract.Episodes.FIRSTAIRED,
        };

        String SORT = MediaContract.Episodes.EPISODE + " ASC";

        final int ID = 0;
        final int EPISODEID = 1;
        final int EPISODE = 2;
        final int THUMBNAIL = 3;
        final int PLAYCOUNT = 4;
        final int TITLE = 5;
        final int RUNTIME = 6;
        final int FIRSTAIRED = 7;
    }

    /**
     * Adapter for the {@link android.widget.ExpandableListView}
     * Manages the seasons and episodes list
     */
    private class SeasonsEpisodesAdapter extends CursorTreeAdapter {

        private int themeAccentColor;
        private int separatorPadding;
        private int iconCollapseResId,
                iconExpandResId;

        private HostManager hostManager;
        private int artWidth, artHeight;

        public SeasonsEpisodesAdapter(Context context) {
            // Cursor will be set vir CursorLoader
            super(null, context);

            // Get the default accent color
            Resources.Theme theme = context.getTheme();
            TypedArray styledAttributes = theme.obtainStyledAttributes(new int[] {
                    R.attr.colorAccent,
                    R.attr.iconCollapse,
                    R.attr.iconExpand,
            });
            themeAccentColor = styledAttributes.getColor(0, R.color.accent_default);
            iconCollapseResId = styledAttributes.getResourceId(1, R.drawable.ic_expand_less_white_24dp);
            iconExpandResId = styledAttributes.getResourceId(2, R.drawable.ic_expand_more_white_24dp);
            styledAttributes.recycle();

            this.hostManager = HostManager.getInstance(context);

            // Get the art dimensions
            Resources resources = context.getResources();
            artWidth = (int)(resources.getDimension(R.dimen.seasonlist_art_width) /
                             UIUtils.IMAGE_RESIZE_FACTOR);
            artHeight = (int)(resources.getDimension(R.dimen.seasonlist_art_heigth) /
                              UIUtils.IMAGE_RESIZE_FACTOR);
            separatorPadding = resources.getDimensionPixelSize(R.dimen.small_padding);
        }

        @Override
        public View newGroupView(Context context, Cursor cursor, boolean isExpanded, ViewGroup parent) {
            final View view = LayoutInflater.from(context)
                                            .inflate(R.layout.list_item_season, parent, false);
            return view;
        }

        @Override
        public View newChildView(Context context, Cursor cursor, boolean isLastChild, ViewGroup parent) {
            final View view = LayoutInflater.from(context)
                                            .inflate(R.layout.list_item_episode, parent, false);

            // Setup View holder pattern
            EpisodeViewHolder viewHolder = new EpisodeViewHolder();
            viewHolder.container = (RelativeLayout)view.findViewById(R.id.container);
            viewHolder.titleView = (TextView)view.findViewById(R.id.title);
            viewHolder.detailsView = (TextView)view.findViewById(R.id.details);
            viewHolder.episodenumberView = (TextView)view.findViewById(R.id.episode_number);
//            viewHolder.artView = (ImageView)view.findViewById(R.id.art);
            viewHolder.checkmarkView = (ImageView)view.findViewById(R.id.checkmark);

            view.setTag(viewHolder);
            return view;
        }

        @Override
        public void bindGroupView(View view, Context context, Cursor cursor, boolean isExpanded) {
            TextView seasonView = (TextView)view.findViewById(R.id.season);
            TextView episodesView = (TextView)view.findViewById(R.id.episodes);
            ImageView artView = (ImageView)view.findViewById(R.id.art);

            seasonView.setText(String.format(context.getString(R.string.season_number),
                    cursor.getInt(SeasonsListQuery.SEASON)));
            int numEpisodes = cursor.getInt(SeasonsListQuery.EPISODE),
                    watchedEpisodes = cursor.getInt(SeasonsListQuery.WATCHEDEPISODES);
            episodesView.setText(String.format(context.getString(R.string.num_episodes),
                    numEpisodes, numEpisodes - watchedEpisodes));

            UIUtils.loadImageWithCharacterAvatar(context, hostManager,
                    cursor.getString(SeasonsListQuery.THUMBNAIL),
                    String.valueOf(cursor.getInt(SeasonsListQuery.SEASON)),
                    artView, artWidth, artHeight);

            ImageView indicator = (ImageView)view.findViewById(R.id.status_indicator);
            if (isExpanded) {
//                view.setPadding(0, separatorPadding, 0, 0);
                indicator.setImageResource(iconCollapseResId);
            } else {
                indicator.setImageResource(iconExpandResId);
//                view.setPadding(0, separatorPadding, 0, separatorPadding);
            }
        }

        @Override
        public void bindChildView(View view, Context context, Cursor cursor, boolean isLastChild) {
            final EpisodeViewHolder viewHolder = (EpisodeViewHolder)view.getTag();

            // Save the episode id
            viewHolder.episodeId = cursor.getInt(EpisodesListQuery.EPISODEID);

            viewHolder.episodenumberView.setText(
                    String.format(context.getString(R.string.episode_number),
                    cursor.getInt(EpisodesListQuery.EPISODE)));
            int runtime = cursor.getInt(EpisodesListQuery.RUNTIME) / 60;
            String duration =  runtime > 0 ?
                               String.format(context.getString(R.string.minutes_abbrev), String.valueOf(runtime)) +
                               "  |  " + cursor.getString(EpisodesListQuery.FIRSTAIRED) :
                               cursor.getString(EpisodesListQuery.FIRSTAIRED);
            viewHolder.titleView.setText(cursor.getString(EpisodesListQuery.TITLE));
            viewHolder.detailsView.setText(duration);

            if (cursor.getInt(EpisodesListQuery.PLAYCOUNT) > 0) {
                viewHolder.checkmarkView.setVisibility(View.VISIBLE);
                viewHolder.checkmarkView.setColorFilter(themeAccentColor);
            } else {
                viewHolder.checkmarkView.setVisibility(View.GONE);
            }

//            if (isLastChild) {
//                view.setPadding(0, 0, 0, separatorPadding);
//            } else {
//                view.setPadding(0, 0, 0, 0);
//            }

//            UIUtils.loadImageIntoImageview(hostManager,
//                    cursor.getString(EpisodesListQuery.THUMBNAIL),
//                    viewHolder.artView);
        }

        @Override
        public Cursor getChildrenCursor(Cursor groupCursor) {
            // Check if the fragment is attached to avoid IllegalStateException...
            if (!isAdded()) return null;

            // Start the episodes loader
            final int season = groupCursor.getInt(SeasonsListQuery.SEASON);
            Bundle bundle = new Bundle();
            bundle.putInt(SEASON, season);
            int groupPositon = groupCursor.getPosition();
            // The season id will be passed in a bundle to the loadermanager, and the group id
            // will be used as the loader's id
            LoaderManager loaderManager = getLoaderManager();
            if ((loaderManager.getLoader(groupPositon) == null) ||
                (loaderManager.getLoader(groupPositon).isReset())) {
                loaderManager.initLoader(groupPositon, bundle, TVShowEpisodeListFragment.this);
            } else {
                loaderManager.restartLoader(groupPositon, bundle, TVShowEpisodeListFragment.this);
            }

            return null;
        }
    }

    /**
     * View holder pattern, only for episodes
     */
    private static class EpisodeViewHolder {
        RelativeLayout container;
        TextView titleView;
        TextView detailsView;
        TextView episodenumberView;
//        ImageView artView;
        ImageView checkmarkView;

        int episodeId;
    }

}
