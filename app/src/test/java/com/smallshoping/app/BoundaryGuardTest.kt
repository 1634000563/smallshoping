package com.smallshoping.app

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture Guard 的代码级自动检查（Task 003 边界骨架）。
 *
 * 把 AGENTS.md / ARCHITECTURE.md 的分层约束固化为测试：
 * - Domain 是纯业务层，不依赖 Android/AI/UI/Data/Device
 * - AI 与 UI 都不得直接访问 DAO、Room、数据实体
 * 后续 Task 新增代码时本测试自动拦截越界依赖。
 */
class BoundaryGuardTest {

    private val classes = ClassFileImporter().importPackages("com.smallshoping.app")

    @Test
    fun `domain 不得依赖 Android、AI、UI、Data 或 Device`() {
        noClasses().that().resideInAPackage("com.smallshoping.app.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "android..", "androidx..",
                "com.smallshoping.app.ai..",
                "com.smallshoping.app.feature..",
                "com.smallshoping.app.data..",
                "com.smallshoping.app.device.."
            )
            .check(classes)
    }

    @Test
    fun `AI 不得访问 DAO、Room 或数据实体`() {
        noClasses().that().resideInAPackage("..ai..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..data.dao..", "..data.db..", "..data.entity..", "androidx.room.."
            )
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `UI（feature）不得访问 DAO、Room 或数据实体`() {
        noClasses().that().resideInAPackage("..feature..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..data.dao..", "..data.db..", "..data.entity..", "androidx.room.."
            )
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `哨兵：导入必须包含已知类，防止 Guard 空转`() {
        assertTrue(classes.contain("com.smallshoping.app.feature.home.MainActivity"))
        assertTrue(classes.contain("com.smallshoping.app.domain.UseCase"))
    }
}
