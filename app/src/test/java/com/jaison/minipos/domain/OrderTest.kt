package com.jaison.minipos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderTest {
    private val burrito = MenuItem("burrito", "Bean Burrito", Money(349))
    private val drink = MenuItem("drink", "Fountain Drink", Money(199))

    @Test
    fun addingSameItemTwiceIncrementsQuantity() {
        val order = Order().add(burrito).add(burrito)
        assertEquals(1, order.lines.size)
        assertEquals(2, order.lines.single().quantity)
    }

    @Test
    fun removeOneDecrementsThenRemovesLine() {
        val order = Order().add(burrito).add(burrito).add(drink)
        val once = order.removeOne(burrito)
        assertEquals(1, once.lines.first { it.item == burrito }.quantity)
        val twice = once.removeOne(burrito)
        assertTrue(twice.lines.none { it.item == burrito })
        assertEquals(1, twice.itemCount)
    }

    @Test
    fun totalsUseCentsAndRoundTaxHalfUp() {
        // 2 x 3.49 + 1.99 = 8.97; 10% tax = 0.897, which rounds to 0.90
        val order = Order(taxRateBps = 1000).add(burrito).add(burrito).add(drink)
        assertEquals(Money(897), order.subtotal)
        assertEquals(Money(90), order.tax)
        assertEquals(Money(987), order.total)
    }

    @Test
    fun moneyFormatsWithTwoDecimals() {
        assertEquals("$9.05", Money(905).format())
        assertEquals("$0.00", Money.ZERO.format())
        assertEquals("$120.40", Money(12040).format())
    }
}
