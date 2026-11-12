package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class RoutinePlanTest {
    private fun parse(steps: List<Any?>, extra: Map<String, Any?> = emptyMap()) =
        RoutinePlan.parse(Args(mapOf("app" to "example.app", "steps" to steps) + extra))

    @Test fun validatesTheWholePlanBeforeReturningAnythingExecutable() {
        val valid = mapOf("op" to "tap", "target" to "Continue")
        assertTrue(parse(listOf(valid, mapOf("op" to "shell", "command" to "anything"))) is Either.Left)
        assertTrue(parse(listOf(valid, "not an object")) is Either.Left)
        assertTrue(parse(List(61) { valid }) is Either.Left)
        assertTrue(parse(emptyList()) is Either.Left)
        assertTrue(parse(List(60) { valid }) is Either.Right)
    }

    @Test fun onlyKnownFieldsAndBoundedWaitsAreAccepted() {
        for (step in listOf(
            mapOf("op" to "tap", "target" to "Continue", "x" to 5),
            mapOf("op" to "wait", "ms" to -1),
            mapOf("op" to "wait", "ms" to 10001),
            mapOf("op" to "wait", "ms" to 1, "text" to "Ready"),
            mapOf("op" to "wait", "ms" to 1, "timeoutMs" to 500),
            mapOf("op" to "wait", "text" to "Ready", "timeoutMs" to 249),
            mapOf("op" to "wait", "text" to "Ready", "timeoutMs" to 10001),
            mapOf("op" to "scroll", "direction" to "down", "max" to 21),
            mapOf("op" to "key", "key" to "home"),
            mapOf("op" to "type", "target" to "Name", "text" to "x".repeat(2001)),
            mapOf("op" to "type", "target" to "Name", "text" to "ok", "submit" to "true"),
        )) assertTrue(step.toString(), parse(listOf(step)) is Either.Left)
        assertTrue(parse(listOf(mapOf("op" to "wait", "ms" to 0))) is Either.Right)
        assertTrue(parse(listOf(mapOf("op" to "wait", "text" to "Ready", "timeoutMs" to 250))) is Either.Right)
        assertTrue(parse(listOf(mapOf("op" to "key", "key" to "back"))) is Either.Right)
    }

    @Test fun resumptionMustNameAnInRangeStepWithAPagePrecondition() {
        val steps = listOf(mapOf("op" to "snapshot"), mapOf("op" to "tap", "target" to "Continue"))
        for (start in listOf(0, 2, 3)) assertTrue(parse(steps, mapOf("startAt" to start)) is Either.Left)
        val guarded = steps.dropLast(1) + mapOf("op" to "tap", "target" to "Continue", "requireText" to "Step 2")
        val parsed = parse(guarded, mapOf("startAt" to 2)) as Either.Right<RoutinePlan>
        assertEquals(2, parsed.value.startAt)
        assertEquals("Step 2", parsed.value.steps[1].a().str("requireText"))
    }

    @Test fun previewsDoNotDiscloseTypedContents() {
        val secret = "private draft only"
        val parsed = parse(listOf(mapOf("op" to "type", "target" to "Message", "text" to secret)), mapOf("dryRun" to true)) as Either.Right<RoutinePlan>
        assertTrue(parsed.value.dryRun)
        assertFalse(parsed.value.steps.single().summary().contains(secret))
        assertTrue(parsed.value.steps.single().summary().contains("${secret.length} chars"))
        assertTrue(RoutinePlan.parse(Args(mapOf("app" to "not-a-package", "steps" to listOf(mapOf("op" to "snapshot"))))) is Either.Left)
    }
}
