package com.xxt.study.domain

/** 登录态信息，用于在首页确认"登的是谁的号" */
data class Session(
    val account: String,
    val school: String,
    val fid: String,
) {
    val known: Boolean get() = account.isNotBlank() || school.isNotBlank()
}
