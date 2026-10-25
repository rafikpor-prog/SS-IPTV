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
    public static final int BG = Color.rgb(7, 19, 34);
    public static final int SURFACE = Color.rgb(17, 35, 55);
    public static final int CARD = Color.rgb(21, 43, 64);
    public static final int ACCENT = Color.rgb(39, 230, 208);
    public static final int SECONDARY = Color.rgb(139, 165, 187);
    public static final int WHITE = Color.rgb(240, 248, 255);
    public static final int MUTED = Color.rgb(105, 134, 160);
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
        return button;
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
