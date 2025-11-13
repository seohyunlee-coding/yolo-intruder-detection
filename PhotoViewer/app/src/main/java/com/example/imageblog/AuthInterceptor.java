package com.example.imageblog;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class AuthInterceptor implements Interceptor {
    private static final String TAG = "AuthInterceptor";
    private final Context ctx;

    public AuthInterceptor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        Request original = chain.request();

        String token = AuthHelper.getToken(ctx);
        Request.Builder rb = original.newBuilder();
        if (token != null && !token.isEmpty()) {
            rb.header("Authorization", "Token " + token);
        }
        Request reqWithAuth = rb.build();
        Response resp = chain.proceed(reqWithAuth);

        if (resp.code() == 401) {
            // 인증 실패: 토큰을 지우고 재발급 시도 후 한 번만 재시도
            resp.close();
            Log.d(TAG, "401 received - clearing token and retrying");
            AuthHelper.clearToken(ctx);
            String newToken = AuthHelper.getToken(ctx);
            if (newToken != null && !newToken.isEmpty()) {
                Request retry = original.newBuilder()
                        .header("Authorization", "Token " + newToken)
                        .build();
                Response retryResp = chain.proceed(retry);
                if (retryResp.code() == 401) {
                    // 재시도도 401이면 토큰 무효 플래그 설정
                    retryResp.close();
                    AuthHelper.setTokenInvalid(ctx);
                    return retryResp;
                }
                return retryResp;
            } else {
                // 재발급 실패 => 토큰 무효 플래그 설정
                AuthHelper.setTokenInvalid(ctx);
            }
        }
        return resp;
    }
}
