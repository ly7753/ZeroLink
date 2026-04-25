package com.zero.link.common.result

/**
 * 强类型错误边界基石
 * 隔离异常，强制在使用侧进行失败路径处理
 */
sealed interface AppResult<out T, out E : AppError> {
    
    data class Success<out T>(val data: T) : AppResult<T, Nothing>
    
    data class Failure<out E : AppError>(val error: E) : AppResult<Nothing, E>

    /**
     * 映射成功的 Payload
     */
    fun <R> map(transform: (T) -> R): AppResult<R, E> {
        return when (this) {
            is Success -> Success(transform(data))
            is Failure -> this
        }
    }

    /**
     * 错误恢复或者处理
     */
    fun <R : AppError> mapError(transform: (E) -> R): AppResult<T, R> {
        return when (this) {
            is Success -> this
            is Failure -> Failure(transform(error))
        }
    }
}

/**
 * 伴生扩展：快捷消费结果
 */
inline fun <T, E : AppError> AppResult<T, E>.onSuccess(action: (T) -> Unit): AppResult<T, E> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T, E : AppError> AppResult<T, E>.onFailure(action: (E) -> Unit): AppResult<T, E> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * 解包或返回默认值
 */
inline fun <T, E : AppError> AppResult<T, E>.getOrElse(onFailure: (E) -> T): T {
    return when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> onFailure(error)
    }
}
