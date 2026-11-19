package ro.tinulica.tvhome;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * The screen saver: the moving wallpaper that suits the hour and the sky, full screen with no
 * apps on it, the time large in the middle (a little above), the weather under it. Any key
 * wakes the TV back where it was. The video is released as soon as the saver ends.
 */
public final class WallpaperDream extends DreamService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private SurfaceView surface;
    private TextView clock;
    private TextView weather;
    private String source;
    private boolean surfaceReady;
    private int tries;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showTime();
            long untilNextMinute = 60_000 - (System.currentTimeMillis() % 60_000) + 50;
            handler.postDelayed(this, untilNextMinute);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                play();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                release();
            }
        });
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        // A soft shade so the white time reads on any picture.
        View shade = new View(this);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33000000, 0x14000000, 0x33000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(112);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(18 * dp, 0, 2 * dp, 0x66000000);
        clock.setIncludeFontPadding(false);
        column.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        weather = new TextView(this);
        weather.setTextColor(0xE6FFFFFF);
        weather.setTextSize(22);
        weather.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        weather.setShadowLayer(10 * dp, 0, 1 * dp, 0x66000000);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = Math.round(6 * dp);
        column.addView(weather, wlp);
        // In the middle, a little above.
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        clp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.27f);
        root.addView(column, clp);
        setContentView(root);
        showWeather();
        tick.run();
        column.setAlpha(0f);
        column.animate().alpha(1f).setStartDelay(600).setDuration(1200).start();
        // Which video: the one that suits the hour and the sky (the forecast may be fetched).
        new Thread(() -> {
            List<File> files = DynamicWallpaper.files(this);
            File chosen = files.isEmpty() ? null : DynamicWallpaper.pick(files, DynamicWallpaper.currentMood(this));
            handler.postDelayed(() -> {
                source = chosen != null ? chosen.getAbsolutePath() : null;
                showWeather();
                // After the home screen's own video has been let go (same queue).
                MainActivity.VIDEO_RELEASE.execute(() -> handler.postDelayed(this::play, 400));
            }, 300);
        }, "dream-wallpaper").start();
    }

    private void showTime() {
        clock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(new java.util.Date()));
    }

    private void showWeather() {
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (!prefs.contains("dyn_temp")) {
            weather.setVisibility(View.GONE);
            return;
        }
        String words = Weather.words(prefs.getInt("dyn_code", 0));
        if (!prefs.getBoolean("dyn_day", true) && prefs.getInt("dyn_code", 0) <= 1) {
            words = "Nuit claire";
        }
        weather.setText((words.isEmpty() ? "" : words + "  ·  ") + prefs.getInt("dyn_temp", 0) + "°");
        weather.setVisibility(View.VISIBLE);
    }

    private void play() {
        if (player != null || source == null || !surfaceReady) {
            return;
        }
        try {
            MediaPlayer p = new MediaPlayer();
            p.setDataSource(source);
            p.setDisplay(surface.getHolder());
            p.setLooping(true);
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnErrorListener((mp, what, extra) -> {
                // The home screen's player lets go of the video decoder a moment after the
                // saver comes up; until then a new one is refused. Try again shortly.
                release();
                if (++tries < 6) {
                    handler.postDelayed(this::play, 700);
                }
                return true;
            });
            p.prepareAsync();
            player = p;
        } catch (Exception e) {
            release();
        }
    }

    private void release() {
        if (player != null) {
            final MediaPlayer p = player;
            player = null;
            MainActivity.VIDEO_RELEASE.execute(() -> {
                try {
                    p.release();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            });
        }
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        release();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        release();
        super.onDetachedFromWindow();
    }
}
