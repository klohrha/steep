import TestMetadata.services
import com.fasterxml.jackson.module.kotlin.readValue
import db.PluginRegistry
import db.PluginRegistryFactory
import helper.ConsecutiveID
import helper.JsonUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.json.JsonObject
import model.plugins.OutputAdapterPlugin
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * Tests for [ProcessChainGenerator]
 * @author Michel Kraemer
 */
class ProcessChainGeneratorTest {
  companion object {
    private data class T(val workflowName: String, val resultsName: String = workflowName)

    private val tests = listOf(
        // Test if a simple workflow with a single service can be converted
        // correctly
        T("singleService"),

        // Test if a simple workflow with a two independent services is
        // converted to two process chains
        T("twoIndependentServices"),

        // Test if a simple workflow with a two dependent services is converted
        // to a single process chain
        T("twoDependentServices"),

        // Test if a simple workflow with a two dependent services is converted
        // to a single process chain even if the services are in reverse order
        // in the workflow
        T("twoDependentServicesReverse", "twoDependentServices"),

        // Test if a simple workflow with a three dependent services is
        // converted to a single process chain
        T("threeDependentServices"),

        // Test if a simple workflow with four services is converted to two
        // process chains
        T("twoProcessChains"),

        // Test if a simple workflow with a four services is converted to two
        // process chains even if the results of the first process chain arrive
        // earlier than those of the second
        T("twoProcessChains", "twoProcessChainsAsyncResults"),

        // Test if a workflow with a service that joins the results of two
        // preceding services can be converted
        T("join"),

        // Test if a workflow with a service that joins the results of two
        // preceding services. Test if the process chains are generated
        // correctly even if the results of the first service arrive earlier
        // than those of the second.
        T("join", "joinAsyncResults"),

        // Test if a simple workflow with a two independent services that read
        // the same file is converted to two process chains
        T("twoIndependentServicesSameFile"),

        // Test a workflow with two services that depend on the results of one
        // preceding service
        T("fork"),

        // Test a workflow with a service that produces two outputs and another
        // one depending on both these outputs
        T("twoOutputs"),

        // Test a workflow with a service that produces two outputs and two
        // subsequent services each of them depending on one of these outputs
        T("twoOutputsTwoServices"),

        // Test a workflow with a fork and a join
        T("diamond"),

        // Test a small graph
        T("smallGraph"),

        // Test if a missing parameter with a default value is correctly added
        // as an argument
        T("missingDefaultParameter"),

        // Test if a service requiring Docker is converted correctly
        T("serviceWithDocker"),

        // Test if a service requiring runtime arguments is converted correctly
        T("serviceWithRuntimeArgs"),

        // Test if a for-each action can be unrolled correctly
        T("forEach"),

        // Test if a for-each action with a pre-defined list of inputs can be
        // unrolled correctly
        T("forEachPredefinedList"),

        // Test if a for-each action can iterate over a predefined list of lists
        // and an inner join action can access the individual lists
        T("forEachPredefinedListOfLists"),

        // Test if a for-each action with a pre-defined list of inputs and two
        // sub-actions can be unrolled correctly
        T("forEachPredefinedListTwoServices"),

        // Test if nested for-each actions can be unrolled correctly
        T("forEachNested"),

        // Test if a subsequent for-each action can iterate over a list of
        // lists generated by an earlier embedded for-each action and it its
        // inner join action can access the individual lists
        T("forEachNestedListOfLists"),

        // Test if nested for-each actions that iterate over pre-defined lists
        // can be unrolled correctly (and in one step)
        T("forEachNestedPredefinedList"),

        // Test if nested for-each actions that iterate over pre-defined lists
        // can be unrolled correctly if the inner for-each action contains an
        // action that depends on the results of an action from the outer
        // for-each action
        T("forEachNestedPredefinedDependent"),

        // Test if a nested for-each action that depends on the enumerator of
        // the outer for-each action and whose actions depend on the results of
        // actions from the outer for-each action can be unrolled (in other
        // words: test if a complex situation with nested for-each actions can
        // be handled)
        T("forEachNestedComplex"),

        // Test for-each action with yield
        T("forEachYield"),

        // Test for-each action with yield and subsequent join
        T("forEachYieldJoin"),

        // Test if a subsequent action can receive a list of files as directory
        T("directoryInput"),

        // Test if a subsequent action in a separate process chain can receive
        // a list of files as directory
        T("directoryInputProcessChains"),

        // Test that the results of a nested for action can be merged to a
        // subsequent join
        T("forEachYieldForEach"),

        // Test if output variables can have prefixes defined
        T("outputPrefix")

        //  TODO test complex graph

        //  TODO test missing service metadata

        //  TODO test wrong cardinality

        //  TODO test missing parameters

        //  TODO test wrong parameter names

    )

    /**
     * Provides arguments for all unit tests to JUnit
     */
    @JvmStatic
    @Suppress("UNUSED")
    fun argumentProvider(): Stream<Arguments> = tests.flatMap {
      listOf(
          Arguments.arguments(it.workflowName, it.resultsName, true),
          Arguments.arguments(it.workflowName, it.resultsName, false)
      )
    }.stream()
  }

  data class Expected(
      val chains: List<ProcessChain>,
      val results: Map<String, List<Any>>
  )

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.json").readText()
    return JsonUtils.mapper.readValue(fixture)
  }

  private fun readProcessChains(name: String): List<Expected> {
    val fixture = javaClass.getResource("fixtures/${name}_result.json").readText()
    return JsonUtils.mapper.readValue(fixture)
  }

  @ParameterizedTest
  @MethodSource("argumentProvider")
  fun testAll(workflowName: String, resultsName: String = workflowName,
      persistState: Boolean = false) {
    val workflow = readWorkflow(workflowName)
    val expectedChains = readProcessChains(resultsName)

    var json = JsonObject()
    val idgen = ConsecutiveID()
    var generator = ProcessChainGenerator(workflow, "/tmp/", services, idgen)
    assertThat(generator.isFinished()).isFalse()

    if (persistState) {
      json = generator.persistState()
    }

    var results = mapOf<String, List<Any>>()
    for (expected in expectedChains) {
      if (persistState) {
        generator = ProcessChainGenerator(workflow, "/tmp/", services, idgen)
        generator.loadState(json)
      }

      val processChains = generator.generate(results)
      assertThat(processChains).isEqualTo(expected.chains)
      results = expected.results

      if (persistState) {
        json = generator.persistState()
      }
    }

    if (persistState) {
      generator = ProcessChainGenerator(workflow, "/tmp/", services, idgen)
      generator.loadState(json)
    }

    val processChains = generator.generate(results)
    assertThat(processChains).isEmpty()
    assertThat(generator.isFinished()).isTrue()
  }

  /**
   * Test if a for-each action with a subsequent cp fails due to a
   * cardinality error
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYieldCardinalityError(persistState: Boolean) {
    assertThatThrownBy {
      testAll("forEachYieldCardinalityError", persistState = persistState)
    }.isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("cardinality")
  }

  /**
   * Test a custom output adapter splits a process chain
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun outputAdapter(persistState: Boolean) {
    val pluginRegistry: PluginRegistry = mockk()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry

    val p = OutputAdapterPlugin(name = "custom", scriptFile = "custom.kts",
        supportedDataType = "custom")
    every { pluginRegistry.findOutputAdapter(any()) } returns null
    every { pluginRegistry.findOutputAdapter("custom") } returns p

    try {
      testAll("outputAdapter", persistState = persistState)
    } finally {
      unmockkAll()
    }
  }
}
