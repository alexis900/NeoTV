package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings backup (Launcher settings → Save a backup): the theme's config.json and every choice the TV
 * saved in launcher_settings, as one JSON file. Restoring writes both back, so a reinstall or a second
 * TV keeps the dock, the hidden apps, the Jellyfin server and the look without a rebuild.
 *
 * The file is written to Downloads (API 29+, no storage permission) and to the launcher's own folder,
 * which survives a theme push but not an uninstall; the Downloads copy is the one that does. Nothing
 * here touches the network, and the Jellyfin API key in the file is the owner's to keep private.
 */
final class SettingsBackup {
    private static final String TAG = "TvHomeBackup";
    static final String PREFIX = "neotv-settings-";
    private static final String SUFFIX = ".json";
    private static final String FOLDER = "backups";
    private static final int FORMAT = 1;
    /** Bookkeeping, never a choice: the restore sets both itself. */
    private static final Set<String> INTERNAL = new HashSet<>(Arrays.asList(
            Config.SETTINGS_REVISION_KEY, "theme_signature"));

    private SettingsBackup() {}

    /** The launcher's own backups folder, or null when external storage is unavailable. */
    static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return base == null ? null : new File(base, FOLDER);
    }

    /** Where one save landed: the file's name, its path, and whether Downloads also has a copy. */
    static final class Saved {
        final String name;
        final String path;
        final boolean downloads;

        Saved(String name, String path, boolean downloads) {
            this.name = name;
            this.path = path;
            this.downloads = downloads;
        }
    }

    /** Writes one backup; the Downloads copy is the one that survives an uninstall. */
    static Saved save(Context ctx) throws JSONException, IOException {
        JSONObject out = new JSONObject();
        out.put("app", "NeoTV");
        out.put("format", FORMAT);
        out.put("package", ctx.getPackageName());
        out.put("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                .format(new Date()));
        JSONObject theme = theme(ctx);
        if (theme != null) out.put("theme", theme);
        String order = order(ctx);
        if (order != null) out.put("order", order);
        out.put("choices", choices(ctx));
        String text = out.toString(2);
        String name = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + SUFFIX;

        File folder = dir(ctx);
        if (folder == null) throw new IOException("no external files directory");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("could not create " + folder);
        File local = new File(folder, name);
        write(local, text);
        return new Saved(name, local.getAbsolutePath(), saveToDownloads(ctx, name, text));
    }

    /** One backup file the restore screen can offer: its name, when it was made, and its text. */
    static final class Entry {
        final String name;
        final String text;
        final boolean downloads;
        final String when;

        Entry(String name, String text, boolean downloads) {
            this.name = name;
            this.text = text;
            this.downloads = downloads;
            this.when = when(name);
        }
    }

    /**
     * Every backup on the TV, newest first: Downloads (which survives an uninstall) and the launcher's
     * own folder. A name found in both is offered once, from Downloads.
     */
    static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) downloads(ctx, out);
        Set<String> seen = new HashSet<>();
        for (Entry entry : out) seen.add(entry.name);
        File folder = dir(ctx);
        File[] files = folder == null || !folder.isDirectory() ? null
                : folder.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        if (files != null) {
            for (File file : files) {
                if (seen.contains(file.getName())) continue;
                String text = read(file);
                if (text != null) out.add(new Entry(file.getName(), text, false));
            }
        }
        Collections.sort(out, (a, b) -> b.name.compareTo(a.name));
        return out;
    }

    /** Applies one backup; null on success, or a short reason the screen turns into a message. */
    static String restore(Context ctx, String text) {
        JSONObject in;
        try {
            in = new JSONObject(text);
        } catch (JSONException e) {
            return "not-json";
        }
        if (in.optInt("format", 0) != FORMAT) return "format";
        JSONObject theme = in.optJSONObject("theme");
        JSONObject choices = in.optJSONObject("choices");
        if (theme == null && choices == null) return "empty";

        File folder = Config.dir(ctx);
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return "storage";
        if (folder != null) {
            File config = new File(folder, Config.FILE_NAME);
            try {
                // A backup without a theme was taken on a TV that had none: the restore removes the
                // current one instead of leaving a theme the backup never had.
                if (theme == null) {
                    if (config.isFile() && !config.delete()) return "storage";
                } else {
                    write(config, theme.toString(2));
                }
            } catch (JSONException e) {
                return "format";
            } catch (IOException e) {
                Log.w(TAG, "could not write the theme: " + e);
                return "storage";
            }
        }

        // The owner's own tile order lives in the launcher's private files, not in the theme. A backup
        // without it was taken before the order was saved, so the current one stays.
        String order = in.optString("order", null);
        if (order != null) {
            try {
                write(new File(ctx.getFilesDir(), Order.FILE_NAME), order);
            } catch (IOException e) {
                Log.w(TAG, "could not write the tile order: " + e);
                return "storage";
            }
        }

        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        long revision = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().clear();
        if (choices != null) putChoices(editor, choices);
        editor.putLong(Config.SETTINGS_REVISION_KEY, revision).commit();
        // The restored file is the theme these choices belong to: mark it as seen, or the next load
        // reads the new file stamp as a theme push and clears them (Config.syncThemeChoices).
        Config.adoptTheme(ctx);
        return null;
    }

    /** "2026-11-14 12:34" from a neotv-settings-<stamp>.json name, or the name itself. */
    private static String when(String name) {
        String raw = name.startsWith(PREFIX) && name.length() >= PREFIX.length() + 15
                ? name.substring(PREFIX.length(), PREFIX.length() + 15) : "";
        try {
            Date date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(raw);
            if (date != null) return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date);
        } catch (ParseException ignored) {
            // A renamed file still restores; only its label falls back to the name.
        }
        return name;
    }

    private static JSONObject theme(Context ctx) {
        File folder = Config.dir(ctx);
        File config = folder == null ? null : new File(folder, Config.FILE_NAME);
        String text = config != null && config.isFile() ? read(config) : null;
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            Log.w(TAG, "the theme config is not JSON: " + e);
            return null;
        }
    }

    private static String order(Context ctx) {
        File file = new File(ctx.getFilesDir(), Order.FILE_NAME);
        return file.isFile() ? read(file) : null;
    }

    private static JSONObject choices(Context ctx) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getAll().entrySet()) {
            if (INTERNAL.contains(entry.getKey())) continue;
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** Types SharedPreferences keeps: booleans, ints, longs, floats, strings and string sets. */
    private static void putChoices(SharedPreferences.Editor editor, JSONObject choices) {
        for (Iterator<String> keys = choices.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (INTERNAL.contains(key)) continue;
            Object value = choices.opt(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Number) {
                editor.putFloat(key, ((Number) value).floatValue());
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < array.length(); i++) set.add(array.optString(i));
                editor.putStringSet(key, set);
            }
        }
    }

    private static boolean saveToDownloads(Context ctx, String name, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) return false;
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not write " + name + " to Downloads: " + e);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                    // The failed row is invisible while it stays pending.
                }
            }
            return false;
        }
    }

    private static void downloads(Context ctx, List<Entry> out) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        ContentResolver resolver = ctx.getContentResolver();
        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
        try (Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", new String[]{PREFIX + "%"},
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || !name.endsWith(SUFFIX)) continue;
                String text = read(resolver,
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
                if (text != null) out.add(new Entry(name, text, true));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not list Downloads: " + e);
        }
    }

    private static String read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return read(in);
        } catch (IOException e) {
            Log.w(TAG, "could not read " + file + ": " + e);
            return null;
        }
    }

    private static String read(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            return in == null ? null : read(in);
        } catch (Exception e) {
            Log.w(TAG, "could not read " + uri + ": " + e);
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file) && !(file.delete() && tmp.renameTo(file))) {
            tmp.delete();
            throw new IOException("could not write " + file);
        }
    }
}
