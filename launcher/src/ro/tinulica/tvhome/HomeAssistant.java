package ro.tinulica.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The room's lights follow Cinema mode, through Home Assistant's REST API: going into Cinema
 * notes which of the chosen lights are on and switches them off; leaving it switches those (and
 * only those) back on. Set up from the computer:
 *
 *   am start -n ro.tinulica.tvhome/.MainActivity --es ha_url http://192.168.1.10:8123
 *       --es ha_token <long-lived token> --es ha_entities light.salon,switch.lampe_tv
 *
 * The address, token and list stay in TV Home's private storage; nothing else is sent anywhere.
 */
final class HomeAssistant {

    private HomeAssistant() {
    }

    static boolean configured(Context context) {
        SharedPreferences p = prefs(context);
        return !p.getString("ha_url", "").isEmpty() && !p.getString("ha_token", "").isEmpty()
                && !p.getString("ha_entities", "").isEmpty();
    }

    /** Lights off (true) or back as they were (false); off the main thread, best effort. */
    static void cinema(Context context, boolean on) {
        if (!configured(context)) {
            android.util.Log.d("TVHome", "home assistant: not set up");
            return;
        }
        android.util.Log.d("TVHome", "home assistant: cinema " + (on ? "on" : "off"));
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            SharedPreferences p = prefs(app);
            String base = p.getString("ha_url", "").replaceAll("/+$", "");
            if (!base.startsWith("http")) {
                base = "http://" + base;
            }
            String token = p.getString("ha_token", "");
            try {
                if (on) {
                    Set<String> wereOn = new LinkedHashSet<>();
                    for (String id : p.getString("ha_entities", "").split(",")) {
                        id = id.trim();
                        if (id.isEmpty()) {
                            continue;
                        }
                        String state = new JSONObject(call(base + "/api/states/" + id, token, null)).optString("state");
                        if ("on".equals(state)) {
                            wereOn.add(id);
                            call(base + "/api/services/" + domain(id) + "/turn_off", token, body(id));
                        }
                    }
                    p.edit().putStringSet("ha_restore", wereOn).apply();
                    android.util.Log.d("TVHome", "home assistant: switched off " + wereOn);
                } else {
                    Set<String> restore = p.getStringSet("ha_restore", new LinkedHashSet<String>());
                    for (String id : restore) {
                        call(base + "/api/services/" + domain(id) + "/turn_on", token, body(id));
                    }
                    p.edit().remove("ha_restore").apply();
                }
            } catch (Exception e) {
                android.util.Log.w("TVHome", "home assistant: " + e);
            }
        }, "home-assistant").start();
    }

    private static String domain(String entity) {
        int dot = entity.indexOf('.');
        return dot > 0 ? entity.substring(0, dot) : "homeassistant";
    }

    private static String body(String entity) {
        return "{\"entity_id\":\"" + entity.replace("\"", "") + "\"}";
    }

    private static String call(String address, String token, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code >= 300) {
                throw new java.io.IOException("HTTP " + code + " " + address);
            }
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }
}
