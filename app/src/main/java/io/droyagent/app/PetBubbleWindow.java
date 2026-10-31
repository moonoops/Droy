package io.droyagent.app;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import java.util.function.Consumer;
import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;

/** A floating Window supplies bounded compositor blur without capturing the screen. */
final class PetBubbleWindow {
    private final ComponentDialog dialog;
    private final Window window;
    private final WindowManager manager;
    private final PetBubbleDrawable background;
    private final int blurRadius;
    private final Consumer<Boolean> blurChanged = this::applyBlur;
    private boolean hidden, listening, blurEnabled;

    PetBubbleWindow(Context context, View content, PetBubbleDrawable background,
                    WindowManager.LayoutParams parameters, Runnable onBack) {
        this.background = background;
        manager = context.getSystemService(WindowManager.class);
        blurRadius = Math.min(100, Math.round(8 * context.getResources().getDisplayMetrics().density));
        dialog = new ComponentDialog(context, R.style.DroyPetBubbleTheme) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) return content.dispatchTouchEvent(event);
                return super.onTouchEvent(event);
            }
        };
        dialog.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { onBack.run(); }
        });
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) onBack.run();
            return true;
        });
        window = java.util.Objects.requireNonNull(dialog.getWindow());
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawable(background);
        window.setElevation(0);
        window.setDecorFitsSystemWindows(false);
        window.setAttributes(parameters);
        window.setContentView(content);
        window.getDecorView().setPadding(0, 0, 0, 0);
    }

    void show() {
        dialog.show();
        if (Build.VERSION.SDK_INT >= 31) {
            manager.addCrossWindowBlurEnabledListener(blurChanged);
            listening = true;
            applyBlur(manager.isCrossWindowBlurEnabled());
        } else applyBlur(false);
    }

    void update(WindowManager.LayoutParams parameters) { window.setAttributes(parameters); }
    View surface() { return window.getDecorView(); }

    void setHidden(boolean hidden) {
        this.hidden = hidden;
        surface().setAlpha(hidden ? 0f : 1f);
        surface().setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        applyBlur(blurEnabled);
    }

    private void applyBlur(boolean enabled) {
        blurEnabled = enabled;
        background.setBlurAvailable(enabled);
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(!hidden && enabled ? blurRadius : 0);
    }

    void dismiss() {
        if (Build.VERSION.SDK_INT >= 31 && listening) {
            manager.removeCrossWindowBlurEnabledListener(blurChanged);
            listening = false;
        }
        // An Activity can remove its owned windows before the overlay gets its lifecycle callback.
        if (surface().getParent() != null) dialog.dismiss();
    }
}
