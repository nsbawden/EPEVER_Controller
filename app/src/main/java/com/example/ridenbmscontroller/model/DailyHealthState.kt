package com.example.ridenbmscontroller.model

data class DailyHealthState(
    val maxChargeAmpsToday: Double? = null,
    val minChargeAmpsToday: Double? = null,
    val mpptFault: Boolean = false
)
