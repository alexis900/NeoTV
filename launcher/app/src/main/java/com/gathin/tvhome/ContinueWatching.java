package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Continue watching row's data: the system's Watch Next table (docs/watch-next.md), read once per Home
 * resume off the main thread. Needs READ_TV_LISTINGS (build-and-install grants it); without it the query
 * throws and the row stays hidden.
 *
 * Only local posters are loaded (content://, android.resource://, file://). Most apps publish https://
 * poster URLs on their own CDNs; this app has no INTERNET permission, so those rows get a text card instead
 * and Home never makes a network request.
 */
final class ContinueWatching {
    static final String TAG = "TvHomeContinue";
    /** Rows older than this are dropped: some apps leave them for months (docs/watch-next.md, TCL C6K). */
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    /** A "continue" row this far through is finished in all but name. */
    private static final double DONE_FRACTION = 0.95;
    static final int MAX_ITEMS = 10;
    /** Poster decode width; a card is 160dp wide, 2x on this box. */
    private static final int POSTER_PX = 360;

    static final class Item {
        final String pkg;
        final String title;
        final String subtitle;
        final String intentUri;
        final float progress;      // 0..1, or -1 when the app gives no position
        final String posterUri;    // local posters only; null: the app's banner
        /** http(s) poster (Jellyfin): fetched with posterToken, null for Watch Next rows. */
        final String posterUrl;
        final String posterToken;
        /** Jellyfin's own id for the item, so a card opens it in the client; null for Watch Next rows. */
        String itemId;
        Bitmap poster;             // filled in by loadPoster, off the main thread

        Item(String pkg, String title, String subtitle, String intentUri, float progress, String posterUri) {
            this(pkg, title, subtitle, intentUri, progress, posterUri, null, null);
        }

        Item(String pkg, String title, String subtitle, String intentUri, float progress, String posterUri,
             String posterUrl, String posterToken) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.intentUri = intentUri;
            this.progress = progress;
            this.posterUri = posterUri;
            this.posterUrl = posterUrl;
            this.posterToken = posterToken;
        }
    }

    private ContinueWatching() {
    }

    /** Runs on a background thread. Never throws: any failure is one log line and an empty list. */
    static List<Item> load(Context context) {
        List<Item> items = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        PackageManager pm = context.getPackageManager();
        long now = System.currentTimeMillis();
        Map<String, Integer> perPackage = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        List<long[]> order = new ArrayList<>();   // {engagement, index into items}
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException when it passes one.
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) {
                Log.w(TAG, "Watch Next query returned no cursor (no TV provider?)");
                return items;
            }
            while (c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (pkg == null) continue;
                perPackage.merge(pkg, 1, Integer::sum);
                if (lng(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0);
                if (engaged > 0 && now - engaged > MAX_AGE_MS) continue;
                String title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                if (title == null || title.isEmpty()) continue;
                if (!seen.add(pkg + '\n' + title)) continue;   // one card per show
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;

                long pos = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                long dur = lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, 0);
                float progress = pos > 0 && dur > 0 ? (float) Math.min(1.0, (double) pos / dur) : -1f;
                int type = (int) lng(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
                if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && progress >= DONE_FRACTION) continue;

                String poster = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                if (poster == null) poster = str(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                Item item = new Item(pkg, title, subtitle(c), str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI),
                        progress, isLocal(poster) ? poster : null);
                item.poster = cached(item.posterUri);
                order.add(new long[]{engaged, items.size()});
                items.add(item);
            }
        } catch (Exception e) {
            // SecurityException without READ_TV_LISTINGS, or a provider that is missing or broken.
            Log.w(TAG, "Watch Next not readable: " + e);
            return new ArrayList<>();
        }
        Log.i(TAG, "Watch Next rows per app: " + perPackage + ", shown: " + Math.min(items.size(), MAX_ITEMS));
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        List<Item> sorted = new ArrayList<>();
        for (long[] o : order) {
            if (sorted.size() == MAX_ITEMS) break;
            sorted.add(items.get((int) o[1]));
        }
        return sorted;
    }

    /** Jellyfin clients that can play a card, best first; the first installed one is opened. */
    private static final String[] JELLYFIN_CLIENTS = {
            "org.jellyfin.androidtv",           // Jellyfin for Android TV
            "com.github.damontecres.wholphin",  // Wholphin
            "org.jellyfin.mobile",              // Jellyfin for Android
            "dev.jdtech.jellyfin",              // Findroid
    };

    /** True when this package is a Jellyfin client the row reads from and can open items in. */
    static boolean isJellyfinClient(String pkg) {
        for (String client : JELLYFIN_CLIENTS) {
            if (client.equals(pkg)) return true;
        }
        return false;
    }

    /** The lists the row can show, in the order the remote's Menu key cycles them. */
    static final int LIST_RESUME = 0, LIST_RECENT = 1, LIST_NEXT_UP = 2;
    static final int LIST_COUNT = 3;

    /**
     * The row read from the Jellyfin server itself (config.xml jellyfin_url + jellyfin_api_key), for a TV whose
     * client does not publish Watch Next rows, or when the owner would rather not depend on one. Runs on a
     * background thread; never throws. The key only ever travels to that server, in a header.
     */
    static List<Item> loadJellyfin(Context context, String base, String key, String user) {
        return loadJellyfin(context, base, key, user, LIST_RESUME);
    }

    /** One of LIST_RESUME, LIST_RECENT or LIST_NEXT_UP; an unknown value reads Resume. */
    static List<Item> loadJellyfin(Context context, String base, String key, String user, int list) {
        List<Item> items = new ArrayList<>();
        String root = base == null ? "" : base.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (!(root.startsWith("http://") || root.startsWith("https://"))) {
            Log.w(TAG, "jellyfin_url is not an http(s) address");
            return items;
        }
        String apiKey = key == null ? "" : key.trim();
        if (apiKey.isEmpty()) return items;
        try {
            String userId = jellyfinUser(root, apiKey, user == null ? "" : user.trim());
            if (userId == null) {
                Log.w(TAG, "Jellyfin: no user matches jellyfin_user, or the key is not accepted");
                return items;
            }
            String fields = "Fields=SeriesId,SeriesPrimaryImageTag,ParentIndexNumber,IndexNumber,"
                    + "UserData,RunTimeTicks,ImageTags&ImageTypeLimit=1&EnableImageTypes=Primary";
            JSONArray rows;
            if (list == LIST_RECENT) {
                // Latest answers with a plain array, not the {"Items": [...]} the other lists use.
                rows = new JSONArray(get(root + "/Users/" + userId + "/Items/Latest?Limit=" + MAX_ITEMS
                        + "&IncludeItemTypes=Series,Movie&" + fields, apiKey));
            } else if (list == LIST_NEXT_UP) {
                // NextUp lives under /Shows, not under /Users/<id>/Items.
                JSONObject body = new JSONObject(get(root + "/Shows/NextUp?UserId=" + userId
                        + "&Limit=" + MAX_ITEMS + "&" + fields + "&EnableTotalRecordCount=false", apiKey));
                rows = body.optJSONArray("Items");
            } else {
                JSONObject body = new JSONObject(get(root + "/Users/" + userId + "/Items/Resume?Limit=" + MAX_ITEMS
                        + "&MediaTypes=Video&" + fields + "&EnableTotalRecordCount=false", apiKey));
                rows = body.optJSONArray("Items");
            }
            if (rows == null) return items;
            String client = jellyfinClient(context);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.length() && items.size() < MAX_ITEMS; i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("Name", "").trim();
                String series = o.optString("SeriesName", "").trim();
                String title = series.isEmpty() ? name : series;
                if (title.isEmpty() || !seen.add(title)) continue;
                long run = o.optLong("RunTimeTicks", 0);
                JSONObject ud = o.optJSONObject("UserData");
                long pos = ud == null ? 0 : ud.optLong("PlaybackPositionTicks", 0);
                // Only Resume has a position worth drawing a bar for: the other lists are things not started.
                float progress = list == LIST_RESUME && run > 0
                        ? (float) Math.min(1.0, (double) pos / run) : -1f;
                if (progress >= DONE_FRACTION) continue;
                String poster = jellyfinPoster(root, o);
                Item item = new Item(client, title, jellyfinSubtitle(o, series, name), null, progress,
                        null, poster, apiKey);
                item.itemId = o.optString("Id", "");
                item.poster = cached(poster);
                items.add(item);
            }
            Log.i(TAG, "Jellyfin " + listName(list) + ": " + items.size() + " rows from " + root);
        } catch (Exception e) {
            // A wrong key (401), a server that is off, or a reply that is not Jellyfin's.
            Log.w(TAG, "Jellyfin not readable at " + root + ": " + e);
            return new ArrayList<>();
        }
        return items;
    }

    private static String listName(int list) {
        return list == LIST_RECENT ? "recent" : list == LIST_NEXT_UP ? "next up" : "resume";
    }

    /** "T2 · E5 · Episode name", or the film's own name as the line under the title. */
    private static String jellyfinSubtitle(JSONObject o, String series, String name) {
        StringBuilder s = new StringBuilder();
        int season = o.optInt("ParentIndexNumber", -1);
        int episode = o.optInt("IndexNumber", -1);
        if (season >= 0) s.append('T').append(season);
        if (episode >= 0) s.append(s.length() > 0 ? " · " : "").append('E').append(episode);
        if (!series.isEmpty() && !name.isEmpty() && !name.equals(series)) {
            s.append(s.length() > 0 ? " · " : "").append(name);
        }
        return s.toString();
    }

    /** The series' poster for an episode, the item's own for a film, or null when Jellyfin has no image. */
    private static String jellyfinPoster(String root, JSONObject o) {
        String id = o.optString("Id", "");
        String seriesId = o.optString("SeriesId", "");
        String seriesTag = o.optString("SeriesPrimaryImageTag", "");
        JSONObject tags = o.optJSONObject("ImageTags");
        String ownTag = tags == null ? "" : tags.optString("Primary", "");
        boolean useSeries = !seriesId.isEmpty() && !seriesTag.isEmpty();
        String imageId = useSeries ? seriesId : id;
        String tag = useSeries ? seriesTag : ownTag;
        if (imageId.isEmpty() || tag.isEmpty()) return null;
        return root + "/Items/" + imageId + "/Images/Primary?maxHeight=480&quality=80&tag=" + Uri.encode(tag);
    }

    /** The account to read: jellyfin_user's name, or the server's first user when it is empty. */
    private static String jellyfinUser(String root, String key, String wanted) throws Exception {
        JSONArray users = new JSONArray(get(root + "/Users", key));
        if (users.length() == 0) return null;
        if (wanted.isEmpty()) return users.getJSONObject(0).optString("Id", "");
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (wanted.equalsIgnoreCase(u.optString("Name", ""))) return u.optString("Id", "");
        }
        return null;
    }

    /** The installed Jellyfin client a card opens, or the default package when none is installed. */
    static String jellyfinClient(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : JELLYFIN_CLIENTS) {
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) {
                return pkg;
            }
        }
        return JELLYFIN_CLIENTS[0];
    }

    /** A JSON reply from the server; the API key travels in the header, never in the URL or a log line. */
    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
        c.setRequestProperty("X-Emby-Token", key);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            byte[] bytes = readAll(c.getInputStream(), 2 * 1024 * 1024);
            if (bytes == null) throw new IOException("reply too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** The whole stream, or null past max bytes: posters and JSON replies are small. */
    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                if (out.size() + n > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** An http(s) poster (Jellyfin's image endpoint), with the API key in the header. */
    private static Bitmap download(String url, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + token + "\"");
                c.setRequestProperty("X-Emby-Token", token);
            }
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "poster HTTP " + c.getResponseCode());
                return null;
            }
            byte[] bytes = readAll(c.getInputStream(), 4 * 1024 * 1024);
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= POSTER_PX) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "poster not downloaded: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "S2 · E5 · Episode title", or whatever part of it the app filled in. */
    private static String subtitle(Cursor c) {
        StringBuilder s = new StringBuilder();
        String season = str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        String name = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
        if (season != null && !season.isEmpty()) s.append('S').append(season);
        if (episode != null && !episode.isEmpty()) s.append(s.length() > 0 ? " · " : "").append('E').append(episode);
        if (name != null && !name.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(name);
        return s.toString();
    }

    /** Decoded posters by URI, so Home coming back does not fetch them again: Jellyfin's provider asks its
     *  server for each one, about four seconds a poster on this network. A dozen 360px RGB_565 posters is
     *  about 3 MB. */
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > MAX_ITEMS + 2;
        }
    };

    private static boolean isLocal(String uri) {
        if (uri == null) return false;
        String scheme = Uri.parse(uri).getScheme();
        return "content".equals(scheme) || "android.resource".equals(scheme) || "file".equals(scheme);
    }

    private static Bitmap cached(String uri) {
        if (uri == null) return null;
        synchronized (CACHE) {
            return CACHE.get(uri);
        }
    }

    /** Off the main thread, one at a time: fills item.poster and returns true when there is one to show. */
    static boolean loadPoster(Context context, Item item) {
        if (item.poster != null) return false;
        String cacheKey = item.posterUrl != null ? item.posterUrl : item.posterUri;
        if (cacheKey == null) return false;
        Bitmap b = cached(cacheKey);
        if (b == null) {
            b = item.posterUrl != null ? download(item.posterUrl, item.posterToken)
                    : decode(context.getContentResolver(), item.posterUri);
            if (b == null) return false;
            synchronized (CACHE) {
                CACHE.put(cacheKey, b);
            }
        }
        item.poster = b;
        return true;
    }

    private static Bitmap decode(ContentResolver cr, String uri) {
        Uri u = Uri.parse(uri);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= POSTER_PX) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, posters have no alpha
            try (InputStream in = cr.openInputStream(u)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "poster not loaded: " + e);
            return null;
        }
    }

    /** The intent the app published, or its own launch intent when that is missing or unparseable. */
    static Intent intentFor(Context context, Item item) {
        if (item.intentUri != null) {
            try {
                Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                // Only the app that published the row: an intent_uri pointing elsewhere is not followed.
                if (i.getPackage() == null && i.getComponent() == null) i.setPackage(item.pkg);
                String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
                if (item.pkg.equals(target)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return i;
                }
            } catch (URISyntaxException e) {
                Log.w(TAG, "bad intent_uri from " + item.pkg + ": " + e);
            }
        }
        PackageManager pm = context.getPackageManager();
        Intent deep = jellyfinItem(context, item);
        if (deep != null) return deep;
        Intent i = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(item.pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * The Jellyfin client's own way in to one item, the one its Watch Next cards use: the Android TV client
     * reads StartupActivity's "ItemId" extra, Wholphin reads itemId from wholphin://view. Null when the item
     * has no id or the client has no known way in, so the card falls back to opening the app.
     */
    private static Intent jellyfinItem(Context context, Item item) {
        if (item.itemId == null || item.itemId.isEmpty()) return null;
        Intent i;
        if (JELLYFIN_CLIENTS[0].equals(item.pkg)) {
            i = new Intent();
            i.setClassName(item.pkg, "org.jellyfin.androidtv.ui.startup.StartupActivity");
            i.putExtra("ItemId", item.itemId);
        } else if (JELLYFIN_CLIENTS[1].equals(item.pkg)) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("wholphin://view?itemId=" + Uri.encode(item.itemId)));
            i.setPackage(item.pkg);
        } else {
            return null;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A client that moved its entry point: open the app rather than fail to start an activity.
        return context.getPackageManager().resolveActivity(i, 0) == null ? null : i;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }
}
