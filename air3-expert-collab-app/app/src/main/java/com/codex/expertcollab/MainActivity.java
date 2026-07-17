package com.codex.expertcollab;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setBackgroundColor(Color.rgb(9, 13, 17));
        status.setGravity(Gravity.CENTER);
        status.setText("叮当专家协同\n正在准备眼镜端");
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        setContentView(status);
    }
}
