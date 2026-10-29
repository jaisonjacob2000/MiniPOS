package com.jaison.minipos.domain

/** Money stored as whole cents so totals never drift the way floating point does. */
@JvmInline
value class Money(val cents: Long) {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun times(quantity: Int) = Money(cents * quantity)

    /** "$12.05" */
    fun format(): String = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')

    companion object {
        val ZERO = Money(0)
    }
}

data class MenuItem(val id: String, val name: String, val price: Money)

data class OrderLine(val item: MenuItem, val quantity: Int) {
    val lineTotal: Money get() = item.price * quantity
}

/**
 * An order is immutable: every change returns a new Order. That keeps the UI state
 * predictable and makes the logic easy to unit test.
 *
 * Tax is in basis points (1000 = 10.00%) and rounded half up to the nearest cent.
 */
data class Order(
    val lines: List<OrderLine> = emptyList(),
    val taxRateBps: Int = 1000,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val itemCount: Int get() = lines.sumOf { it.quantity }
    val subtotal: Money get() = Money(lines.sumOf { it.lineTotal.cents })
    val tax: Money get() = Money((subtotal.cents * taxRateBps + 5_000) / 10_000)
    val total: Money get() = subtotal + tax

    fun add(item: MenuItem): Order {
        val existing = lines.indexOfFirst { it.item.id == item.id }
        val newLines = if (existing >= 0) {
            lines.mapIndexed { i, line -> if (i == existing) line.copy(quantity = line.quantity + 1) else line }
        } else {
            lines + OrderLine(item, 1)
        }
        return copy(lines = newLines)
    }

    fun removeOne(item: MenuItem): Order {
        val newLines = lines.mapNotNull { line ->
            when {
                line.item.id != item.id -> line
                line.quantity > 1 -> line.copy(quantity = line.quantity - 1)
                else -> null
            }
        }
        return copy(lines = newLines)
    }
}

object SampleMenu {
    val items = listOf(
        MenuItem("burger", "Classic Burger", Money(649)),
        MenuItem("tenders", "Chicken Tenders (3pc)", Money(599)),
        MenuItem("burrito", "Bean Burrito", Money(349)),
        MenuItem("tacos", "Crunchy Tacos (2pc)", Money(399)),
        MenuItem("pizza", "Pepperoni Slice", Money(449)),
        MenuItem("fries", "Fries", Money(299)),
        MenuItem("drink", "Fountain Drink", Money(199)),
        MenuItem("cookie", "Cookie", Money(149)),
    )
}
