package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plex publishes nothing for Android TV home screens, so TV Home asks the Plex Media Server
 * itself (on the home network): "Continue" (On Deck) and "Recently added", as preview cards for
 * the Plex tile, with the server's own full-screen artwork. OK opens the title in the Plex app.
 *
 * Set up from the computer:
 *   am start -n ro.tinulica.tvhome/.MainActivity --es plex_url 192.168.1.10:32400 --es plex_token <token>
 * Address and token stay in TV Home's private storage and only ever go to that server.
 */
final class PlexSource {

    static final String PKG = "com.plexapp.android";

    private PlexSource() {
    }

    static boolean configured(Context context) {
        SharedPreferences p = prefs(context);
        return !p.getString("plex_url", "").isEmpty() && !p.getString("plex_token", "").isEmpty();
    }

    /** On Deck then recently added movies and shows; empty when not set up or unreachable. */
    static List<Previews.Item> load(Context context) {
        List<Previews.Item> out = new ArrayList<>();
        if (!configured(context)) {
            return out;
        }
        SharedPreferences p = prefs(context);
        String base = base(p);
        String token = p.getString("plex_token", "");
        try {
            String machine = p.getString("plex_machine", "");
            if (machine.isEmpty()) {
                machine = json(base + "/identity", token).optString("machineIdentifier", "");
                p.edit().putString("plex_machine", machine).apply();
            }
            Set<String> seen = new HashSet<>();
            JSONArray deck = json(base + "/library/onDeck?X-Plex-Container-Size=12&includeImages=1", token).optJSONArray("Metadata");
            for (int i = 0; deck != null && i < deck.length() && out.size() < 8; i++) {
                Previews.Item item = item(deck.getJSONObject(i), base, token, machine, true);
                if (item != null && seen.add(item.intentUri)) {
                    out.add(item);
                }
            }
            JSONArray recent = json(base + "/library/recentlyAdded?X-Plex-Container-Size=40&includeImages=1", token).optJSONArray("Metadata");
            int added = 0;
            for (int i = 0; recent != null && i < recent.length() && added < 14; i++) {
                Previews.Item item = item(recent.getJSONObject(i), base, token, machine, false);
                if (item != null && seen.add(item.intentUri)) {
                    out.add(item);
                    added++;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TVHome", "plex: " + e.getClass().getSimpleName());
        }
        return out;
    }

    private static Previews.Item item(JSONObject m, String base, String token, String machine, boolean deck) {
        String type = m.optString("type");
        String key = m.optString("ratingKey");
        String title = m.optString("title");
        String art = m.optString("art", "");
        // Plex's own artwork set: the background scene and the title's logo (for an episode, the show's).
        String background = null;
        String logo = null;
        JSONArray images = m.optJSONArray("Image");
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            if (img == null) {
                continue;
            }
            if ("background".equals(img.optString("type"))) {
                background = img.optString("url", null);
            } else if ("clearLogo".equals(img.optString("type"))) {
                logo = img.optString("url", null);
            }
        }
        int programType;
        switch (type) {
            case "movie":
                programType = 0;
                break;
            case "episode":
                // "Show · S2 E5 · Episode title"
                title = m.optString("grandparentTitle") + "  ·  S" + m.optInt("parentIndex") + " E" + m.optInt("index")
                        + (title.isEmpty() ? "" : "  ·  " + title);
                art = m.optString("grandparentArt", art);
                programType = 3;
                break;
            case "season":
                // Recently added seasons stand for their show.
                key = m.optString("parentRatingKey", key);
                title = m.optString("parentTitle", title);
                art = m.optString("parentArt", art);
                programType = 1;
                break;
            case "show":
                programType = 1;
                break;
            default:
                return null;   // music and the rest stay in the Plex app
        }
        if (key.isEmpty() || art.isEmpty()) {
            return null;
        }
        Previews.Item item = new Previews.Item();
        item.pkg = PKG;
        item.title = title;
        item.type = programType;
        item.aspect = 0;
        item.watchNext = deck;
        item.channel = deck ? "Reprendre" : "Ajouts récents";
        if (background != null && !background.isEmpty() && !"episode".equals(type)) {
            art = background;
        }
        String scene = base + "/photo/:/transcode?width=1920&height=1080&minSize=1&upscale=1&url="
                + Uri.encode(art) + "&X-Plex-Token=" + Uri.encode(token);
        // The Apple TV look: the scene with the title's logo laid over it (see Previews.fetch).
        item.poster = logo != null && !logo.isEmpty()
                ? "plexcombo:" + scene + "|" + base + logo + (logo.contains("?") ? "&" : "?") + "X-Plex-Token=" + Uri.encode(token)
                : scene;
        item.intentUri = "plex://server://" + machine + "/com.plexapp.plugins.library/library/metadata/" + key;
        item.genre = null;   // the type already gives the "Films" / "Séries" tabs
        return item;
    }

    static String base(SharedPreferences p) {
        String url = p.getString("plex_url", "").trim().replaceAll("/+$", "");
        return url.startsWith("http") ? url : "http://" + url;
    }

    static JSONObject json(String address, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Plex-Token", token);
            c.setRequestProperty("X-Plex-Product", "TV Home");
            c.setRequestProperty("X-Plex-Client-Identifier", "tvhome-launcher");
            if (c.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
                JSONObject root = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                return root.optJSONObject("MediaContainer") != null ? root.getJSONObject("MediaContainer") : root;
            }
        } finally {
            c.disconnect();
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }
}
