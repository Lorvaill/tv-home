package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Wallpapers that follow the time of day and the sky. Files pushed to files/dynamic/ are named
 *
 *     <time>[_<weather>].mp4        e.g. "night_rain.mp4", "late night.mp4", "day_sun.mp4"
 *
 * time:    morning, day, evening, night, late night
 * weather: (nothing, sun or clear), cloud, fog, wind, rain, thunder, snow
 *
 * The home screen shows the file whose name is closest to now: the time of day (from the real
 * sunrise and sunset where the TV is) counts most, then the weather (Open-Meteo). Older names
 * ("sunny forest", "pluie", "nuit"...) still work, read as the nearest time and weather.
 * Videos play muted in a loop behind the home screen; pictures stay still.
 */
final class DynamicWallpaper {

    static final String DIR = "dynamic";

    // Times of day, in the order they come.
    static final String MORNING = "morning";
    static final String DAY = "day";
    static final String EVENING = "evening";
    static final String NIGHT = "night";
    static final String LATE_NIGHT = "late night";
    private static final String[] TIMES = {MORNING, DAY, EVENING, NIGHT, LATE_NIGHT};

    // Weathers.
    static final String CLEAR = "clear";
    static final String CLOUD = "cloud";
    static final String FOG = "fog";
    static final String WIND = "wind";
    static final String RAIN = "rain";
    static final String THUNDER = "thunder";
    static final String SNOW = "snow";
    private static final String[] WEATHERS = {CLEAR, CLOUD, FOG, WIND, RAIN, THUNDER, SNOW};

    /** Used before the first weather reading. */
    static final String NEUTRAL = DAY;

    /** How far apart two times of day look (same order as TIMES). */
    private static final int[][] TIME_COST = {
            //  morn day eve night late
            {0, 2, 4, 6, 4},   // morning
            {2, 0, 3, 7, 8},   // day
            {4, 3, 0, 3, 5},   // evening
            {6, 7, 3, 0, 2},   // night
            {4, 8, 5, 2, 0},   // late night
    };

    /** How far apart two skies look (same order as WEATHERS). */
    private static final int[][] WEATHER_COST = {
            // clr cld fog wnd rain thun snow
            {0, 2, 3, 1, 4, 5, 4},   // clear
            {2, 0, 2, 1, 2, 3, 3},   // cloud
            {3, 2, 0, 3, 3, 4, 3},   // fog
            {1, 1, 3, 0, 3, 3, 4},   // wind
            {4, 2, 3, 3, 0, 1, 3},   // rain
            {5, 3, 4, 3, 1, 0, 4},   // thunder
            {4, 3, 3, 4, 3, 4, 0},   // snow
    };

