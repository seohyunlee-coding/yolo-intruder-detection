package com.example.imageblog;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class NetworkClient {
    private static volatile OkHttpClient client = null;

    public static OkHttpClient getClient(Context ctx) {
        if (client != null) return client;
        synchronized (NetworkClient.class) {
            if (client == null) {
                OkHttpClient.Builder b = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .addInterceptor(new AuthInterceptor(ctx));
                client = b.build();
            }
            return client;
        }
    }
}
