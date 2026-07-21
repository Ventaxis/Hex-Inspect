package com.ventaxiscorp.hexinspect

sealed class Result<out T> {
    data object Loading : Result<Nothing>()
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable, val message: String = exception.message ?: "Unknown error") : Result<Nothing>()
    
    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Loading -> Loading
        is Success -> Success(transform(data))
        is Error -> Error(exception, message)
    }
}

// Alias for backwards compatibility
typealias Res<T> = Result<T>
