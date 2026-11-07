package com.revxzxcx.remotewebapp.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四层开关的合成判定与诊断文案（R056）。
 *
 * ## 为什么这一组用例特别密
 * 四层里任意一层关掉，用户看到的现象**完全一样**：什么都不响。所以设置页那行小字
 * 是他唯一能知道「该去动哪一层」的地方。文案排错序 = 引导他去改一个没坏的东西。
 *
 * 优先级从「根本收不到」往「能用但被裁」排，这里的用例就是照那个顺序钉的。
 */
class NoticeStatusTest {

    private val allGood = NoticeStatus()

    @Test
    fun `四层全通时两类都能收到`() {
        assertTrue(allGood.doneUsable)
        assertTrue(allGood.askUsable)
        assertTrue(allGood.anyUsable)
    }

    @Test
    fun `系统总开关关掉时两类都不可用`() {
        val status = allGood.copy(systemEnabled = false)
        assertFalse(status.doneUsable)
        assertFalse(status.askUsable)
        assertFalse(status.anyUsable)
    }

    @Test
    fun `没有运行时权限时两类都不可用`() {
        val status = allGood.copy(permissionGranted = false)
        assertFalse(status.doneUsable)
        assertFalse(status.askUsable)
    }

    @Test
    fun `只关掉任务结束频道时 审批仍然可用`() {
        val status = allGood.copy(doneChannelEnabled = false)
        assertFalse(status.doneUsable)
        assertTrue("两个频道互不影响，一个关掉不该让另一个也失效", status.askUsable)
        assertTrue(status.anyUsable)
    }

    @Test
    fun `只关掉审批频道时 任务结束仍然可用`() {
        val status = allGood.copy(askChannelEnabled = false)
        assertTrue(status.doneUsable)
        assertFalse(status.askUsable)
    }

    @Test
    fun `只关掉 App 内任务结束开关时仍可用审批`() {
        val status = allGood.copy(doneEnabled = false)
        assertFalse(status.doneUsable)
        assertTrue(status.askUsable)
    }

    @Test
    fun `两类全关时 anyUsable 为假`() {
        val status = allGood.copy(doneEnabled = false, askEnabled = false)
        assertFalse(status.anyUsable)
    }

    @Test
    fun `全通时文案说会提醒`() {
        assertEquals("任务跑完、需要你确认时提醒", noticeStatusDetail(allGood))
    }

    @Test
    fun `从没请求过权限时 文案让用户点这里开启`() {
        val status = allGood.copy(permissionGranted = false, requested = false)
        assertEquals("还没授权，点这里开启", noticeStatusDetail(status))
    }

    @Test
    fun `请求过权限被拒后 文案让用户去系统设置`() {
        val status = allGood.copy(permissionGranted = false, requested = true)
        assertEquals("已拒绝，去系统设置里打开", noticeStatusDetail(status))
    }

    @Test
    fun `没权限的文案优先于频道被关的文案`() {
        val status = allGood.copy(permissionGranted = false, requested = true, askChannelEnabled = false)
        assertEquals(
            "先解决权限，否则用户去开频道也收不到",
            "已拒绝，去系统设置里打开",
            noticeStatusDetail(status),
        )
    }

