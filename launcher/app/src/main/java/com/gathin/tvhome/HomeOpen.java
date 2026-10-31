package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings this launcher's Home to the front from anywhere: the Home helper, Settings, Weather, the profile picker.
 *
 * Android files each task as either "home" or "standard" and refuses to start the same activity under the other type
 * ("Can't change activity type once set"). With Google TV's home kept as Android's Home (for the Assistant), this
 * launcher's task can end up as either, depending on how it was last opened. So try the plain start first and the
 * Home-intent start second; never let the refusal escape, because in the key helper an uncaught exception crashes
 * the service and leaves the remote's buttons stuck.
 */
final class HomeOpen {
    private static final String TAG = "TvHome.HomeOpen";

    private HomeOpen() {
    }

    static boolean open(Context ctx) {
        // CATEGORY_HOME without ACTION_MAIN is no Home intent (the task stays "standard"), but MainActivity.onNewIntent
        // still reads the category and goes back to the first tile.
        Intent plain = new Intent(ctx, MainActivity.class).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (Intent i : new Intent[]{plain, home}) {
            try {
                ctx.startActivity(i);
                return true;
            } catch (RuntimeException refused) {
                Log.w(TAG, "start refused, trying the other way: " + refused.getMessage());
            }
        }
        return false;
    }
}
