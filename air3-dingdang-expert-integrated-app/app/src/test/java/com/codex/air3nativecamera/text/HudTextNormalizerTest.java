package com.codex.air3nativecamera.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudTextNormalizerTest {

    @Test
    public void removesMarkdownStructureWithoutDroppingFieldContent() {
        String markdown = "## 检查结论\n"
                + "- **确认供电**\n"
                + "- 查看 [设备手册](https://example.invalid/manual)\n\n"
                + "```sh\n"
                + "cat /sys/class/thermal/thermal_zone0/temp\n"
                + "```";

        assertEquals("检查结论\n"
                        + "• 确认供电\n"
                        + "• 查看 设备手册\n\n"
                        + "cat /sys/class/thermal/thermal_zone0/temp",
                HudTextNormalizer.normalize(markdown));
    }

    @Test
    public void preservesOrderedStepsAndTechnicalLiterals() {
        String markdown = "1. 检查 `TEMP_SENSOR_1`\n"
                + "2. 确认 4*20mA 信号\n"
                + "3. 执行 `find **/*.log`";

        assertEquals("1. 检查 TEMP_SENSOR_1\n"
                        + "2. 确认 4*20mA 信号\n"
                        + "3. 执行 find **/*.log",
                HudTextNormalizer.normalize(markdown));
    }

    @Test
    public void hidesIncompleteStreamingEmphasisMarkers() {
        assertEquals("请先检查电源模块", HudTextNormalizer.normalize("**请先检查电源模块"));
        assertEquals("确认接口后继续", HudTextNormalizer.normalize("*确认接口后继续"));
    }

    @Test
    public void keepsAtMostOneBlankLine() {
        assertEquals("第一段\n\n第二段", HudTextNormalizer.normalize("第一段\n\n\n\n第二段"));
    }
}
