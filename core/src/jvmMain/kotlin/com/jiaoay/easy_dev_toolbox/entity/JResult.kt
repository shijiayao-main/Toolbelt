package com.jiaoay.easy_dev_toolbox.entity

/**
 */
sealed class JResult<out T> {

    data class Success<T>(val data: T) : JResult<T>()

    /** 现网不可达分支（审计核实：shelltools 链只产 Success/Failed/null，
     *  全仓零构造点）——保留作 [com.jiaoay.easy_dev_toolbox.widget.StatusView] 的规格分支。 */
    class Loading(): JResult<Nothing>()

    /** 现网不可达分支（同上）——保留作 StatusView 空态规格分支。 */
    class Empty(): JResult<Nothing>()

    data class Failed(val code: JErrorCode, val msg: String? = null) : JResult<Nothing>()

    companion object {
        fun <T> success(data: T): Success<T> {
            return Success(data)
        }
    }
}
