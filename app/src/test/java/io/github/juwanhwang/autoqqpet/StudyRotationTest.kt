package io.github.juwanhwang.autoqqpet

import io.github.juwanhwang.autoqqpet.optimizer.DayPlanner
import io.github.juwanhwang.autoqqpet.optimizer.Kind
import io.github.juwanhwang.autoqqpet.optimizer.Snapshot
import io.github.juwanhwang.autoqqpet.optimizer.StudyRotation
import io.github.juwanhwang.autoqqpet.pet.AdventureInfo
import io.github.juwanhwang.autoqqpet.pet.Attr
import io.github.juwanhwang.autoqqpet.pet.CourseInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 均衡学习轮转（2026-10-20）：目标属性选「均衡」时学习按力量→智力→魅力轮着来；
 * 轮到的属性没课就顺延到下一个有课的；派出后指针跳到实际派出属性的下一个。
 */
class StudyRotationTest {

    @Test fun orderCyclesThroughThreeAttrs() {
        assertEquals(listOf(Attr.STRENGTH, Attr.INTEL, Attr.CHARM), StudyRotation.orderFrom(0))
        assertEquals(listOf(Attr.INTEL, Attr.CHARM, Attr.STRENGTH), StudyRotation.orderFrom(1))
        assertEquals(listOf(Attr.CHARM, Attr.STRENGTH, Attr.INTEL), StudyRotation.orderFrom(2))
        assertEquals(StudyRotation.orderFrom(0), StudyRotation.orderFrom(3))
        assertEquals(Attr.CHARM, StudyRotation.attrAt(-1))
    }

    @Test fun resolvePicksFirstAvailableInRotationOrder() {
        // 指针在智力：智力有课就用智力
        assertEquals(Attr.INTEL, StudyRotation.resolve(1, setOf(Attr.INTEL, Attr.STRENGTH)))
        // 智力没课 → 顺延到魅力，再没有才回力量
        assertEquals(Attr.CHARM, StudyRotation.resolve(1, setOf(Attr.CHARM, Attr.STRENGTH)))
        assertEquals(Attr.STRENGTH, StudyRotation.resolve(1, setOf(Attr.STRENGTH)))
        // 三个都没课 → 用轮到的那个（上层自有回退，如随机属性课）
        assertEquals(Attr.INTEL, StudyRotation.resolve(1, emptySet()))
        assertEquals(Attr.INTEL, StudyRotation.resolve(1, setOf(Attr.RANDOM)))
    }

    @Test fun indexAfterAdvancesPastUsedAttr() {
        assertEquals(1, StudyRotation.indexAfter(0, Attr.STRENGTH))
        assertEquals(2, StudyRotation.indexAfter(0, Attr.INTEL))   // 顺延派了智力，指针直接跳过智力
        assertEquals(0, StudyRotation.indexAfter(2, Attr.CHARM))
        assertEquals(2, StudyRotation.indexAfter(2, Attr.RANDOM))  // 随机属性课不算趟，指针不动
        assertEquals(1, StudyRotation.indexAfter(1, Attr.NONE))
    }

    private fun course(name: String, sub: Long, attr: Attr) =
        CourseInfo(name, sub, attr, 30, 100, 0, 0, 30, 30 * 60L, true)

    private val walk = AdventureInfo("附近走走", 0, 0, false, Attr.NONE, 0, 0, 0, 0, 1, 45, true)

    private fun snap(courses: List<CourseInfo>) =
        Snapshot(2000, 80, 80, 0, courses, emptyList(), 10, 10, adventures = listOf(walk))

    @Test fun plannerFollowsRotationOrderNotTargetAttr() {
        val courses = listOf(course("力量课", 11, Attr.STRENGTH), course("智力课", 12, Attr.INTEL))
        // 均衡：指针在智力 → 顺序 [智力, 魅力, 力量]，同等收益下选智力课（不是设置里的目标属性力量）
        val balanced = DayPlanner(Attr.STRENGTH, studyAttrOrder = StudyRotation.orderFrom(1))
        val d = balanced.decide(snap(courses))
        assertEquals(Kind.STUDY, d.kind)
        assertEquals(12L, d.sub)
        // 不传顺序（固定目标属性）→ 还是力量课，旧行为不变
        val fixed = DayPlanner(Attr.STRENGTH)
        assertEquals(11L, fixed.decide(snap(courses)).sub)
    }

    @Test fun plannerSkipsAttrWithoutCourse() {
        val courses = listOf(course("力量课", 11, Attr.STRENGTH), course("魅力课", 13, Attr.CHARM))
        // 指针在智力但智力没课 → 顺延魅力
        val balanced = DayPlanner(Attr.STRENGTH, studyAttrOrder = StudyRotation.orderFrom(1))
        val d = balanced.decide(snap(courses))
        assertEquals(Kind.STUDY, d.kind)
        assertEquals(13L, d.sub)
    }
}