    @Test
    fun `系统总开关关掉时 文案指出是系统里关的`() {
        val status = allGood.copy(systemEnabled = false)
        assertEquals("系统里被关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `两个频道都被关时 文案一次说清两类`() {
        val status = allGood.copy(askChannelEnabled = false, doneChannelEnabled = false)
        assertEquals("两类通知都被关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `只关掉审批频道时 文案点名需要确认`() {
        val status = allGood.copy(askChannelEnabled = false)
        assertEquals("「需要确认」被关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `只关掉任务结束频道时 文案点名任务结束`() {
        val status = allGood.copy(doneChannelEnabled = false)
        assertEquals("「任务结束」被关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `App 内两个开关都关时 文案说两类都被你关掉了`() {
        val status = allGood.copy(askEnabled = false, doneEnabled = false)
        assertEquals("两类都被你关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `App 内关掉审批时 文案要提醒任务会一直等`() {
        val status = allGood.copy(askEnabled = false)
        assertEquals("「需要确认」被你关掉了，任务会一直等你", noticeStatusDetail(status))
    }

    @Test
    fun `App 内关掉任务结束时 文案只说要关掉了`() {
        val status = allGood.copy(doneEnabled = false)
        assertEquals("「任务结束」被你关掉了", noticeStatusDetail(status))
    }

    @Test
    fun `系统频道被关优先于 App 内开关被关`() {
        val status = allGood.copy(askChannelEnabled = false, askEnabled = false)
        assertEquals(
            "系统频道关掉是我们发不出去，更根本；App 内开关是我们主动不发",
            "「需要确认」被关掉了",
            noticeStatusDetail(status),
        )
    }

    @Test
    fun `从没请求过时该弹权限框`() {
        assertTrue(shouldRequestPermission(allGood.copy(permissionGranted = false, requested = false)))
    }

    @Test
    fun `请求过之后不该再弹权限框`() {
        assertFalse(
            "永久拒绝后再调 requestPermissions 系统直接回调拒绝、不弹界面，用户以为按钮坏了",
            shouldRequestPermission(allGood.copy(permissionGranted = false, requested = true)),
        )
    }

    @Test
    fun `已经有权限时不弹权限框`() {
        assertFalse(shouldRequestPermission(allGood))
    }

    @Test
    fun `请求过但没权限时该引导去系统设置`() {
        assertTrue(shouldGuideToSystemSettings(allGood.copy(permissionGranted = false, requested = true)))
    }

    @Test
    fun `从没请求过时不引导去系统设置 而是先弹框`() {
        assertFalse(
            "还没问过就把用户赶去系统设置，等于跳过了系统本来能帮他弹的那一步",
            shouldGuideToSystemSettings(allGood.copy(permissionGranted = false, requested = false)),
        )
    }

    @Test
    fun `有权限时不引导去系统设置`() {
        assertFalse(shouldGuideToSystemSettings(allGood))
    }

    @Test
    fun `弹权限框与去系统设置互斥`() {
        for (granted in listOf(true, false)) {
            for (requested in listOf(true, false)) {
                val status = NoticeStatus(permissionGranted = granted, requested = requested)
                assertFalse(
                    "两条路同时成立会让设置页出现两个互相矛盾的按钮 granted=$granted requested=$requested",
                    shouldRequestPermission(status) && shouldGuideToSystemSettings(status),
                )
            }
        }
    }
}

/**
 * 一加 / ColorOS 的保活引导清单（R056）。
 *
 * 断的是**完整性**：这四项是 dontkillmyapp 与一加社区实测里缺一不可的组合，
 * 少一项用户就还是会收不到通知。所以用「key 集合必须恰好是这四个」钉住，
 * 而不是断具体文案（文案会随 ROM 版本改）。
 */
class OemGuideStepsTest {

    @Test
    fun `四项保活设置一项都不能少`() {
        assertEquals(
            "少一项用户就还是会被杀后台：${OemGuideSteps.map { it.key }}",
            setOf("battery", "advanced", "autostart", "lock"),
            OemGuideSteps.map { it.key }.toSet(),
        )
    }

    @Test
    fun `key 不重复`() {
        assertEquals(OemGuideSteps.size, OemGuideSteps.map { it.key }.toSet().size)
    }

    @Test
    fun `每项都有标题和具体路径`() {
        for (step in OemGuideSteps) {
            assertTrue("${step.key} 缺标题", step.title.isNotBlank())
            assertTrue("${step.key} 缺路径，用户照着做不了", step.detail.isNotBlank())
        }
    }

    @Test
    fun `路径里要有可照着点的层级分隔`() {
        for (step in OemGuideSteps) {
            assertTrue(
                "${step.key} 的路径看不出从哪进：${step.detail}",
                step.detail.contains("›"),
            )
        }
    }

    @Test
    fun `多任务锁定那项必须说明不锁的后果`() {
        val lock = OemGuideSteps.first { it.key == "lock" }
        assertTrue(
            "不说明「会被随机改回去」，用户会以为开完就一劳永逸",
            lock.detail.contains("改回"),
        )
    }
}

/**
 * 「这件事该去哪儿解决」——诊断卡的按钮去哪儿（R056 第 5 轮审查补）。
 *
 * 存在的理由：四层里有一层是本页自己的开关。写死「去系统设置」的话，
 * 用户看到「两类都被你关掉了」点按钮过去，系统里一切正常，只能原路返回。
 */
class NoticeFixLocationTest {

    @Test
    fun `全是好的时候不需要修 指向本页开关也不会误导`() {
        assertEquals(NoticeFix.AppSwitch, noticeFixLocation(NoticeStatus()))
    }

    @Test
    fun `系统总开关关了 只能去系统设置`() {
        assertEquals(
            NoticeFix.SystemSettings,
            noticeFixLocation(NoticeStatus(systemEnabled = false)),
        )
    }

    @Test
    fun `还没问过权限 应当在这里弹框`() {
        assertEquals(
            NoticeFix.Permission,
            noticeFixLocation(NoticeStatus(permissionGranted = false, requested = false)),
        )
    }

    @Test
    fun `问过了还没给 只能去系统设置 再弹框不会有反应`() {
        assertEquals(
            NoticeFix.SystemSettings,
            noticeFixLocation(NoticeStatus(permissionGranted = false, requested = true)),
        )
    }

    @Test
    fun `系统频道被关 只能去系统设置`() {
        assertEquals(
            NoticeFix.SystemSettings,
            noticeFixLocation(NoticeStatus(doneChannelEnabled = false)),
        )
        assertEquals(
            NoticeFix.SystemSettings,
            noticeFixLocation(NoticeStatus(askChannelEnabled = false)),
        )
    }

    @Test
    fun `App 内开关被关 解决办法就在本页 不能把用户送去系统设置`() {
        // 这条是本类存在的理由：四层里只有这一层在设置页自己身上。
        assertEquals(NoticeFix.AppSwitch, noticeFixLocation(NoticeStatus(doneEnabled = false)))
        assertEquals(NoticeFix.AppSwitch, noticeFixLocation(NoticeStatus(askEnabled = false)))
        assertEquals(
            NoticeFix.AppSwitch,
            noticeFixLocation(NoticeStatus(doneEnabled = false, askEnabled = false)),
        )
    }

    @Test
    fun `权限与会话级开关同时出问题时 先解决权限`() {
        // 与 noticeStatusDetail 的优先级一致：说的和让你做的必须是同一件事。
        assertEquals(
            NoticeFix.Permission,
            noticeFixLocation(
                NoticeStatus(permissionGranted = false, requested = false, doneEnabled = false),
            ),
        )
    }
}
