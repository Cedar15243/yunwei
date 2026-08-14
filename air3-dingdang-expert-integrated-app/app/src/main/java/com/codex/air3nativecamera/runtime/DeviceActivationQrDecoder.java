package com.codex.air3nativecamera.runtime;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Pattern;

/** Decodes only the one-time V9 device activation QR payload. */
public final class DeviceActivationQrDecoder {
    private static final Pattern ACTIVATION_CODE = Pattern.compile(
            "^HF9-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$");

    private DeviceActivationQrDecoder() {}

    public static String decodeArgb(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || pixels == null || pixels.length != width * height) {
            return "";
        }
        try {
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            MultiFormatReader reader = new MultiFormatReader();
            Result result = reader.decode(bitmap, Collections.singletonMap(
                    DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(BarcodeFormat.QR_CODE)));
            return normalizePayload(result == null ? "" : result.getText());
        } catch (Exception error) {
            return "";
        }
    }

    public static String normalizePayload(String payload) {
        String normalized = clean(payload);
        String direct = normalized.toUpperCase(Locale.ROOT);
        if (ACTIVATION_CODE.matcher(direct).matches()) return direct;
        try {
            URI uri = URI.create(normalized);
            if (!"dingdang-v9".equalsIgnoreCase(uri.getScheme())
                    || !"activate".equalsIgnoreCase(uri.getHost())
                    || uri.getFragment() != null) {
                return "";
            }
            String query = clean(uri.getRawQuery());
            if (!query.startsWith("code=") || query.indexOf('&') >= 0) return "";
            String code = URLDecoder.decode(
                    query.substring("code=".length()), StandardCharsets.UTF_8.name())
                    .trim().toUpperCase(Locale.ROOT);
            return ACTIVATION_CODE.matcher(code).matches() ? code : "";
        } catch (Exception error) {
            return "";
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
