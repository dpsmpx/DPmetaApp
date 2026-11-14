package com.dpsmpx.dpai.ui

/** Оформление обычного уведомления оболочкой; без Android и действий контура (D-176). */
data class NoticePresentation(val channel: String? = null, val silent: Boolean = false, val privacy: Boolean = true)
