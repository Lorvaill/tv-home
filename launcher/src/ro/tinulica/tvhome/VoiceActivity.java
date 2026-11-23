package ro.tinulica.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The remote's microphone button: a small glass bubble at the bottom of the screen listens, shows
 * what it hears, then does it. "Active le mode cinéma", "Ouvre Plex", "Quel temps fait-il ?",
 * "Réglages", "Écran de veille"; anything else is searched for (films, series, where to watch).
 * Back closes it; the button again listens again.
 */
public final class VoiceActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private LinearLayout bubble;
    private TextView hint;
    private TextView heard;
    private Level level;
    private boolean acting;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(px(18), px(14), px(28), px(14));
        android.graphics.drawable.GradientDrawable back = new android.graphics.drawable.GradientDrawable();
        back.setCornerRadius(px(32));
        back.setColor(0xEB1C1C1E);
        back.setStroke(Math.max(1, px(0.75f)), 0x33FFFFFF);
        bubble.setBackground(back);
        bubble.setElevation(px(12));
        bubble.setMinimumWidth(px(360));
        level = new Level(this);
        bubble.addView(level, new LinearLayout.LayoutParams(px(36), px(36)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        hint = new TextView(this);
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(13);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setText("Je t'écoute…");
        words.addView(hint);
        heard = new TextView(this);
        heard.setTextColor(Color.WHITE);
        heard.setTextSize(22);
        heard.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        heard.setMaxWidth(px(620));
        heard.setText("Dis par exemple « Active le mode cinéma »");
        heard.setAlpha(0.55f);
        words.addView(heard);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.leftMargin = px(14);
        bubble.addView(words, wlp);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = px(54);
        root.addView(bubble, blp);
        setContentView(root);
        bubble.setAlpha(0f);
        bubble.setTranslationY(px(30));
        bubble.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f)).setDuration(320).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        listen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        listen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST) {
            listen();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            close(0);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    // ------------------------------------------------------------------ listening

    private void listen() {
        if (acting) {
            return;
        }
        handler.removeCallbacksAndMessages(null);
        stop();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Le micro n'est pas disponible", "La reconnaissance vocale manque sur la télé.");
            close(3500);
            return;
        }
        hint.setText("Je t'écoute…");
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                level.listening = true;
                level.invalidate();
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rms) {
                level.set(Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                level.listening = false;
                level.set(0f);
                hint.setText("Un instant…");
            }

            @Override
            public void onError(int error) {
                level.listening = false;
                level.set(0f);
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    say("Je n'ai rien entendu", "Appuie sur le micro et parle juste après.");
                } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        || error == SpeechRecognizer.ERROR_SERVER) {
                    say("Pas de connexion", "La reconnaissance vocale a besoin d'internet.");
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    say("Le micro n'est pas autorisé", "");
                } else {
                    say("Le micro n'a pas marché", "Erreur " + error + ". Réessaie.");
                }
                android.util.Log.w("TVHome", "voice: error " + error);
                close(3000);
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (said == null || said.isEmpty() || said.get(0).trim().isEmpty()) {
                    say("Je n'ai rien entendu", "Appuie sur le micro et parle juste après.");
                    close(3000);
                    return;
                }
                act(said.get(0).trim());
            }

            @Override
            public void onPartialResults(Bundle partial) {
                ArrayList<String> said = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (said != null && !said.isEmpty() && !said.get(0).isEmpty()) {
                    heard.setAlpha(1f);
                    heard.setText(capital(said.get(0)));
                }
            }

            @Override
            public void onEvent(int type, Bundle params) {
            }
        });
        Intent speak = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(speak);
        } catch (RuntimeException e) {
            say("Le micro n'est pas disponible", String.valueOf(e.getMessage()));
            close(3500);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            listen();
        } else {
            say("Le micro n'est pas autorisé", "");
            close(3000);
        }
    }

    private void stop() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            recognizer = null;
        }
    }

    // ------------------------------------------------------------------ doing what was said

    private void act(String said) {
        acting = true;
        heard.setAlpha(1f);
        heard.setText(capital(said));
        String s = plain(said);
        // Cinema mode.
        if (s.contains("cinema")) {
            hint.setText("Mode Cinéma");
            home("cinema", null);
            return;
        }
        // The weather.
        if (s.contains("meteo") || s.contains("quel temps") || s.contains("temperature")) {
            SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
            if (p.contains("dyn_temp")) {
                String words = Weather.words(p.getInt("dyn_code", 0));
                if (!p.getBoolean("dyn_day", true) && p.getInt("dyn_code", 0) <= 1) {
                    words = "Nuit claire";
                }
                hint.setText("Météo");
                heard.setText((words.isEmpty() ? "" : words + "  ·  ") + p.getInt("dyn_temp", 0) + "°");
            } else {
                say("Météo", "Pas encore de prévisions.");
            }
            close(4500);
            return;
        }
        // Settings, the screen saver, home.
        if (s.matches(".*\\b(reglages?|parametres?)\\b.*") && !s.matches(".*\\b(ouvre|lance)\\b.+\\b(de|du)\\b.*")) {
            start(new Intent(this, SettingsActivity.class));
            return;
        }
        if (s.contains("ecran de veille") || s.equals("veille")) {
            start(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
            return;
        }
        if (s.matches("(retour (a l')?)?accueil|ecran d'accueil")) {
            home(null, null);
            return;
        }
        // "Ouvre / lance / mets <app>".
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:ouvre|ouvrir|lance|lancer|mets|mettre|demarre|va sur|allume)\\s+(?:l'appli(?:cation)?\\s+|l'|la |le |les )?(.+)$")
                .matcher(s);
        if (m.find()) {
            Intent app = app(m.group(1).trim());
            if (app != null) {
                hint.setText("J'ouvre");
                start(app);
                return;
            }
        } else {
            Intent app = app(s);
            if (app != null) {
                hint.setText("J'ouvre");
                start(app);
                return;
            }
        }
        // Anything else: a search.
        String query = said.replaceFirst("(?i)^(cherche|recherche|trouve|je veux (voir|regarder)|mets|lance)\\s+", "");
        hint.setText("Recherche");
        home("search", query);
    }

    /** The app whose name was said, or null. */
    private Intent app(String name) {
        String n = plain(name);
        String[][] aliases = {
                {"youtube", "org.smarttube.stable"}, {"smart tube", "org.smarttube.stable"},
                {"tf1", "fr.tf1.mytf1"}, {"tf 1", "fr.tf1.mytf1"}, {"m6", "fr.m6.m6replay"}, {"m 6", "fr.m6.m6replay"},
                {"france tv", "fr.francetv.pluzz"}, {"france television", "fr.francetv.pluzz"},
                {"france 2", "fr.francetv.pluzz"}, {"plex", "com.plexapp.android"}, {"spotify", "com.spotify.tv.android"},
                {"play store", "com.android.vending"}, {"google play", "com.android.vending"},
                {"netflix", "com.netflix.ninja"}, {"prime video", "com.amazon.amazonvideo.livingroom"},
                {"disney", "com.disney.disneyplus"}, {"canal", "com.canal.android.canal"},
        };
        PackageManager pm = getPackageManager();
        for (String[] a : aliases) {
            if (n.equals(a[0]) || n.startsWith(a[0] + " ")) {
                Intent i = pm.getLeanbackLaunchIntentForPackage(a[1]);
                if (i == null) {
                    i = pm.getLaunchIntentForPackage(a[1]);
                }
                if (i != null) {
                    return i;
                }
            }
        }
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        for (ResolveInfo r : apps) {
            String label = plain(String.valueOf(r.loadLabel(pm)));
            if (!label.isEmpty() && (n.equals(label) || (n.length() >= 4 && label.startsWith(n)))) {
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        return null;
    }

    private void home(String what, String query) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("internal", MainActivity.INTERNAL);
        if (what != null) {
            i.putExtra("voice", what);
        }
        if (query != null) {
            i.putExtra("voice_query", query);
        }
        handler.postDelayed(() -> {
            try {
                startActivity(i);
            } catch (RuntimeException ignored) {
                // Home is always there.
            }
            finish();
            overridePendingTransition(0, 0);
        }, 650);
    }

    private void start(Intent i) {
        handler.postDelayed(() -> {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                acting = false;
                say("Impossible de l'ouvrir", "");
                close(2500);
                return;
            }
            finish();
            overridePendingTransition(0, 0);
        }, 650);
    }

    private void say(String head, String detail) {
        hint.setText(head);
        heard.setAlpha(detail.isEmpty() ? 0f : 0.75f);
        heard.setText(detail);
    }

    private void close(long after) {
        handler.postDelayed(() -> bubble.animate().alpha(0f).translationY(px(24)).setDuration(220)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start(), after);
    }

    /** Lower case, no accents, single spaces, no final punctuation. */
    static String plain(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.FRANCE), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('’', '\'').replaceAll("[?!.,;:]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ------------------------------------------------------------------ the listening mark

    /** A white disc that breathes with the voice, ringed while listening. */
    private static final class Level extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float now;
        private float target;
        boolean listening;

        Level(Context context) {
            super(context);
        }

        void set(float v) {
            target = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f;
            now += (target - now) * 0.3f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(listening ? 0x40FFFFFF : 0x1FFFFFFF);
            canvas.drawCircle(w / 2f, h / 2f, r * (0.72f + 0.28f * now), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(w / 2f, h / 2f, r * 0.42f, paint);
            // The microphone, in black on the disc.
            paint.setColor(0xFF1C1C1E);
            float mw = r * 0.16f;
            float mh = r * 0.26f;
            canvas.drawRoundRect(w / 2f - mw, h / 2f - mh, w / 2f + mw, h / 2f + mh * 0.5f, mw, mw, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.06f);
            canvas.drawLine(w / 2f, h / 2f + mh * 0.8f, w / 2f, h / 2f + mh * 1.15f, paint);
            if (Math.abs(target - now) > 0.01f) {
                postInvalidateOnAnimation();
            }
        }
    }
}
