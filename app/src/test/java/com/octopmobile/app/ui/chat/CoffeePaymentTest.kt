package com.octopmobile.app.ui.chat

import com.octopmobile.app.data.model.Part
import com.octopmobile.app.data.model.PartType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoffeePaymentTest {

    private val createOrderOutput = """
        {
          "code": 0,
          "success": true,
          "data": {
            "orderId": "TEST-ORDER-1",
            "payOrderUrl": "weixin://wxpay/bizpayurl?pr=test",
            "payOrderQrCodeUrl": "https://open.lkcoffee.com/transfer/qrcode?token=test",
            "discountPrice": 10.9,
            "needPay": true
          }
        }
    """.trimIndent()

    @Test
    fun `extracts an actionable wechat checkout from createOrder`() {
        val payment = requireNotNull(parseCoffeePayment(createOrderOutput))

        assertEquals("TEST-ORDER-1", payment.orderId)
        assertEquals("10.9", payment.amount)
        assertEquals("weixin://wxpay/bizpayurl?pr=test", payment.wechatPayUrl)
        assertEquals(
            "https://open.lkcoffee.com/transfer/qrcode?token=test",
            payment.qrCodeUrl,
        )
    }

    @Test
    fun `only allows the expected wechat payment and Luckin QR targets`() {
        assertTrue(isAllowedWechatPayUrl("weixin://wxpay/bizpayurl?pr=test"))
        assertTrue(
            isAllowedCoffeeQrUrl(
                "https://open.lkcoffee.com/transfer/qrcode?token=test",
            ),
        )
        assertFalse(isAllowedWechatPayUrl("weixin://other/bizpayurl?pr=test"))
        assertFalse(isAllowedWechatPayUrl("https://wxpay.example/pay"))
        assertFalse(isAllowedCoffeeQrUrl("https://example.com/transfer/qrcode?token=test"))
        assertFalse(isAllowedCoffeeQrUrl("http://open.lkcoffee.com/transfer/qrcode?token=test"))
    }

    @Test
    fun `hides checkout after the same turn contains a real pickup code`() {
        val parts = listOf(
            Part(
                type = PartType.TOOL_RESULT,
                name = "my-coffee_createOrder",
                output = createOrderOutput,
            ),
            Part(
                type = PartType.TOOL_RESULT,
                name = "my-coffee_queryOrderDetailInfo",
                output = """{"success":true,"data":{"takeMealCodeInfo":{"code":"972"}}}""",
            ),
        )

        assertNull(coffeePaymentFrom(parts))
    }

    @Test
    fun `does not expose a checkout when no payment is needed`() {
        val paid = createOrderOutput.replace("\"needPay\": true", "\"needPay\": false")

        assertNull(parseCoffeePayment(paid))
    }
}
