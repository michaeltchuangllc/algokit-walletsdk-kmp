package com.michaeltchuang.seedvault.service.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignApprovalCoordinatorTest {
    private var now = 0L
    private val coordinator = SignApprovalCoordinator(clock = { now }, grantMillis = 1_000)
    private val operation = SignOperation.bytes(seedId = 1, data = byteArrayOf(1, 2, 3))

    private fun begin(
        caller: String = "client",
        operation: SignOperation = this.operation,
    ) = coordinator.begin(SignApprovalRequest(caller, "Client", operation, "Sign 3 bytes of data"))

    @Test
    fun approvedOperationCanBeUsedOnce() {
        coordinator.resolve(begin(), approved = true)

        assertTrue(coordinator.consume(setOf("client"), operation))
        assertFalse(coordinator.consume(setOf("client"), operation))
    }

    @Test
    fun deniedOperationCannotBeUsed() {
        coordinator.resolve(begin(), approved = false)

        assertFalse(coordinator.consume(setOf("client"), operation))
    }

    @Test
    fun nothingApprovedMeansNothingToUse() {
        assertFalse(coordinator.consume(setOf("client"), operation))
    }

    @Test
    fun approvalIsForTheAppThatGotIt() {
        coordinator.resolve(begin(caller = "client"), approved = true)

        assertFalse(coordinator.consume(setOf("other"), operation))
    }

    @Test
    fun approvalIsForTheExactOperation() {
        coordinator.resolve(begin(), approved = true)

        assertFalse(coordinator.consume(setOf("client"), SignOperation.bytes(1, byteArrayOf(1, 2, 4))))
        assertFalse(coordinator.consume(setOf("client"), SignOperation.bytes(2, byteArrayOf(1, 2, 3))))
    }

    @Test
    fun approvalExpires() {
        coordinator.resolve(begin(), approved = true)
        now = 1_001

        assertFalse(coordinator.consume(setOf("client"), operation))
    }

    @Test
    fun paymentValuesAllMatter() {
        val payment = SignOperation.payment(1, "RECEIVER", 5, "{}")

        assertEquals(payment, SignOperation.payment(1, "RECEIVER", 5, "{}"))
        assertNotEquals(payment, SignOperation.payment(1, "OTHER", 5, "{}"))
        assertNotEquals(payment, SignOperation.payment(1, "RECEIVER", 6, "{}"))
        assertNotEquals(payment, SignOperation.payment(1, "RECEIVER", 5, "{\"a\":1}"))
    }

    @Test
    fun requestIsGoneOnceSettled() {
        val id = begin()
        assertTrue(coordinator.request(id) != null)

        coordinator.resolve(id, approved = true)

        assertNull(coordinator.request(id))
    }
}
