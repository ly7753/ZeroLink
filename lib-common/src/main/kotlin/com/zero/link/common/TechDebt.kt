package com.zero.link.common

/**
 * 显式技术债务标记
 * @param reason 债务原因
 * @param ttl 过期时间或重构工单号
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class TechDebt(val reason: String, val ttl: String)
