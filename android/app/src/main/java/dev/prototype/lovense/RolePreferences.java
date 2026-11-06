package dev.prototype.lovense;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/** Role is a navigation preference, never authorization to control another phone. */
final class RolePreferences {
    static String get(Context context) { return context.getSharedPreferences("heel-navigation", Context.MODE_PRIVATE).getString("role", ""); }
    static void choose(Context context, String role) {
        if (role.equals("owner") || role.equals("pet")) context.getSharedPreferences("heel-navigation", Context.MODE_PRIVATE).edit().putString("role", role).apply();
    }
    static void reset(Context context) { context.getSharedPreferences("heel-navigation", Context.MODE_PRIVATE).edit().remove("role").apply(); }
    static void returnToPick(Activity activity) {
        reset(activity);
        activity.startActivity(new Intent(activity, WelcomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
    }
}
