package com.sp00nznet.swep1racer;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The art and its interactive overlays use the same aspect-fitted reference frame. */
final class PodOneLauncherView extends FrameLayout {
    private static final int ART_WIDTH = 1672;
    private static final int ART_HEIGHT = 941;
    private static final int[] ROW_TOPS = {257, 357, 443, 529, 615};
    private static final String[] LABELS = {"Play Game", "Settings", "Updates", "Diagnostics", "Quit"};
    private static final String[] DETAILS = {
        "Start Star Wars Racer", "Graphics, audio, controls & more", "Check for new releases",
        "Logs, speaker test & troubleshooting", "Exit to Android"
    };
    private static final String[] SYMBOLS = {"▶", "⚙", "↻", "ⓘ", "➜"};

    private final FrameLayout stage;
    private final MenuButton[] buttons = new MenuButton[LABELS.length];
    private final TextView fallbackTitle;
    private final TextView buildVersion;
    private final TextView selectHint;
    private final TextView changeRom;
    private final View footerRule;
    private float measuredScale = -1;

    PodOneLauncherView(Activity activity, Runnable[] actions, Runnable pickRom) {
        super(activity);
        setBackgroundColor(0xFF061323);
        stage = new FrameLayout(activity);
        addView(stage, new LayoutParams(0, 0, Gravity.CENTER));

        ImageView art = new ImageView(activity);
        int resource = getResources().getIdentifier("pod_one_launcher_background", "drawable",
                activity.getPackageName());
        art.setImageResource(resource != 0 ? resource : R.drawable.launcher_background);
        art.setScaleType(resource != 0 ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.CENTER_CROP);
        stage.addView(art, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        fallbackTitle = text(activity, "POD ONE\nSTAR WARS EPISODE I: RACER", 0xFFFFD787);
        fallbackTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fallbackTitle.setGravity(Gravity.CENTER_VERTICAL);
        fallbackTitle.setVisibility(resource == 0 ? VISIBLE : GONE);
        stage.addView(fallbackTitle, new LayoutParams(0, 0));

        for (int i = 0; i < buttons.length; i++) {
            MenuButton button = new MenuButton(activity, SYMBOLS[i], LABELS[i], DETAILS[i], actions[i]);
            button.setId(View.generateViewId());
            buttons[i] = button;
            stage.addView(button, new LayoutParams(0, 0));
        }
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setNextFocusUpId(buttons[(i + buttons.length - 1) % buttons.length].getId());
            buttons[i].setNextFocusDownId(buttons[(i + 1) % buttons.length].getId());
        }

        buildVersion = text(activity, "Pod One " + installedVersion(activity), 0xFFF0F6FC);
        buildVersion.setGravity(Gravity.CENTER);
        stage.addView(buildVersion, new LayoutParams(0, 0));

        footerRule = new View(activity);
        footerRule.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x00EAB84F, 0xFFD7A744, 0x00EAB84F}));
        stage.addView(footerRule, new LayoutParams(0, 0));

        selectHint = text(activity, "A  Select", 0xFFF0F6FC);
        selectHint.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        stage.addView(selectHint, new LayoutParams(0, 0));

        changeRom = text(activity, "Y  Change ROM", 0xFFF0F6FC);
        changeRom.setGravity(Gravity.CENTER);
        changeRom.setOnClickListener(view -> pickRom.run());
        changeRom.setFocusable(false);
        changeRom.setContentDescription("Choose a different ROM");
        stage.addView(changeRom, new LayoutParams(0, 0));
    }

    static void enterFullscreen(Activity activity) {
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static String installedVersion(Activity activity) {
        try {
            return "v" + activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "Development";
        }
    }

    void setRomState(boolean hasRom, boolean importing) {
        buttons[0].setLabel(importing ? "Importing ROM…" : hasRom ? "Play Game" : "ROM Selection",
                importing ? "Checking the selected file" : hasRom ? DETAILS[0] : "Choose your Racer ROM");
        buttons[0].setEnabled(!importing);
        changeRom.setVisibility(hasRom && !importing ? VISIBLE : GONE);
    }

    void focusFirstAction() {
        buttons[0].requestFocus();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int availableWidth = MeasureSpec.getSize(widthSpec);
        int availableHeight = MeasureSpec.getSize(heightSpec);
        float inset = Math.min(availableWidth, availableHeight) * 0.012f;
        float scale = Math.min(Math.max(1f, availableWidth - 2 * inset) / ART_WIDTH,
                Math.max(1f, availableHeight - 2 * inset) / ART_HEIGHT);
        if (scale != measuredScale) {
            measuredScale = scale;
            LayoutParams stageParams = (LayoutParams) stage.getLayoutParams();
            stageParams.width = px(ART_WIDTH);
            stageParams.height = px(ART_HEIGHT);
            place(fallbackTitle, 48, 30, 630, 190);
            size(fallbackTitle, 43);

            for (int i = 0; i < buttons.length; i++) {
                place(buttons[i], 41, ROW_TOPS[i], 423, i == 0 ? 88 : 76);
                buttons[i].rescale();
            }
            place(buildVersion, 48, 842, 232, 50);
            buildVersion.setBackground(surface(0xEF071A2D, 0xFF56748A, 25));
            size(buildVersion, 20);
            place(footerRule, 294, 866, 997, 2);
            place(changeRom, 1310, 831, 180, 65);
            place(selectHint, 1500, 831, 125, 65);
            size(selectHint, 21);
            size(changeRom, 21);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private int px(int referencePixels) { return Math.max(1, Math.round(referencePixels * measuredScale)); }

    private void place(View view, int left, int top, int width, int height) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        params.leftMargin = px(left);
        params.topMargin = px(top);
        params.width = px(width);
        params.height = px(height);
    }

    private void size(TextView view, int referencePixels) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(referencePixels));
    }

    private static TextView text(Activity activity, String label, int color) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(px(radius));
        drawable.setStroke(px(2), stroke);
        return drawable;
    }

    private GradientDrawable buttonBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                selected ? new int[] {0xFF362200, 0xFF654000} : new int[] {0xF2051324, 0xF208233B});
        drawable.setCornerRadius(px(22));
        drawable.setStroke(px(selected ? 4 : 3), selected ? 0xFFFFD36D : 0xFF319CDD);
        return drawable;
    }

    private class MenuButton extends LinearLayout {
        private final String defaultSymbol;
        private final TextView symbol;
        private final TextView title;
        private final TextView detail;

        MenuButton(Activity activity, String icon, String label, String description, Runnable action) {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusableInTouchMode(true);
            setOnClickListener(view -> {
                requestFocus();
                action.run();
            });
            defaultSymbol = icon;
            symbol = text(activity, icon, 0xFFA7DBFF);
            symbol.setGravity(Gravity.CENTER);
            addView(symbol, new LinearLayout.LayoutParams(0, -1));

            LinearLayout copy = new LinearLayout(activity);
            copy.setOrientation(VERTICAL);
            title = text(activity, "", 0xFFFFF1D0);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            detail = text(activity, "", 0xFFFFF6E8);
            for (TextView line : new TextView[] {title, detail}) {
                line.setSingleLine(true);
                line.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(line);
            }
            addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            setLabel(label, description);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }

        void setLabel(String label, String description) {
            title.setText(label);
            detail.setText(description);
            symbol.setText("ROM Selection".equals(label) ? "▣" : defaultSymbol);
            setContentDescription(label + ". " + description);
        }

        void rescale() {
            LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) symbol.getLayoutParams();
            iconParams.width = px(110);
            setPadding(px(5), 0, px(8), 0);
            size(symbol, 46);
            size(title, 29);
            size(detail, 18);
            detail.setPadding(0, px(4), 0, 0);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[] {android.R.attr.state_pressed}, buttonBackground(true));
            states.addState(new int[] {android.R.attr.state_focused}, buttonBackground(true));
            states.addState(new int[0], buttonBackground(false));
            setBackground(states);
        }
    }
}
