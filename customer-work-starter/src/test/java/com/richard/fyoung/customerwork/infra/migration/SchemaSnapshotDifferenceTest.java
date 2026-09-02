package com.richard.fyoung.customerwork.infra.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照差异描述的定位准确性。
 *
 * <p>差异描述是漂移门禁唯一的排查线索：快照有十几万字符，报错里那句「哪张表」决定了人要看一眼
 * 还是自己 diff 一遍。种子数据段整体排在全部建表语句之后，若只按建表前缀回溯，数据段里的任何
 * 差异都会被归到<b>最后一张表</b>上——指着一张毫不相干的表报错，比不报表名更误导。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SchemaSnapshotDifferenceTest {

    @Test
    void identicalSnapshotsShouldReportNoDifference() {
        String snapshot = "CREATE TABLE `sys_user` (\n  `id` bigint\n);\n";
        assertNull(SchemaSnapshotExporter.describeDifference(snapshot, snapshot));
    }

    @Test
    void structureDifferenceShouldNameOwningTable() {
        String expected = "CREATE TABLE `sys_user` (\n  `id` bigint NOT NULL\n);\n";
        String actual = "CREATE TABLE `sys_user` (\n  `id` bigint DEFAULT NULL\n);\n";
        String difference = SchemaSnapshotExporter.describeDifference(expected, actual);
        assertTrue(difference != null && difference.contains("表 sys_user"),
            "结构差异应指出所属表，实际：" + difference);
    }

    /** 数据段差异必须报自己那张表，不能回溯到它前面最后一条建表语句上。 */
    @Test
    void seedDifferenceShouldNameSeedTableNotLastCreatedTable() {
        String head = "CREATE TABLE `sys_user` (\n  `id` bigint\n);\n"
            + "CREATE TABLE `workbench_token` (\n  `id` bigint\n);\n"
            + "INSERT INTO `ai_model_price` (`id`) VALUES\n";
        String difference = SchemaSnapshotExporter.describeDifference(head + "  (1);\n", head + "  (2);\n");
        assertTrue(difference != null && difference.contains("表 ai_model_price 的种子数据"),
            "种子数据差异应指出自己那张表，实际：" + difference);
        assertTrue(difference != null && !difference.contains("workbench_token"),
            "种子数据差异不应归到前面最后一张建表上，实际：" + difference);
    }

    @Test
    void headerDifferenceShouldBeReportedAsHeader() {
        String difference = SchemaSnapshotExporter.describeDifference(
            "-- 对应版本：Flyway V100\n", "-- 对应版本：Flyway V101\n");
        assertTrue(difference != null && difference.contains("文件头"),
            "文件头差异应标记为文件头，实际：" + difference);
    }

    /** 一侧整段缺失（例如旧快照还没有种子段）时，要说明是哪一侧多出、从哪张表开始。 */
    @Test
    void extraTrailingLinesShouldBeReported() {
        // 结尾刻意不留换行：留了的话 split 会在两侧各产生一个空尾元素，
        // 先撞上的是「空行 vs 有内容」的逐行差异，走不到「多出行」这个分支。
        String expected = "CREATE TABLE `sys_user` (\n  `id` bigint\n);";
        String difference = SchemaSnapshotExporter.describeDifference(
            expected, expected + "\nINSERT INTO `sys_user` (`id`) VALUES\n  (1);");
        assertTrue(difference != null && difference.contains("迁移产物")
            && difference.contains("多出"), "多出的行应说明是哪一侧多出，实际：" + difference);
        assertTrue(difference != null && difference.contains("sys_user"),
            "应指出从哪张表开始多出，实际：" + difference);
    }
}
