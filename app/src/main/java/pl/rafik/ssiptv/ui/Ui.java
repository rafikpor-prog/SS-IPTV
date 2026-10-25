package pl.rafik.ssiptv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(10, 16, 26);
    public static final int SURFACE = Color.rgb(19, 28, 41);
    public static final int CARD = Color.rgb(27, 39, 54);
    public static final int ACCENT = Color.rgb(70, 218, 199);
    public static final int SECONDARY = Color.rgb(170, 187, 204);
    public static final int WHITE = Color.rgb(244, 248, 252);
    public static final int MUTED = Color.rgb(118, 141, 165);
    private Ui() {}

    public static int dp(Context context, float value) {
        return (int) (context.getResources().getDisplayMetrics().density * value + 0.5f);
    }

    public static GradientDrawable rounded(Context c, int color, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, radius));
        return bg;
    }

    public static GradientDrawable outlined(Context c, int color, int stroke, float radius) {
        GradientDrawable bg = rounded(c, color, radius);
        bg.setStroke(dp(c, 1), stroke);
        return bg;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static TextView text(Context context, String content, float size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(content);
        text.setTextColor(color);
        text.setTextSize(size);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    public static TextView button(Context ctx, String label, boolean primary, View.OnClickListener click) {
        TextView button = text(ctx, label, 14, primary ? BG : WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        button.setBackground(rounded(ctx, primary ? ACCENT : CARD, 13));
        button.setClickable(true);
        button.setOnClickListener(click);
        tvFocus(button);
        return button;
    }

    /** Obrys i lekkie powiększenie dla fokusu pilota Android TV. */
    public static <T extends View> T tvFocus(T view) {
        view.setFocusable(true);
        view.setOnFocusChangeListener((v, focused) -> {
            v.setScaleX(focused ? 1.017f : 1f);
            v.setScaleY(focused ? 1.017f : 1f);
            v.setElevation(focused ? dp(v.getContext(), 8) : 0);
            GradientDrawable highlight = outlined(v.getContext(), 0x2046DAC7, ACCENT, 10);
            highlight.setStroke(dp(v.getContext(), 2), ACCENT);
            v.setForeground(focused ? highlight : null);
        });
        return view;
    }

    /** Selector rysowany nad kafelkiem ListView/GridView, widoczny nawet na jego tle. */
    public static GradientDrawable tvSelection(Context context) {
        GradientDrawable highlight = outlined(context, 0x2446DAC7, ACCENT, 12);
        highlight.setStroke(dp(context, 3), ACCENT);
        return highlight;
    }

    public static LinearLayout.LayoutParams margins(Context c, int width, int height,
                                                    int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width < 0 ? width : dp(c, width),
                height < 0 ? height : dp(c, height));
        lp.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        return lp;
    }
}
