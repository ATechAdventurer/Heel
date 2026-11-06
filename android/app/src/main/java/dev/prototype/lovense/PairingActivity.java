package dev.prototype.lovense;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Personal invitation flow. No account, contact permission, or automatic message sending. */
public final class PairingActivity extends Activity implements RelayClient.Listener {
    static final String EXTRA_REMOTE = "dev.prototype.lovense.REMOTE";
    private RelayClient relay;
    private LinearLayout page, content;
    private TextView connection;
    private String role, phase = "", invite = "", typedCode = "", owner = "", pet = "", species = "puppy";
    private EditText enrollment, codeInput, ownerInput, petInput, speciesInput;
    private boolean resettingRole;
    private boolean resumed, launched;
    private int bg, fg, muted, accent, surface;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = Color.parseColor(dark ? "#211B1E" : "#FBF5EF"); fg = Color.parseColor(dark ? "#FAEEE9" : "#402D35");
        muted = Color.parseColor(dark ? "#C9B7BD" : "#806D73"); accent = Color.parseColor(dark ? "#EFB1C5" : "#A03E60"); surface = Color.parseColor(dark ? "#382A32" : "#F1D6DC");
        role = "pet".equals(getIntent().getStringExtra(WelcomeActivity.EXTRA_ROLE)) ? "pet" : "owner";
        relay = RelayClient.get(this);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg); scroll.setFitsSystemWindows(true);
        page = column(); page.setPadding(dp(24), dp(20), dp(24), dp(24));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); TextView brand = text(getString(R.string.heel_wordmark), 25, true); brand.setMinHeight(dp(48)); brand.setContentDescription("Settings"); brand.setFocusable(true); brand.setOnClickListener(v -> showSettings()); top.addView(brand, new LinearLayout.LayoutParams(-1, -2)); page.addView(top);
        ImageView art = new ImageView(this); art.setImageResource(role.equals("owner") ? R.drawable.artemis_owner : R.drawable.artemis_curious);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER); art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); page.addView(art, new LinearLayout.LayoutParams(-1, dp(132)));
        connection = text("Connecting…", 13, false); connection.setTextColor(muted); connection.setGravity(Gravity.CENTER); page.addView(connection);
        content = column(); content.setPadding(0, dp(20), 0, 0); page.addView(content);
        scroll.addView(page); setContentView(scroll); showUnpaired();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; launched = false; relay.attach(this); if (relay.hasToken()) relay.connect(null); }
    @Override protected void onPause() { resumed = false; relay.detach(this); super.onPause(); }
    @Override public void onConnection(boolean connected, String reason) { if (connection != null) connection.setText(reason); }
    private void remember() {
        if (ownerInput != null) owner = ownerInput.getText().toString(); if (petInput != null) pet = petInput.getText().toString();
        if (speciesInput != null) species = speciesInput.getText().toString(); if (codeInput != null) typedCode = codeInput.getText().toString();
    }
    private void clear(String next) { remember(); ownerInput = petInput = speciesInput = codeInput = null; phase = next; content.removeAllViews(); }
    private void showUnpaired() {
        clear("unpaired");
        content.addView(text(role.equals("owner") ? "Make it yours" : "Someone waiting?", 28, true));
        if (relay.endpoint().isEmpty()) {
            content.addView(text("Enter the relay address from your host to connect your phones.", 14, false));
            content.addView(button("Set relay address", this::advancedSettings));
            return;
        }
        if (!relay.hasToken()) {
            content.addView(text("First, give this phone its private setup code.", 14, false));
            enrollment = input("Phone setup code", ""); enrollment.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); content.addView(enrollment);
            content.addView(button("Connect this phone", () -> relay.connect(enrollment.getText().toString())));
        } else content.addView(button("Reconnect", () -> relay.connect(null)));
        if (role.equals("owner")) {
            ownerInput = input("Your name", owner); content.addView(ownerInput);
            petInput = input("Their name", pet); content.addView(petInput);
            speciesInput = input("Their kind · puppy, bunny…", species); content.addView(speciesInput);
            content.addView(button("Make an invitation", () -> {
                remember();
                if (owner.trim().isEmpty() || pet.trim().isEmpty() || species.trim().isEmpty()) { connection.setText("Fill in the names and their kind first."); showUnpaired(); return; }
                if (!relay.send(RelayClient.json("invite", "owner", owner, "pet", pet, "species", species))) connection.setText("Connect this phone first.");
            }));
        } else {
            codeInput = input("Invitation code", typedCode); codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); content.addView(codeInput);
            content.addView(button("Find my owner", () -> {
                typedCode = codeInput.getText().toString();
                if (!relay.send(RelayClient.json("preview", "code", typedCode))) connection.setText("Connect this phone first.");
            }));
        }
    }
    @Override public void onRelay(JSONObject message) {
        String type = message.optString("type");
        if (type.equals("error")) { connection.setText(message.optString("message", "Could not complete that action.")); return; }
        if (type.equals("invite")) { invite = message.optString("code"); showInvite(); return; }
        if (type.equals("preview")) { showPreview(message.optJSONObject("profile")); return; }
        if (!type.equals("state")) return;
        String next = message.optString("phase");
        if (resettingRole && next.equals("unpaired")) { resettingRole = false; RolePreferences.returnToPick(this); return; }
        if (next.equals("paired")) {
            if (resumed && !launched) { launched = true; RolePreferences.choose(this, message.optString("role")); startActivity(new Intent(this, MainActivity.class).putExtra(EXTRA_REMOTE, true).putExtra(WelcomeActivity.EXTRA_ROLE, message.optString("role"))); finish(); }
        } else if (next.equals("approval")) showApproval(message);
        else if (next.equals("inviting")) { if (!phase.equals("inviting")) showInvite(); }
        else if (!phase.equals("unpaired") || enrollment != null && relay.hasToken()) { enrollment = null; showUnpaired(); }
    }
    private void showInvite() {
        clear("inviting"); content.addView(text("A little invitation", 28, true));
        content.addView(text("Send this code to your " + species + ". It expires in 10 minutes.", 15, false));
        TextView value = text(invite.isEmpty() ? "Cancel, then make a new invitation" : invite, 27, true); value.setTextIsSelectable(true); value.setGravity(Gravity.CENTER); value.setPadding(0, dp(18), 0, dp(18)); content.addView(value);
        if (!invite.isEmpty()) content.addView(button("Copy code", () -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Heel invitation", invite)); connection.setText("Copied · send it privately"); }));
        content.addView(text("You’ll approve the other phone before it can join.", 14, false));
        content.addView(button("Cancel invitation", () -> relay.send(RelayClient.json("unpair"))));
    }
    private void showPreview(JSONObject profile) {
        if (profile == null) return;
        clear("preview"); content.addView(text("There you are", 28, true));
        content.addView(text(profile.optString("owner") + " would like to call you " + profile.optString("pet") + ", their " + profile.optString("species") + ".", 19, true));
        content.addView(text("Your phone connects to your device. Your owner can control it after you tap Ready. Pause is a temporary break; Safe Word stays stopped until you choose Ready again. Stop and unpair always stay yours.", 15, false));
        content.addView(button("That’s me", () -> relay.send(RelayClient.json("accept", "code", typedCode))));
        content.addView(button("Go back", this::showUnpaired));
    }
    private void showApproval(JSONObject message) {
        clear("approval"); boolean owning = message.optString("role").equals("owner");
        content.addView(text(owning ? "Your pet is here" : "One little check", 28, true));
        JSONObject profile = message.optJSONObject("profile");
        content.addView(text(profile == null ? "Compare this number on both phones." : profile.optString("owner") + " + " + profile.optString("pet"), 18, true));
        TextView number = text(message.optString("confirmation"), 36, true); number.setGravity(Gravity.CENTER); number.setPadding(0, dp(20), 0, dp(20)); content.addView(number);
        content.addView(text("Compare this number with the other phone before approving.", 15, false));
        if (owning) content.addView(button("The numbers match · pair", () -> relay.send(RelayClient.json("approve"))));
        else content.addView(text("Waiting for your owner’s approval…", 15, false));
        content.addView(button("Cancel", () -> relay.send(RelayClient.json("unpair"))));
    }
    private void showSettings() {
        String[] choices = new String[]{"Reset role choice", "Advanced connection settings"};
        new AlertDialog.Builder(this).setTitle("Settings").setItems(choices, (dialog, which) -> {
            String choice = choices[which];
            if (choice.equals("Reset role choice")) resetRole();
            else advancedSettings();
        }).setNegativeButton("Close", null).show();
    }
    private void resetRole() {
        if (!relay.hasToken() || relay.connected() && relay.state().optString("phase").equals("unpaired")) { relay.pause(); RolePreferences.returnToPick(this); return; }
        if (!relay.connected()) { connection.setText("Reconnect first so Heel can unpair before changing roles."); return; }
        new AlertDialog.Builder(this).setTitle("Unpair and choose again?").setMessage("This stops the pair and returns to Pick. This phone’s setup identity is kept, so you won’t need another setup code.").setNegativeButton("Keep role", null)
                .setPositiveButton("Unpair & reset", (dialog, which) -> { resettingRole = true; relay.pause(); if (!relay.send(RelayClient.json("unpair"))) { resettingRole = false; connection.setText("Reconnect to reset the role."); } }).show();
    }
    private void advancedSettings() {
        EditText url = input("HTTPS relay address", relay.endpoint());
        new AlertDialog.Builder(this).setTitle("Relay address").setMessage("Changing or resetting the address removes this phone’s saved identity. You’ll need a new setup code.")
                .setView(url).setNegativeButton("Keep", null).setPositiveButton("Save / reset", (dialog, which) -> {
                    try { relay.endpoint(url.getText().toString().trim()); invite = ""; enrollment = null; showUnpaired(); }
                    catch (IllegalArgumentException e) { connection.setText(e.getMessage()); }
                }).show();
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(fg); view.setTextSize(size); view.setTypeface(getResources().getFont(R.font.nunito), bold ? Typeface.BOLD : Typeface.NORMAL); view.setFontVariationSettings(bold ? "'wght' 750" : "'wght' 500"); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private EditText input(String hint, String value) {
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setTextColor(fg); input.setHintTextColor(muted); input.setSingleLine(true); input.setTextSize(16); input.setMinHeight(dp(52)); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)}); return input;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(accent); button.setTextSize(15); button.setTypeface(getResources().getFont(R.font.nunito), Typeface.BOLD); button.setFontVariationSettings("'wght' 700"); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(18)); button.setBackground(shape); button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8); params.bottomMargin = dp(2); button.setLayoutParams(params); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