    static boolean isVideo(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".m4v");
    }

    private static boolean isMedia(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return isVideo(lower) || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp");
    }

    /** Videos this TV's decoder refused (4K at 60 fps H.264, for one): skipped until the app restarts. */
    static final java.util.Set<String> UNPLAYABLE = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    static List<File> files(Context context) {
        List<File> out = new ArrayList<>();
        File dir = context.getExternalFilesDir(DIR);
        File[] all = dir != null ? dir.listFiles() : null;
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && isMedia(f.getName()) && !UNPLAYABLE.contains(f.getAbsolutePath())) {
                    out.add(f);
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ names

    private static String plain(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** {time, weather} of a file name, or null when it says neither. */
    static String[] keyOf(String fileName) {
        String n = plain(fileName);
        // The convention: "<time>[_<weather>]" ("late night" may also be late_night or latenight).
        String head = n;
        String tail = "";
        int us = n.lastIndexOf('_');
        String compact = n.replaceAll("[\\s\\-]+", " ").trim();
        if (compact.startsWith("late night") || compact.startsWith("late_night") || compact.startsWith("latenight")) {
            head = LATE_NIGHT;
            String rest = compact.replaceFirst("^late[ _]?night", "");
            tail = rest.startsWith("_") ? rest.substring(1) : "";
        } else if (us > 0) {
            head = n.substring(0, us).trim();
            tail = n.substring(us + 1).trim();
        }
        String time = timeWord(head);
        if (time != null) {
            String weather = tail.isEmpty() ? CLEAR : weatherWord(tail.split("[\\s(]")[0]);
            return new String[]{time, weather != null ? weather : CLEAR};
        }
        return legacy(" " + n.replaceAll("[^a-z0-9]+", " ") + " ");
    }

    private static String timeWord(String w) {
        switch (w) {
            case "morning": case "matin": return MORNING;
            case "day": case "jour": case "journee": return DAY;
            case "evening": case "soir": case "soiree": case "sunset": return EVENING;
            case "night": case "nuit": return NIGHT;
            case "late night": case "fin de nuit": return LATE_NIGHT;
            default: return null;
        }
    }

    private static String weatherWord(String w) {
        switch (w) {
            case "": case "sun": case "sunny": case "clear": case "soleil": return CLEAR;
            case "cloud": case "cloudy": case "clouds": case "nuage": case "nuageux": return CLOUD;
            case "fog": case "foggy": case "mist": case "brouillard": return FOG;
            case "wind": case "windy": case "vent": return WIND;
            case "rain": case "rainy": case "pluie": return RAIN;
            case "thunder": case "storm": case "orage": return THUNDER;
            case "snow": case "snowy": case "winter": case "neige": return SNOW;
            default: return null;
        }
    }

    /** Free names ("sunny forest", "pluie", "nightly forest"): the nearest time and weather. */
    private static String[] legacy(String n) {
        String time = DAY;
        if (n.matches(".*\\b(night|nightly|nuit)\\b.*")) {
            time = NIGHT;
        } else if (n.matches(".*\\b(evening|soir|sunset)\\b.*")) {
            time = EVENING;
        } else if (n.matches(".*\\b(morning|matin|sunrise)\\b.*")) {
            time = MORNING;
        }
        String weather = null;
        String[][] words = {
                {THUNDER, "thunder", "storm", "orage"}, {SNOW, "snow", "winter", "neige", "hiver"},
                {RAIN, "rain", "rainy", "pluie"}, {FOG, "fog", "foggy", "brouillard"},
                {WIND, "wind", "windy", "vent"}, {CLOUD, "cloud", "cloudy", "nuage", "nuageux"},
                {CLEAR, "sun", "sunny", "soleil", "clear", "neutral", "neutre"}};
        for (String[] g : words) {
            for (int i = 1; i < g.length && weather == null; i++) {
                if (n.contains(" " + g[i] + " ")) {
                    weather = g[0];
                }
            }
        }
        if (weather == null && !NIGHT.equals(time)) {
            return null;
        }
        return new String[]{time, weather != null ? weather : CLEAR};
    }

    private static int index(String[] list, String value) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    /** The file closest to "time_weather" (lowest cost); files without a readable name come last. */
    static File pick(List<File> files, String key) {
        if (files.isEmpty()) {
            return null;
        }
        String[] want = key.split("_", 2);
        int wt = Math.max(0, index(TIMES, want[0]));
        int ww = Math.max(0, index(WEATHERS, want.length > 1 ? want[1] : CLEAR));
        File best = null;
        int bestCost = Integer.MAX_VALUE;
        for (File f : files) {
            String[] k = keyOf(f.getName());
            int cost = 100;
            if (k != null) {
                cost = TIME_COST[wt][index(TIMES, k[0])] + WEATHER_COST[ww][index(WEATHERS, k[1])];
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ now

    private static final long FRESH = 20 * 60_000L;

    /**
     * "time_weather" for now, e.g. "night_rain". The weather is asked at most every 20 minutes
     * (call this off the main thread); the time of day is worked out each call from the stored
     * sunrise and sunset. A forced key ("wallpaper_mood" in the preferences) wins, for testing.
     */
    static String currentMood(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        long at = prefs.getLong("dyn_mood_at", 0);
        if (System.currentTimeMillis() - at >= FRESH || !prefs.contains("dyn_sunset")) {
            fetch(context, prefs);
        }
        String forced = prefs.getString("wallpaper_mood", "");
        if (!forced.isEmpty()) {
            return forced;
        }
        String key = timeOfDay(prefs) + "_" + weatherOf(prefs);
        prefs.edit().putString("dyn_mood", key).apply();
        return key;
    }

    private static String timeOfDay(SharedPreferences prefs) {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int sunrise = minutesOf(prefs.getLong("dyn_sunrise", 0), 7 * 60 + 30);
        int sunset = minutesOf(prefs.getLong("dyn_sunset", 0), 18 * 60);
        if (minute < sunrise - 30 && minute >= 30) {
            return LATE_NIGHT;           // from half past midnight until just before dawn
        }
        if (minute < 30) {
            return NIGHT;
        }
        if (minute < Math.max(sunrise + 180, 11 * 60)) {
            return MORNING;              // dawn until about three hours after sunrise (11:00 at least)
        }
        if (minute < sunset - 75) {
            return DAY;
        }
        if (minute < sunset + 60) {
            return EVENING;              // golden hour, sunset and dusk
        }
        return NIGHT;
    }

    private static int minutesOf(long epochMillis, int fallback) {
        if (epochMillis <= 0) {
            return fallback;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static String weatherOf(SharedPreferences prefs) {
        int code = prefs.getInt("dyn_code", 0);
        if (code >= 95) {
            return THUNDER;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return SNOW;
        }
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            return RAIN;
        }
        if (code == 45 || code == 48) {
            return FOG;
        }
        // Strong wind (gusts over 50 km/h, or 35 km/h steady) shows when the sky is otherwise calm.
        if (prefs.getFloat("dyn_gusts", 0) >= 50 || prefs.getFloat("dyn_wind", 0) >= 35) {
            return WIND;
        }
        if (code == 2 || code == 3) {
            return CLOUD;
        }
        return CLEAR;
    }

    /** Weather, wind, sunrise and sunset where the TV is (Open-Meteo, free, no key). */
    private static void fetch(Context context, SharedPreferences prefs) {
        try {
            Weather.Place place = Weather.place(context);
            if (place == null) {
                return;
            }
            String address = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f"
                    + "&longitude=%.4f&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_gusts_10m"
                    + "&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=1", place.lat, place.lon);
            JSONObject o = new JSONObject(get(address));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.optJSONObject("daily");
            SharedPreferences.Editor e = prefs.edit()
                    .putLong("dyn_mood_at", System.currentTimeMillis())
                    .putInt("dyn_temp", (int) Math.round(cur.optDouble("temperature_2m")))
                    .putInt("dyn_code", cur.optInt("weather_code"))
                    .putBoolean("dyn_day", cur.optInt("is_day", 1) == 1)
                    .putFloat("dyn_wind", (float) cur.optDouble("wind_speed_10m", 0))
                    .putFloat("dyn_gusts", (float) cur.optDouble("wind_gusts_10m", 0));
            if (daily != null) {
                JSONArray rise = daily.optJSONArray("sunrise");
                JSONArray set = daily.optJSONArray("sunset");
                if (rise != null && rise.length() > 0) {
                    e.putLong("dyn_sunrise", rise.getLong(0) * 1000L);
                }
                if (set != null && set.length() > 0) {
                    e.putLong("dyn_sunset", set.getLong(0) * 1000L);
                }
            }
            e.apply();
        } catch (Exception ignored) {
            // Offline: keep the last reading.
        }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "TVHome/3.0 (personal TV launcher)");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
