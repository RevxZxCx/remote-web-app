package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.credentials.CredentialBook
import com.revxzxcx.remotewebapp.core.credentials.DeviceCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B001 的常量层与形状契约测试（用户 m03969 第 1 条：切换远程的展开卡片不是圆角的）。
 *
 * 这一层只锁「形状到底是什么」，不渲染任何东西。
 * 为什么需要它：`RoundedCornerShape(0.dp)` 与「四面 16dp」在**类型上是同一个东西**，
 * 编译器不会拦，只有把四角的具体取值写进断言才能防回归。
 */
class DeviceBarShapeTest {

    @Test
    fun 设备栏只圆下两角_上两角必须是直角() {
        // 上两角直角是**故意的**，不是漏改：设备栏上沿与 TopAppBar 直接相接，
        // 圆上去会露出顶栏底下的背景，看着像裂了一道缝。
        // 注意：`RoundedCornerShape.topStart` 返回的是 `CornerSize` 而不是 `Dp`，
        // 直接和 `0.dp` 比会得到 `expected:<0.dp> but was:<CornerSize(size = 0.0.dp)>`
        // —— 值是对的，类型不同。B003 里已经踩过一次同样的坑。
        val shape = DeviceBarShape
        assertEquals("左上角必须保持直角", CornerSize(0.dp), shape.topStart)
        assertEquals("右上角必须保持直角", CornerSize(0.dp), shape.topEnd)
    }

    @Test
    fun 设备栏下两角必须圆_否则这条缺陷没修好() {
        val shape = DeviceBarShape
        assertNotEquals("左下角不能是直角", CornerSize(0.dp), shape.bottomStart)
        assertNotEquals("右下角不能是直角", CornerSize(0.dp), shape.bottomEnd)
        assertEquals("左右两角必须对称", shape.bottomStart, shape.bottomEnd)
        assertEquals("下两角必须是 16dp", CornerSize(16.dp), shape.bottomStart)
    }

    @Test
    fun 设备栏圆角取16dp_与首页会话行同一档() {
        // 会话行用 16dp（HomeScreen 的 rowShape）。两者在屏幕上上下相邻，
        // 用同一档圆角才是同一套视觉语言。
        assertEquals(16.dp, DeviceBarCornerRadius)
        assertEquals(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp), DeviceBarShape)
    }

    @Test
    fun 设备栏形状不能写成四面圆角() {
        // 反例断言：四面 16dp 是很容易「顺手」写出来的错法（大多数容器的惯例就是四面），
        // 但它会让上沿出现缺口。这条把「错法」显式写死，防止将来有人改回四面。
        val fourSided = RoundedCornerShape(16.dp)
        assertNotEquals("四面 16dp 不是设备栏要的形状", fourSided, DeviceBarShape)
    }

    @Test
    fun 设备栏形状也不能留直角() {
        // 另一个反例：B001 之前就是这么写的（RoundedCornerShape(0.dp)）。
        assertNotEquals("通条直角正是这条缺陷本身", RoundedCornerShape(0.dp), DeviceBarShape)
    }
}
