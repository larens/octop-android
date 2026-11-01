package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.TodayCardEntity

internal fun TodayCardEntity.actionPromptForChat(): String {
    if (kind != "health" || actionPrompt.contains("卡片记录：")) return actionPrompt
    val record = summary.substringBefore("【健康可视化】").trim()
    if (record.isEmpty()) return actionPrompt
    return "请结合健康卡片「$title」和已加载的个人健康技能给我建议。\n" +
        "卡片记录：\n$record\n\n" +
        actionPrompt
}
