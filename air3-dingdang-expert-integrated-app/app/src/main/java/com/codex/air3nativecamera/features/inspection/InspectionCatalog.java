package com.codex.air3nativecamera.features.inspection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class InspectionCatalog {
    private final List<InspectionTaskDefinition> tasks;

    private InspectionCatalog(List<InspectionTaskDefinition> tasks) {
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    public static InspectionCatalog defaultCatalog() {
        List<InspectionTaskDefinition> tasks = new ArrayList<>();
        tasks.add(task("lab-training-room", "实训室设备巡检", "现场逐点拍照，AI识别后由人员确认结果。",
                InspectionTaskDefinition.Scope.MY_TASK,
                devices("交换机", "电表", "温湿度传感器", "服务器"),
                point("switch-led", "交换机指示灯", "拍摄电源、SYS/ALM、端口灯、上联口和网线状态",
                        "SYS 绿灯常亮，ALM 未亮，端口有收发"),
                point("meter-reading", "电表读数", "识别当前读数并与上次记录比较", "1286.4 kWh"),
                point("environment", "温湿度环境", "识别温湿度显示与传感器状态", "24.6 C / 48%RH"),
                point("server-status", "服务器运行状态", "检查电源、告警灯和风扇运行状态", "运行正常，无告警")));
        tasks.add(task("water-power-heating", "水电暖系统巡检", "逐点核对供电、供水与供暖关键运行参数。",
                InspectionTaskDefinition.Scope.INDUSTRY,
                devices("配电柜", "电表", "水泵", "压力表", "供回水温度计"),
                point("power-cabinet", "配电柜状态", "拍摄开关位置、告警灯、表计与异常温升痕迹", "运行正常，无告警"),
                point("energy-meter", "电表读数", "识别电量读数并与上次巡检值比较", "3628.7 kWh"),
                point("water-pump", "水泵与阀门", "检查运行灯、泵体渗漏、振动和阀门位置", "1号泵运行，阀门开启"),
                point("pipe-pressure", "管网压力", "识别压力表读数并检查指针和表盘状态", "0.42 MPa"),
                point("supply-return", "供回水温度", "识别供水与回水温度，比较温差变化", "供水 52 C / 回水 43 C")));
        tasks.add(task("air-conditioning", "空调系统巡检", "核对控制、运行参数、过滤与送回风状态。",
                InspectionTaskDefinition.Scope.INDUSTRY,
                devices("空调控制器", "空调机组", "过滤器", "冷凝水盘", "风口"),
                point("controller", "空调控制面板", "识别设定温度、运行模式与告警代码", "制冷 24 C，无告警"),
                point("running-params", "机组运行参数", "读取进出风温度、压力或电流等可见参数", "送风 17 C / 回风 25 C"),
                point("filter-drain", "过滤器与冷凝水", "检查过滤器积尘、压差提示、积水和排水状态", "过滤器清洁，排水正常"),
                point("airflow", "送回风状态", "检查风口遮挡、风量表现和异常结露", "风口无堵塞，无结露")));
        tasks.add(task("fire-safety", "消防系统巡检", "逐点核对报警、消防泵、器材和疏散设施状态。",
                InspectionTaskDefinition.Scope.INDUSTRY,
                devices("消防报警主机", "消防泵柜", "灭火器", "应急照明"),
                point("fire-panel", "消防报警主机", "拍摄主机运行、故障、屏蔽和火警指示", "主机运行，无火警故障"),
                point("fire-pump", "消防泵控制柜", "检查电源、手自动位置、泵组状态与压力显示", "自动位置，压力正常"),
                point("extinguisher", "灭火器材", "识别压力指针、铅封、有效期和摆放位置", "压力绿色区，铅封完整"),
                point("emergency-light", "应急照明与疏散指示", "检查灯具亮度、方向、外观与测试状态", "指示清晰，灯具完好")));
        return new InspectionCatalog(tasks);
    }

    public InspectionTaskDefinition find(String id) {
        for (InspectionTaskDefinition task : tasks) {
            if (task.id().equals(id)) return task;
        }
        return null;
    }

    public List<InspectionTaskDefinition> tasks() { return tasks; }

    public List<InspectionTaskDefinition> industryTasks() {
        List<InspectionTaskDefinition> result = new ArrayList<>();
        for (InspectionTaskDefinition task : tasks) {
            if (task.scope() == InspectionTaskDefinition.Scope.INDUSTRY) result.add(task);
        }
        return Collections.unmodifiableList(result);
    }

    private static InspectionTaskDefinition task(String id, String title, String summary,
            InspectionTaskDefinition.Scope scope, LinkedHashSet<String> devices,
            InspectionTaskDefinition.Point... points) {
        return new InspectionTaskDefinition(id, title, summary, scope, devices, Arrays.asList(points));
    }

    private static LinkedHashSet<String> devices(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static InspectionTaskDefinition.Point point(String id, String title, String detail) {
        return new InspectionTaskDefinition.Point(id, title, detail, true);
    }

    private static InspectionTaskDefinition.Point point(String id, String title, String detail,
            String previousValue) {
        return new InspectionTaskDefinition.Point(id, title, detail, previousValue, true);
    }
}
