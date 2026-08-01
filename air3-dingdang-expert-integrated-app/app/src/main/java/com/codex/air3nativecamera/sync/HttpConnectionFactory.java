package com.codex.air3nativecamera.sync;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

interface HttpConnectionFactory {
    HttpURLConnection open(String endpoint) throws IOException;

    HttpConnectionFactory DEFAULT = new HttpConnectionFactory() {
        @Override
        public HttpURLConnection open(String endpoint) throws IOException {
            return (HttpURLConnection) new URL(endpoint).openConnection();
        }
    };
}
