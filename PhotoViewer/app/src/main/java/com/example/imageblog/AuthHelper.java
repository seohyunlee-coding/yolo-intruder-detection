package com.example.imageblog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AuthHelper {
    private static final String TAG = "AuthHelper";
    private static final String PREFS = "auth_prefs";
    private static final String KEY_TOKEN = "auth_token";
    // 사용자 제공 자격증명 (요청에 따라 고정)
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "myblog1008";
    private static final String TOKEN_URL = "https://cwijiq.pythonanywhere.com/api-token-auth/";

    // in-memory 캐시와 락
    private static volatile String cachedToken = null;
    private static final Object LOCK = new Object();
    private static final String KEY_TOKEN_INVALID = "auth_token_invalid";

    // 동기적으로 토큰을 반환. SharedPreferences에 캐시. 실패 시 null.
    public static String getToken(Context ctx) {
        if (ctx == null) return null;
        // in-memory 캐시 우선
        if (cachedToken != null && !cachedToken.isEmpty()) return cachedToken;

        synchronized (LOCK) {
            if (cachedToken != null && !cachedToken.isEmpty()) return cachedToken;

            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String token = sp.getString(KEY_TOKEN, null);
            if (token != null && !token.isEmpty()) {
                cachedToken = token;
                return cachedToken;
            }

            // 토큰이 없으면 서버에서 가져와 저장
            OkHttpClient client = new OkHttpClient();
            try {
                JSONObject json = new JSONObject();
                json.put("username", USERNAME);
                json.put("password", PASSWORD);
                RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request req = new Request.Builder()
                        .url(TOKEN_URL)
                        .post(body)
                        .build();
                Response resp = client.newCall(req).execute();
                if (resp.isSuccessful()) {
                    String respBody = resp.body() != null ? resp.body().string() : null;
                    if (respBody != null) {
                        JSONObject robj = new JSONObject(respBody);
                        if (robj.has("token")) {
                            token = robj.optString("token", null);
                            if (token != null) {
                                sp.edit().putString(KEY_TOKEN, token).apply();
                                cachedToken = token;
                                Log.d(TAG, "token obtained and saved");
                                resp.close();
                                return token;
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "getToken: response not successful: " + resp.code());
                }
                resp.close();
            } catch (IOException e) {
                Log.e(TAG, "getToken: network error", e);
            } catch (Exception e) {
                Log.e(TAG, "getToken: parse error", e);
            }
            return null;
        }
    }

    // 강제로 토큰을 삭제(예: 로그아웃용)
    public static void clearToken(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            cachedToken = null;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply();
        }
    }

    // 서버에서 토큰이 작동하지 않는다고 판단될 때 표시용 플래그를 설정
    public static void setTokenInvalid(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TOKEN_INVALID, true).apply();
    }

    public static boolean isTokenInvalid(Context ctx) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TOKEN_INVALID, false);
    }

    public static void clearTokenInvalid(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN_INVALID).apply();
    }
}
