package dev.prototype.lovense;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A personal role chooser; pairing and permission are handled explicitly after the choice. */
public final class WelcomeActivity extends Activity {
    static final String EXTRA_ROLE = "dev.prototype.lovense.ROLE";
    private int background, foreground, muted, pink;
    private boolean dark;

    @Override public void onCreate(Bundle savedInstanceState) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        String remembered = RolePreferences.get(this);
        // Developer copies default their navigation only; relay pairing still grants authority.
        if (remembered.isEmpty()) remembered = getString(R.string.default_role);
        if (!remembered.isEmpty()) { startActivity(new Intent(this, PairingActivity.class).putExtra(EXTRA_ROLE, remembered)); finish(); return; }
        background = Color.rgb(dark ? 33 : 251, dark ? 27 : 245, dark ? 30 : 239);
        foreground = Color.parseColor(dark ? "#FAEEE9" : "#402D35");
        muted = Color.parseColor(dark ? "#C9B7BD" : "#806D73");
        pink = Color.parseColor(dark ? "#EFB1C5" : "#A03E60");
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(background);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView brand = text(getString(R.string.heel_wordmark), 25, 800, pink);
        brand.setMinHeight(dp(48)); brand.setGravity(Gravity.CENTER_VERTICAL); brand.setClickable(true); brand.setFocusable(true); brand.setContentDescription("Settings");
        brand.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("Settings").setMessage("Pick Owner or Pet once. Heel remembers your choice. You can reset it here after pairing.").setPositiveButton("Close", null).setNeutralButton("Reset role choice", (d,w) -> RolePreferences.reset(this)).show());
        page.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = text(getString(R.string.pick), 38, 800, foreground);
        heading.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(44);
        page.addView(heading, titleParams);

        LinearLayout roles = new LinearLayout(this);
        roles.setOrientation(LinearLayout.HORIZONTAL);
        roles.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rolesParams = new LinearLayout.LayoutParams(-1, -2);
        rolesParams.topMargin = dp(64);
        page.addView(roles, rolesParams);
        roles.addView(role("owner", R.drawable.artemis_owner, R.string.owner, R.string.owner_hint, -5), new LinearLayout.LayoutParams(0, -2, 1));
        View gap = new View(this);
        roles.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        roles.addView(role("pet", R.drawable.artemis_curious, R.string.pet, R.string.pet_hint, 6), new LinearLayout.LayoutParams(0, -2, 1));

        View space = new View(this);
        page.addView(space, new LinearLayout.LayoutParams(1, dp(72), 1));
        TextView footer = text(getString(R.string.local_build_note), 12, 500, muted);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));
        setContentView(scroll);
    }

    private LinearLayout role(String role, int drawable, int label, int hint, float rotation) {
        LinearLayout choice = column();
        choice.setGravity(Gravity.CENTER_HORIZONTAL);
        choice.setPadding(dp(2), dp(8), dp(2), dp(16));
        choice.setTag(role);
        choice.setClickable(true);
        choice.setFocusable(true);
        choice.setContentDescription(getString(label) + ". " + getString(hint));
        choice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        choice.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(24));
        choice.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x24EFB1C5 : 0x18A03E60), null, mask));
        FrameLayout portrait = new FrameLayout(this);
        portrait.setClipChildren(false);
        portrait.setClipToPadding(false);
        View halo = new View(this);
        GradientDrawable haloShape = new GradientDrawable();
        haloShape.setShape(GradientDrawable.OVAL);
        haloShape.setColor(Color.parseColor("owner".equals(role) ? (dark ? "#4B2F3B" : "#F1D6DC") : (dark ? "#423039" : "#F6E8D7")));
        halo.setBackground(haloShape);
        FrameLayout.LayoutParams haloParams = new FrameLayout.LayoutParams(-1, dp(108), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        haloParams.setMargins(dp(9), 0, dp(9), dp(6));
        portrait.addView(halo, haloParams);
        ImageView image = new ImageView(this);
        image.setImageResource(drawable);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setRotation(rotation);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        portrait.addView(image, new FrameLayout.LayoutParams(-1, -1));
        choice.addView(portrait, new LinearLayout.LayoutParams(-1, dp(142)));
        TextView title = text(getString(label), 25, 800, foreground);
        title.setGravity(Gravity.CENTER);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(14);
        choice.addView(title, titleParams);
        TextView subtitle = text(getString(hint), 13, 500, muted);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(5);
        choice.addView(subtitle, hintParams);
        choice.setOnClickListener(view -> { RolePreferences.choose(this, role); startActivity(new Intent(this, PairingActivity.class).putExtra(EXTRA_ROLE, role)); finish(); });
        return choice;
    }

    private TextView text(String value, int size, int weight, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        Typeface font = getResources().getFont(R.font.nunito);
        view.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : Typeface.create(font, weight >= 700 ? Typeface.BOLD : Typeface.NORMAL));
        view.setFontVariationSettings("'wght' " + weight);
        view.setIncludeFontPadding(false);
        return view;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
