pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内兜底镜像（google()/mavenCentral() 不可达时生效）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 国内兜底镜像
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "smallshoping"
include(":app")
