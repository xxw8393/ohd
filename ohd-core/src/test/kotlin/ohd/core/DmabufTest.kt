package ohd.core

import ohd.core.parsers.DmabufParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * dmabuf 解析的两个真实坑：
 * 1. 同一 ino 多行（重复映射）→ 按行求和会多算；
 * 2. buf_name 为空/含空格 → 列错位，必须按尾部相对索引定位。
 */
class DmabufTest {
    private val text = javaClass.getResourceAsStream("/fixtures/dmabuf-info.txt")!!
        .bufferedReader().readText()

    @Test
    fun dedupByIno() {
        val s = DmabufParser.parse(text)
        assertEquals(35, s.rawRows, "fixture 应有 35 行数据")
        assertEquals(34, s.entries.size, "按 ino 去重后应是 34 条")
        assertEquals(1, s.duplicateRows)
        assertEquals(27404L * 1024, s.totalBytes, "去重后总量应为 27404 kB")
    }

    @Test
    fun naiveSumWouldOvercount() {
        // 证明去重不是可选项：直接按行求和会多算
        val s = DmabufParser.parse(text)
        val naive = s.entries.sumOf { it.sizeBytes } + 40960 // 那条重复的 40 kB
        assertTrue(naive > s.totalBytes, "不去重会多算 40 kB")
    }

    @Test
    fun groupsSumToTotal() {
        val s = DmabufParser.parse(text)
        assertEquals(s.totalBytes, s.groups.sumOf { it.bytes }, "分组求和必须等于总计，否则丢条目")
        assertTrue(s.groups.isNotEmpty())
        // 降序排列
        assertEquals(s.groups.map { it.bytes }, s.groups.map { it.bytes }.sortedDescending())
    }

    @Test
    fun numericBufNameGetsTypePrefix() {
        // 无 xcomponent-t-* 时：纯数字 buf_name 必须带类型前缀
        val row = "tencent.compose  23806  51  13312000  44623076  1329  allocator_host  23806  " +
            "mm_heap_helpers  1  0  xcomponent  NULL  xcomponent  23806  0  0"
        val s = DmabufParser.parse("Process pid fd size_bytes ino exp_pid exp_task_comm buf_name exp_name " +
            "can_reclaim is_reclaim buf_type reclaim_info leak_type ahost-cpid ahost-iova ahost-usage\n$row")
        assertEquals(1, s.entries.size)
        assertEquals("xcomponent:23806", s.groups.first().label)
    }

    @Test
    fun xcomponentSurfaceTagPreferredOverNumericBufName() {
        // 真机行：buf_name=pid，leak_type=xcomponent-t-Compose_N —— 分组必须用后者才能归因阶梯泄漏
        val row = "tencent.compose  4418  49  13312000  3112696  1332  allocator_host  4418  " +
            "mm_heap_helpers  1  0  xcomponent  NULL  xcomponent-t-Compose_1  4418  0  0"
        val s = DmabufParser.parse("Process pid fd size_bytes ino exp_pid exp_task_comm buf_name exp_name " +
            "can_reclaim is_reclaim buf_type reclaim_info leak_type ahost-cpid ahost-iova ahost-usage\n$row")
        assertEquals(1, s.entries.size)
        assertEquals("xcomponent-t-Compose_1", s.groups.first().label)
    }

    @Test
    fun bufNameWithSpaceDoesNotShiftTailColumns() {
        // buf_name 含空格时会多出 token，尾部字段必须仍能正确定位
        val row = "app  100  7  4096  123  456  worker  my buf name with spaces  exp  " +
            "1  0  pixelmap  NULL  pixelmap  100  0  0"
        val e = DmabufParser.parseLine(row)!!
        assertEquals("my buf name with spaces", e.bufName)
        assertEquals("pixelmap", e.bufType)
        assertTrue(e.canReclaim)
        assertEquals(4096L, e.sizeBytes)
        assertEquals("pixelmap", e.leakType)
    }

    @Test
    fun normalizeLabelBranches() {
        // 尺寸保留（排查关键特征），但去掉 streamsize/mimetype 噪声
        assertEquals(
            "srcImageSize-1024x64",
            DmabufParser.normalizeLabel(
                "srcImageSize-1024x64-pixelMapSize-1024x64-streamsize-141592-mimetype-png",
            ),
        )
        // 图标 id 去掉序号段
        assertEquals(
            "id:[Dropdown]NotificationIconView",
            DmabufParser.normalizeLabel("id:[Dropdown]NotificationIconView___100_20020018_pkg_bigIcon"),
        )
        assertEquals("SaveSnapshot", DmabufParser.normalizeLabel("SaveSnapshot_321"))
        assertEquals("(unnamed)", DmabufParser.normalizeLabel("NULL"))
        assertEquals("(unnamed)", DmabufParser.normalizeLabel("  "))
        // 包名类标签原样保留
        assertEquals(
            "Adaptive_foreground_com.tencent.compose",
            DmabufParser.normalizeLabel("Adaptive_foreground_com.tencent.compose"),
        )
    }

    @Test
    fun garbageLinesSkippedNotCrashing() {
        val s = DmabufParser.parse(
            "Process pid fd\n\nnot a valid row at all\n" +
                "app  100  7  4096  123  456  worker  buf  exp  1  0  pixelmap  NULL  pixelmap  100  0  0",
        )
        assertEquals(1, s.entries.size, "坏行应跳过并计数，不能让整个解析失败")
        assertTrue(s.skipped >= 1)
    }
}
