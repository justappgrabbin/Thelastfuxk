package org.mobilemcp.ondevice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.SecureRandom;

final class Auth {
    private static final String PREFS = "mcp_security";
    private static final String KEY = "bearer";
    static synchronized String get(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = p.getString(KEY, null);
        if (value == null) return rotate(ctx);
        return value;
    }
    static synchronized String rotate(Context ctx) {
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        String value = Base64.encodeToString(token, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply();
        return value;
    }
}
