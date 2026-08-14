package com.codex.air3nativecamera.runtime;

import static org.junit.Assert.assertEquals;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.junit.Test;

public final class DeviceActivationQrDecoderTest {
    @Test
    public void decodesTheRawOneTimeActivationCodeFromArgbPixels() throws Exception {
        String code = "HF9-ABCD-EFGH-JKLM";
        BitMatrix matrix = new MultiFormatWriter().encode(
                code, BarcodeFormat.QR_CODE, 160, 160);
        int[] pixels = pixels(matrix);

        assertEquals(code, DeviceActivationQrDecoder.decodeArgb(
                matrix.getWidth(), matrix.getHeight(), pixels));
    }

    @Test
    public void acceptsOnlyOurActivationPayloadAndRejectsOtherLinks() {
        assertEquals(
                "HF9-ABCD-EFGH-JKLM",
                DeviceActivationQrDecoder.normalizePayload("  HF9-abcd-efgh-jklm  "));
        assertEquals(
                "HF9-ABCD-EFGH-JKLM",
                DeviceActivationQrDecoder.normalizePayload(
                        "dingdang-v9://activate?code=HF9-ABCD-EFGH-JKLM"));
        assertEquals("", DeviceActivationQrDecoder.normalizePayload(
                "https://evil.example/activate?code=HF9-ABCD-EFGH-JKLM"));
        assertEquals("", DeviceActivationQrDecoder.normalizePayload("not-a-code"));
    }

    private static int[] pixels(BitMatrix matrix) {
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                pixels[y * matrix.getWidth() + x] = matrix.get(x, y)
                        ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return pixels;
    }
}
