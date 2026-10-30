package com.nexus.agent.domain.router

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelRouterTest {

    private class FakeClient(override val supportedModels: Set<String>) : ModelClient {
        var lastModel: String? = null
        override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
            lastModel = model
            return Result.success("reply")
        }

        override suspend fun completeStream(
            history: List<ChatMessage>,
            model: String,
            onDelta: suspend (String) -> Unit
        ): Result<String> {
            lastModel = model
            onDelta("streamed")
            return Result.success("streamed")
        }
    }

    @Test
    fun defaultsToFlashModelWhenRequestedNull() = runBlocking {
        val client = FakeClient(
            setOf(AiModelRouter.MODEL_DEEPSEEK_FLASH, AiModelRouter.MODEL_DEEPSEEK_V4_PRO)
        )
        val router = AiModelRouter(listOf(client))
        val result = router.routeRequest(emptyList(), null)
        assertTrue(result.isSuccess)
        assertEquals(AiModelRouter.MODEL_DEEPSEEK_FLASH, client.lastModel)
    }

    @Test
    fun routesToRequestedModel() = runBlocking {
        val client = FakeClient(
            setOf(AiModelRouter.MODEL_DEEPSEEK_FLASH, AiModelRouter.MODEL_DEEPSEEK_V4_PRO)
        )
        val router = AiModelRouter(listOf(client))
        router.routeRequest(emptyList(), AiModelRouter.MODEL_DEEPSEEK_V4_PRO)
        assertEquals(AiModelRouter.MODEL_DEEPSEEK_V4_PRO, client.lastModel)
    }

    @Test
    fun failsForUnsupportedModel() = runBlocking {
        val client = FakeClient(setOf(AiModelRouter.MODEL_DEEPSEEK_FLASH))
        val router = AiModelRouter(listOf(client))
        val result = router.routeRequest(emptyList(), "gemini-pro")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedModelException)
    }

    @Test
    fun routesStreamToProvider() = runBlocking {
        val client = FakeClient(setOf(AiModelRouter.MODEL_DEEPSEEK_FLASH))
        val router = AiModelRouter(listOf(client))
        val deltas = mutableListOf<String>()
        val result = router.routeStream(emptyList(), null) { deltas.add(it) }
        assertTrue(result.isSuccess)
        assertEquals(listOf("streamed"), deltas)
    }
}
