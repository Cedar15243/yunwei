package com.codex.air3nativecamera.voice;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SherpaOfflineRecognizerInstrumentedTest {
    @Test
    public void recognizesKnownChineseMaintenancePhrase() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = new File(new File(context.getFilesDir(), "qa-input"), "server-fault.wav");
        assertTrue("Missing local ASR fixture: " + wav, wav.isFile() && wav.length() > 44L);

        byte[] pcm = wavPcm(wav);
        SherpaOfflineRecognizer recognizer = new SherpaOfflineRecognizer(context);
        try {
            recognizer.start();
            for (int offset = 0; offset < pcm.length; offset += 3200) {
                int length = Math.min(3200, pcm.length - offset);
                byte[] chunk = new byte[length];
                System.arraycopy(pcm, offset, chunk, 0, length);
                recognizer.acceptPcm16(chunk, chunk.length);
            }
            String text = recognizer.finish();
            assertTrue("Unexpected local ASR text: " + text,
                    text.contains("服务器") && text.contains("启动"));
        } finally {
            recognizer.release();
        }
    }

    private static byte[] wavPcm(File wav) throws Exception {
        FileInputStream input = new FileInputStream(wav);
        try {
            long skipped = input.skip(44L);
            assertTrue("Invalid WAV header", skipped == 44L);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }
}
