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

    private fun chat(steps: List<Any?>, extra: Map<String, Any?> = emptyMap()) =
        RoutinePlan.parse(Args(mapOf("schema" to 2, "apps" to listOf("example.app"), "steps" to steps) + extra))

    @Test fun chatSchemaReusesBoundedTaskValidationAndRejectsBackgroundAiWork() {
        val tap = mapOf("op" to "tap", "selector" to mapOf("text" to "Continue", "match" to "exact"))
        val nested = mapOf("op" to "if", "condition" to mapOf("text" to "Ready"), "then" to listOf(
            mapOf("op" to "repeat", "count" to 3, "steps" to listOf(tap, mapOf("op" to "break")))))
        assertTrue(chat(listOf(nested, mapOf("op" to "yield"))) is Either.Right)
        for (bad in listOf(
            mapOf("op" to "ai", "prompt" to "new session"),
            mapOf("op" to "plugin", "tool" to "message"),
            mapOf("op" to "repeat", "count" to 101, "steps" to listOf(tap)),
            mapOf("op" to "repeat", "count" to 0, "steps" to listOf(tap)),
            mapOf("op" to "tap", "selector" to mapOf("text" to "Continue", "unique" to false)),
            mapOf("op" to "tap", "selector" to mapOf("text" to "Continue", "invented" to true)),
            mapOf("op" to "open", "app" to "other.app"),
            mapOf("op" to "break"),
            mapOf("op" to "scroll", "direction" to "down", "until" to mapOf("text" to "Done")),
        )) assertTrue(bad.toString(), chat(listOf(tap, bad)) is Either.Left)
        assertTrue(chat(listOf(tap), mapOf("forbiddenTargets" to "send")) is Either.Left)
        assertTrue(chat(listOf(tap), mapOf("apps" to "example.app")) is Either.Left)
        assertTrue(chat(listOf(tap), mapOf("startAt" to 2)) is Either.Left)
    }

    @Test fun chatSchemaRequiresNumericVersionAndRejectsUnknownTopLevelFields() {
        val tap = mapOf("op" to "tap", "target" to "Continue")
        assertTrue(chat(listOf(tap), mapOf("schema" to "2")) is Either.Left)
        assertTrue(chat(listOf(tap), mapOf("schema" to 2.1)) is Either.Left)
        assertTrue(chat(listOf(tap), mapOf("unexpected" to true)) is Either.Left)
    }

    @Test fun chatGestureKeepsItsRuntimeOperationAndArgumentsAfterValidation() {
        val gesture = mapOf("op" to "gesture", "observation" to "shot-1", "steps" to listOf(
            mapOf("kind" to "tap", "x" to 12, "y" to 34)))
        val parsed = chat(listOf(gesture)) as Either.Right<RoutinePlan>
        assertEquals("gesture", parsed.value.steps.single().args["_chatOp"])
        val parsedStroke = ((parsed.value.steps.single().args["steps"] as List<*>).single() as Map<*, *>)
        val originalStroke = ((gesture["steps"] as List<*>).single() as Map<*, *>)
        assertEquals((originalStroke["x"] as Number).toInt(), (parsedStroke["x"] as Number).toInt())
        assertEquals((originalStroke["y"] as Number).toInt(), (parsedStroke["y"] as Number).toInt())
        assertEquals("shot-1", parsed.value.steps.single().args["observation"])
    }

    @Test fun chatPlanDetachesNestedCallerDataAndPreviewsNeverExposeText() {
        val inner = mutableMapOf<String, Any?>("op" to "type", "target" to "Name", "text" to "private draft")
        val source = mutableListOf<Any?>(inner)
        val parsed = chat(source, mapOf("dryRun" to true)) as Either.Right<RoutinePlan>
        inner["text"] = "changed"
        assertEquals("private draft", parsed.value.steps.single().args["text"])
        assertFalse(parsed.value.steps.single().summary().contains("private draft"))
    }

    @Test fun chatExamplesUseTheRealParserContract() {
        var root = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(6) { if (!java.io.File(root, "examples/phone-chat").isDirectory) root = root.parentFile ?: root }
        val examples = java.io.File(root, "examples/phone-chat").listFiles { f -> f.extension == "json" }
        assertNotNull("Repository chat examples were not found", examples)
        assertTrue(examples!!.isNotEmpty())
        for (file in examples) {
            val input = AutomationPlan.obj(Json.parse(file.readText()))
            assertTrue(file.name, RoutinePlan.parse(Args(input)) is Either.Right)
        }
    }
}
