package com.distanceuprav.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

/**
 * Dark dialog consistent with the DistanceUprav dashboard.
 * The dangerous action always requires a deliberate button press.
 */
public final class StyledConfirm {
    private StyledConfirm() {}

    private static int dp(Activity ctx, float value) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * value);
    }

    private static GradientDrawable shape(Activity ctx, int color, int radius, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radius));
        if (border != 0) d.setStroke(dp(ctx, 1), border);
        return d;
    }

    private static TextView line(Activity ctx, String value, int size, int color, boolean bold) {
        TextView text = new TextView(ctx);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private static Button button(Activity ctx, String caption, int fill) {
        Button button = new Button(ctx);
        button.setAllCaps(false);
        button.setText(caption);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(ctx, 48));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(ctx, fill, 13, 0), null));
        return button;
    }

    public static void show(Activity activity, String heading, String description,
                            String confirmText, boolean destructive, Runnable action) {
        if (activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 22), dp(activity, 21), dp(activity, 22), dp(activity, 20));
        layout.setBackground(shape(activity, 0xFF192238, 24, 0xFF47536C));

        TextView marker = line(activity, destructive ? "⚠  ТРЕБУЕТСЯ ПОДТВЕРЖДЕНИЕ" : "ℹ  WINDOWS AGENT",
                11, destructive ? 0xFFFFCA81 : 0xFF9DAFFF, true);
        layout.addView(marker);

        TextView title = line(activity, heading, 21, 0xFFF7F9FF, true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = dp(activity, 11);
        layout.addView(title, titleP);

        TextView body = line(activity, description, 14, 0xFFCBD6EA, false);
        body.setLineSpacing(dp(activity, 3), 1.0f);
        LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(-1, -2);
        bodyP.topMargin = dp(activity, 12);
        layout.addView(body, bodyP);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsP = new LinearLayout.LayoutParams(-1, -2);
        actionsP.topMargin = dp(activity, 23);
        layout.addView(actions, actionsP);

        Button cancel = button(activity, "Отмена", 0xFF334057);
        Button accept = button(activity, confirmText, destructive ? 0xFF694251 : 0xFF6554CF);

        if (destructive) {
            LinearLayout.LayoutParams cancelP = new LinearLayout.LayoutParams(0, dp(activity, 50), 1);
            cancelP.rightMargin = dp(activity, 9);
            actions.addView(cancel, cancelP);
        }
        LinearLayout.LayoutParams acceptP = new LinearLayout.LayoutParams(0, dp(activity, 50), destructive ? 1 : 2);
        actions.addView(accept, acceptP);

        cancel.setOnClickListener(v -> dialog.dismiss());
        accept.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });

        dialog.setContentView(layout);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.65f;
            window.setAttributes(params);
        }
        dialog.show();
        if (window != null) {
            int width = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32);
            window.setLayout(Math.min(width, dp(activity, 460)), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }
}
