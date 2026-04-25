package com.zero.link.infrastructure

import android.content.Context
import com.zero.link.domain.shizuku.ShizukuRepository
import com.zero.link.infrastructure.shizuku.ShizukuInfrastructureRepository

object ShizukuInfraProvider {
    var appContext: Context? = null
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 创建 ShizukuRepository 实例。
     * 具体实现类为 internal，调用方仅依赖领域层抽象接口。
     */
    fun createRepository(): ShizukuRepository = ShizukuInfrastructureRepository()
}
