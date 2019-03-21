package db

import db.InMemoryPersistentMap.Companion.PERSISTENTMAP_PREFIX
import helper.JsonUtils
import io.vertx.core.Vertx
import model.workflow.Variable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Tests [InMemoryPersistentMap]
 * @author Michel Kraemer
 */
class InMemoryPersistentMapTest : PersistentMapTest() {
  private lateinit var vertx: Vertx

  /**
   * Set up the unit tests
   */
  @BeforeEach
  fun setUp(vertx: Vertx) {
    this.vertx = vertx
  }

  /**
   * Clear the local map after each test
   */
  @AfterEach
  fun tearDown(vertx: Vertx) {
    val lm = vertx.sharedData().getLocalMap<String, Any>(
        PERSISTENTMAP_PREFIX + PERSISTENT_MAP_NAME)
    lm.clear()
  }

  override suspend fun <V> createMap(name: String, cls: Class<V>): PersistentMap<V> {
    return InMemoryPersistentMap<V>(name, vertx).load(cls)
  }

  override suspend fun prepareLoadString(vertx: Vertx): Map<String, String> {
    val lm = vertx.sharedData().getLocalMap<String, String>(
        PERSISTENTMAP_PREFIX + PERSISTENT_MAP_NAME)
    lm["0"] = JsonUtils.mapper.writeValueAsString("B")
    lm["1"] = JsonUtils.mapper.writeValueAsString("C")
    return mapOf("0" to "B", "1" to "C")
  }

  override suspend fun prepareLoadVariable(vertx: Vertx): Map<String, Variable> {
    val v1 = Variable(value = "A")
    val v2 = Variable(value = "B")
    val lm = vertx.sharedData().getLocalMap<String, String>(
        PERSISTENTMAP_PREFIX + PERSISTENT_MAP_NAME)
    lm["0"] = JsonUtils.mapper.writeValueAsString(v1)
    lm["1"] = JsonUtils.mapper.writeValueAsString(v2)
    return mapOf("0" to v1, "1" to v2)
  }

  override suspend fun verifySize(vertx: Vertx, expectedSize: Int) {
    val lm = vertx.sharedData().getLocalMap<String, String>(
        PERSISTENTMAP_PREFIX + PERSISTENT_MAP_NAME)
    assertThat(lm).hasSize(expectedSize)
  }

  override suspend fun <V> verifyPersist(vertx: Vertx, expectedMap: Map<String, V>,
      expectedSize: Int) {
    val lm = vertx.sharedData().getLocalMap<String, String>(
        PERSISTENTMAP_PREFIX + PERSISTENT_MAP_NAME)
    assertThat(lm).hasSize(expectedSize)
    for ((k, v) in expectedMap) {
      assertThat(lm).contains(entry(k, JsonUtils.mapper.writeValueAsString(v)))
    }
  }
}
