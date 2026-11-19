package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Cinema mode on the Plex library instead of the streaming platforms: the same questions (who,
 * film or series, mood, how long), answered from the films and series on the Plex server, the
 * ones not seen yet and well rated first. Opening a title opens it in the Plex app.
 */
final class PlexCinema {

    /** Plex in Cinema's list of places to watch. */
    static final CinemaActivity.Provider PLEX = new CinemaActivity.Provider(-1, PlexSource.PKG, "Plex");

    private static List<JSONObject> library;
    private static long libraryAt;
    private static final Random random = new Random();

    private PlexCinema() {
    }

    /** Cinema uses Plex when it is set up, unless switched off in Settings. */
    static boolean on(Context context) {
        SharedPreferences p = PlexSource.prefs(context);
        return p.getBoolean("cinema_plex", true) && PlexSource.configured(context);
    }

    /** Every film and series on the server (kept 30 minutes). */
    static synchronized List<JSONObject> library(Context context) throws Exception {
        if (library != null && System.currentTimeMillis() - libraryAt < 30 * 60_000L) {
            return library;
        }
        SharedPreferences p = PlexSource.prefs(context);
        String base = PlexSource.base(p);
        String token = p.getString("plex_token", "");
        if (p.getString("plex_machine", "").isEmpty()) {
            p.edit().putString("plex_machine", PlexSource.json(base + "/identity", token)
                    .optString("machineIdentifier", "")).apply();
        }
        List<JSONObject> all = new ArrayList<>();
        JSONArray sections = PlexSource.json(base + "/library/sections", token).optJSONArray("Directory");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject s = sections.getJSONObject(i);
            String type = s.optString("type");
            if (!"movie".equals(type) && !"show".equals(type)) {
                continue;
            }
            JSONArray items = PlexSource.json(base + "/library/sections/" + s.optString("key")
                    + "/all?includeImages=1", token).optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                all.add(items.getJSONObject(j));
            }
        }
        library = all;
        libraryAt = System.currentTimeMillis();
        return all;
    }

    // ------------------------------------------------------------------ the answers

    /** Mood name to words found in Plex's genres (French or English, by agent). */
    private static final Map<String, String[]> MOOD_WORDS = new HashMap<>();

    static {
        MOOD_WORDS.put("Rire", new String[]{"comed", "coméd", "comédie"});
        MOOD_WORDS.put("Action", new String[]{"action", "aventure", "adventure", "guerre", "war", "western"});
        MOOD_WORDS.put("Suspense", new String[]{"thriller", "myst", "crime", "polic", "suspense"});
        MOOD_WORDS.put("Émouvant", new String[]{"drame", "drama", "romance", "romant"});
        MOOD_WORDS.put("Horreur", new String[]{"horr", "épouv"});
        MOOD_WORDS.put("SF et fantasy", new String[]{"science", "sci-fi", "fantas"});
        MOOD_WORDS.put("Histoires vraies", new String[]{"documentaire", "documentary", "histo", "biogra"});
        MOOD_WORDS.put("Animation", new String[]{"animation"});
        MOOD_WORDS.put("Aventure", new String[]{"aventure", "adventure"});
        MOOD_WORDS.put("Magie", new String[]{"fantas", "magie", "magic"});
    }

    private static final String[] KID_WORDS = {"animation", "famil", "enfant", "kids", "children", "jeunesse"};
    private static final String[] HORROR = {"horr", "épouv"};

    /**
     * The library's titles for the answers, best first, at most `keep`. Answers are loosened
     * step by step when too few match: first the length, then the mood.
     */
    static List<CinemaActivity.Title> search(Context context, String who, String kind, String mood, String span,
                                             List<String> seen, int keep) throws Exception {
        List<JSONObject> all = library(context);
        boolean tv = "tv".equals(kind);
        List<JSONObject> found = new ArrayList<>();
        for (int relax = 0; relax < 3 && found.size() < 6; relax++) {
            found.clear();
            for (JSONObject m : all) {
                if (tv != "show".equals(m.optString("type")) || m.optString("art", "").isEmpty()) {
                    continue;
                }
                if (fits(m, who, mood, span, relax)) {
                    found.add(m);
                }
            }
        }
        // Not seen yet first, then the best rated; a little chance so the same evening gives
        // other ideas; suggested lately goes last.
        final Map<JSONObject, Double> score = new HashMap<>();
        for (JSONObject m : found) {
            double s = rating(m) + random.nextDouble() * 2.5;
            if (watched(m)) {
                s -= 6;
            }
            if (seen.contains("p" + m.optString("ratingKey"))) {
                s -= 4;
            }
            score.put(m, s);
        }
        Collections.sort(found, (a, b) -> Double.compare(score.get(b), score.get(a)));
        List<CinemaActivity.Title> out = new ArrayList<>();
        for (JSONObject m : found) {
            if (out.size() >= keep) {
                break;
            }
            out.add(title(context, m));
        }
        return out;
    }

    private static boolean fits(JSONObject m, String who, String mood, String span, int relax) {
        String genres = genres(m).toLowerCase(Locale.ROOT);
        String cert = m.optString("contentRating", "").toLowerCase(Locale.ROOT);
        boolean kids = "kids".equals(who);
        boolean family = "family".equals(who);
        if ((kids || family) && has(genres, HORROR)) {
            return false;
        }
        if (kids && !has(genres, KID_WORDS) && !childSafe(cert)) {
            return false;
        }
        if (family && adultOnly(cert)) {
            return false;
        }
        boolean animated = has(genres, new String[]{"animation"});
        if (!kids && !family && animated && !"Animation".equals(mood)) {
            return false;   // cartoons only when asked for
        }
        if (!"Histoires vraies".equals(mood) && has(genres, new String[]{"documentaire", "documentary"})) {
            return false;
        }
        if (relax < 2 && mood != null && MOOD_WORDS.containsKey(mood) && !has(genres, MOOD_WORDS.get(mood))) {
            return false;
        }
        if (relax < 1 && span != null) {
            long minutes = m.optLong("duration", 0) / 60_000L;
            int year = m.optInt("year", 0);
            int now = Calendar.getInstance().get(Calendar.YEAR);
            switch (span) {
                case "short":
                    return minutes == 0 || minutes <= 95;
                case "two":
                    return minutes == 0 || minutes <= 135;
                case "new":
                    return year >= now - 2;
                case "classic":
                    return year > 0 && year <= 2012;
                default:
                    break;
            }
        }
        return true;
    }

    private static boolean has(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean childSafe(String cert) {
        return cert.equals("g") || cert.equals("pg") || cert.startsWith("tv-y") || cert.equals("tv-g")
                || cert.contains("tous") || cert.equals("u") || cert.equals("0") || cert.endsWith("/0")
                || cert.endsWith("/6");
    }

    private static boolean adultOnly(String cert) {
        return cert.equals("r") || cert.equals("nc-17") || cert.equals("tv-ma") || cert.contains("16")
                || cert.contains("18");
    }

    private static boolean watched(JSONObject m) {
        if ("show".equals(m.optString("type"))) {
            int leaves = m.optInt("leafCount", 0);
            return leaves > 0 && m.optInt("viewedLeafCount", 0) >= leaves;
        }
        return m.optInt("viewCount", 0) > 0;
    }

    private static double rating(JSONObject m) {
        double r = m.optDouble("audienceRating", 0);
        if (r <= 0) {
            r = m.optDouble("rating", 0);
        }
        return r > 0 ? r : 6;
    }

    private static String genres(JSONObject m) {
        JSONArray g = m.optJSONArray("Genre");
        List<String> names = new ArrayList<>();
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o != null) {
                names.add(o.optString("tag"));
            }
        }
        return android.text.TextUtils.join(", ", names);
    }

    // ------------------------------------------------------------------ titles

    /** What is on deck, then recently added: Cinema's "Popular" tab on Plex. */
    static List<CinemaActivity.Title> popular(Context context) {
        List<CinemaActivity.Title> out = new ArrayList<>();
        try {
            SharedPreferences p = PlexSource.prefs(context);
            String base = PlexSource.base(p);
            String token = p.getString("plex_token", "");
            Set<String> keys = new HashSet<>();
            for (String path : new String[]{"/library/onDeck?X-Plex-Container-Size=10&includeImages=1",
                    "/library/recentlyAdded?X-Plex-Container-Size=40&includeImages=1"}) {
                JSONArray items = PlexSource.json(base + path, token).optJSONArray("Metadata");
                for (int i = 0; items != null && i < items.length() && out.size() < 14; i++) {
                    JSONObject m = items.getJSONObject(i);
                    String type = m.optString("type");
                    String key = "episode".equals(type) ? m.optString("grandparentRatingKey")
                            : "season".equals(type) ? m.optString("parentRatingKey") : m.optString("ratingKey");
                    if (key.isEmpty() || !keys.add(key)) {
                        continue;
                    }
                    if ("episode".equals(type) || "season".equals(type)) {
                        JSONArray show = PlexSource.json(base + "/library/metadata/" + key + "?includeImages=1", token)
                                .optJSONArray("Metadata");
                        if (show == null || show.length() == 0) {
                            continue;
                        }
                        m = show.getJSONObject(0);
                    } else if (!"movie".equals(type)) {
                        continue;
                    }
                    if (!m.optString("art", "").isEmpty()) {
                        out.add(title(context, m));
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TVHome", "plex cinema: " + e.getClass().getSimpleName());
        }
        return out;
    }

    /** Behind the questions: the library's backgrounds, a few at random. */
    static List<String> stills(Context context, int count) {
        List<String> out = new ArrayList<>();
        try {
            List<JSONObject> all = new ArrayList<>(library(context));
            Collections.shuffle(all, random);
            for (JSONObject m : all) {
                if (out.size() >= count) {
                    break;
                }
                String art = m.optString("art", "");
                if (!art.isEmpty()) {
                    out.add(image(context, art, 1920, 1080));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TVHome", "plex cinema stills: " + e.getClass().getSimpleName());
        }
        return out;
    }

    /** A background still for a question's option: something of that kind / mood / audience. */
    static String stillFor(Context context, String who, String kind, String mood) {
        try {
            List<JSONObject> all = new ArrayList<>(library(context));
            Collections.shuffle(all, random);
            for (JSONObject m : all) {
                boolean show = "show".equals(m.optString("type"));
                if (kind != null && ("tv".equals(kind)) != show) {
                    continue;
                }
                if (m.optString("art", "").isEmpty() || !fits(m, who, mood, null, mood == null ? 2 : 0)) {
                    continue;
                }
                return image(context, m.optString("art"), 1280, 720);
            }
        } catch (Exception ignored) {
            // The lobby stays.
        }
        return null;
    }

    static CinemaActivity.Title title(Context context, JSONObject m) {
        SharedPreferences p = PlexSource.prefs(context);
        CinemaActivity.Title t = new CinemaActivity.Title();
        t.id = m.optInt("ratingKey");
        t.tv = "show".equals(m.optString("type"));
        t.name = m.optString("title", "");
        t.original = m.optString("originalTitle", "");
        t.overview = m.optString("summary", "");
        t.year = m.optInt("year", 0) > 0 ? String.valueOf(m.optInt("year")) : "";
        t.vote = m.optDouble("audienceRating", m.optDouble("rating", 0));
        String art = m.optString("art", "");
        String thumb = m.optString("thumb", "");
        JSONArray images = m.optJSONArray("Image");
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            if (img == null) {
                continue;
            }
            if ("background".equals(img.optString("type")) && !img.optString("url", "").isEmpty()) {
                art = img.optString("url");
            } else if ("clearLogo".equals(img.optString("type")) && !img.optString("url", "").isEmpty()) {
                t.logo = image(context, img.optString("url"), 800, 400);
            }
        }
        t.backdrop = art.isEmpty() ? null : image(context, art, 1920, 1080);
        t.poster = thumb.isEmpty() ? null : image(context, thumb, 500, 750);
        if (t.backdrop == null) {
            t.backdrop = t.poster;
        }
        String g = genres(m);
        String[] parts = g.split(", ");
        t.genres = parts.length > 2 ? parts[0] + ", " + parts[1] : g;
        if (t.tv) {
            int seasons = m.optInt("childCount", 0);
            t.length = seasons > 0 ? seasons + (seasons > 1 ? " saisons" : " saison") : "";
        } else {
            long minutes = m.optLong("duration", 0) / 60_000L;
            t.length = minutes <= 0 ? "" : minutes >= 60 ? (minutes / 60) + " h " + String.format(Locale.ROOT, "%02d", minutes % 60)
                    : minutes + " min";
        }
        t.cert = m.optString("contentRating", "").replaceFirst("^[a-z]{2}/", "");
        t.included.add(PLEX);
        t.ids = new HashMap<>();
        t.ids.put("plex", "plex://server://" + p.getString("plex_machine", "")
                + "/com.plexapp.plugins.library/library/metadata/" + m.optString("ratingKey"));
        if (t.backdrop != null) {
            t.stills.add(t.backdrop);
        }
        t.detailed = true;
        return t;
    }

    /** A picture from the server, resized by it (and with the token, for the TV only). */
    static String image(Context context, String path, int w, int h) {
        SharedPreferences p = PlexSource.prefs(context);
        String base = PlexSource.base(p);
        return base + "/photo/:/transcode?width=" + w + "&height=" + h + "&minSize=1&upscale=1&url="
                + Uri.encode(path) + "&X-Plex-Token=" + Uri.encode(p.getString("plex_token", ""));
    }
}
