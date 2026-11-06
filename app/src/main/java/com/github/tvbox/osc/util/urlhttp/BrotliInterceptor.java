package com.github.tvbox.osc.util.urlhttp;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.urlhttp.internal.BrotliSource;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.GzipSource;
import okio.Okio;
import okio.Source;

public class BrotliInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request userRequest = chain.request();
        // A range is over the encoded representation, not the decoded bytes.
        if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
            userRequest = chain.request().newBuilder()
                    .header("Accept-Encoding", "br,gzip")
                    .build();
          return uncompress(chain.proceed(userRequest));
        }
        return chain.proceed(userRequest);
    }
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean isNotEmpty(@Nullable CharSequence str) {
        return !isEmpty(str);
    }
    @NotNull
    public final Response uncompress(@NotNull Response response) throws IOException {
        ResponseBody body = response.body();
        if (body != null && !response.request().method().equals("HEAD")
                && response.code() >= 200 && response.code() != 204 && response.code() != 304) {
            String encoding = response.header("Content-Encoding");
            if (isNotEmpty(encoding)) {
                Source brotliSource;
                try {
                    if (encoding.equalsIgnoreCase("br")) {
                        brotliSource = BrotliSource.create(body.source());
                    } else if (encoding.equalsIgnoreCase("gzip")) {
                        brotliSource = new GzipSource(body.source());
                    } else {
                        return response;
                    }
                } catch (IOException e) {
                    body.close();
                    throw e;
                }
                return response.newBuilder()
                        .removeHeader("Content-Encoding")
                        .removeHeader("Content-Length")
                        .body(ResponseBody.create(body.contentType(), -1L, Okio.buffer(brotliSource)))
                        .build();
            } else {
                return response;
            }
        } else {
            return response;
        }
    }
}


