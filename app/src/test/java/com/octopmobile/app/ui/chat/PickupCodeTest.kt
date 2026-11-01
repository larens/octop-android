package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickupCodeTest {

    @Test
    fun `extracts pickup code from nested json`() {
        val output = """{"ok":true,"data":{"order":{"pickup_code":"A128"}}}"""

        assertEquals("A128", extractPickupCode(output))
    }

    @Test
    fun `extracts pickup code from escaped json string`() {
        val output = "\"{\\\"success\\\":true,\\\"data\\\":{\\\"mealCode\\\":\\\"739 204\\\"}}\""

        assertEquals("739204", extractPickupCode(output))
    }

    @Test
    fun `extracts chinese pickup code from readable text`() {
        val output = "订单已提交，门店制作中。取餐码：K-0836，请在吧台取餐。"

        assertEquals("K-0836", extractPickupCode(output))
    }

    @Test
    fun `does not mistake an order number for a pickup code`() {
        val output = """{"ok":true,"data":{"order_no":"123456","status":"paid"}}"""

        assertNull(extractPickupCode(output))
    }

    @Test
    fun `does not show a pickup code from a failed order`() {
        val output = """{"success":false,"data":{"status":"failed","pickup_code":"A128"}}"""

        assertNull(extractPickupCode(output))
    }

    @Test
    fun `does not show a pickup code from readable failure text`() {
        val output = "下单失败，订单已取消。原取餐码：A128"

        assertNull(extractPickupCode(output))
    }

    @Test
    fun `ignores errors and missing pickup codes`() {
        val parts = listOf(
            Part(
                type = PartType.TOOL_RESULT,
                name = "luckin_order",
                output = """{"pickup_code":"A128"}""",
                errorCode = "failed",
            ),
            Part(
                type = PartType.TOOL_RESULT,
                name = "luckin_order",
                output = """{"status":"failed","message":"门店库存不足"}""",
            ),
        )

        assertNull(pickupCodeFrom(parts))
    }
}
