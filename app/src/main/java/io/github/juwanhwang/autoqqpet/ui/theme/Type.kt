package io.github.juwanhwang.autoqqpet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字体层级。不外挂字体文件：系统无衬线（中文落到系统黑体）+ 等宽。
 * 数字一律用等宽数字（tnum），倒计时、数值跳动时不左右抖。
 * 全部用 sp：跟随系统字体缩放；容器只给最小高度，不给死高度。
 */
@Immutable
object AevumType {
    private const val TNUM = "tnum"

    /** 仪表盘巨型数字（倒计时）。 */
    val numeralXL = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM,
    )

    /** 数值磁贴里的数字。 */
    val numeral = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM,
    )

    /** 页面大标题（宠物此刻在做什么）。 */
    val hero = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em,
    )

    val headline = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.005).em,
    )

    val title = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp,
    )

    val bodyStrong = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp,
    )

    val body = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 23.sp,
    )

    val caption = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp,
    )

    val label = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM,
    )

    /** 分组小标题 / 拉丁大写小字：加字距。 */
    val micro = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.08.em, fontFeatureSettings = TNUM,
    )

    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    )
}
