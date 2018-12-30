package model.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the workflow model
 * @author Michel Kraemer
 */
class WorkflowTest {
  /**
   * Test if a workflow can be read correctly
   */
  @Test
  fun read() {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("/fixtures/LS1_2datasets.json").readText()
    val workflow = mapper.readValue<Workflow>(fixture)

    assertThat(workflow.name).isEqualTo("Land showcase 1.1")
    assertThat(workflow.vars).hasSize(19)
    assertThat(workflow.actions).hasSize(10)

    val action0 = workflow.actions[0]
    assertThat(action0).isExactlyInstanceOf(ExecuteAction::class.java)
    val execAction0 = action0 as ExecuteAction
    assertThat(execAction0.service).isEqualTo("ResamplingOfPointCloud")
    assertThat(execAction0.inputs).isEqualTo(listOf(
        Parameter(id = "input_file_name", variable = Variable(
            id = "PointCloudParent0",
            value = "/CNR_IMATI/Liguria-LAS/LiDAR-145/20100902_E_3/S1C1_strip003.las"
        ))
    ))
    assertThat(execAction0.outputs).isEqualTo(listOf(
        Parameter(id = "output_file_name", variable = Variable(
            id = "Resampling0"
        ))
    ))
    assertThat(execAction0.parameters).isEqualTo(listOf(
        Parameter(id = "resampling_resolution", variable = Variable(
            id = "resolution",
            value = 10
        ))
    ))

    val action2 = workflow.actions[2]
    assertThat(action2).isExactlyInstanceOf(StoreAction::class.java)
    val storeAction2 = action2 as StoreAction
    assertThat(storeAction2.inputs).isEqualTo(listOf(Variable("OutlierFiltering0")))

    val action4 = workflow.actions[4]
    assertThat(action4).isExactlyInstanceOf(ForEachAction::class.java)
    val forAction4 = action4 as ForEachAction
    assertThat(forAction4.input).isEqualTo(Variable("metadata0"))
    assertThat(forAction4.enumerator).isEqualTo(Variable("result0"))
    assertThat(forAction4.actions).hasSize(2)

    val action4x0 = forAction4.actions[0]
    assertThat(action4x0).isExactlyInstanceOf(ExecuteAction::class.java)
    val execAction4x0 = action4x0 as ExecuteAction
    assertThat(execAction4x0.service).isEqualTo("MultiresolutionTriangulation")
    assertThat(execAction4x0.inputs).isEqualTo(listOf(
        Parameter(id = "inputjsfile", variable = Variable(
            id = "result0"
        ))
    ))
    assertThat(execAction4x0.outputs).isEqualTo(listOf(
        Parameter(id = "outputjsfile", variable = Variable(
            id = "MultiResolutionTriangulation0"
        ))
    ))
    assertThat(execAction4x0.parameters).isEmpty()

    val action4x1 = forAction4.actions[1]
    assertThat(action4x1).isExactlyInstanceOf(StoreAction::class.java)
    val storeAction4x1 = action4x1 as StoreAction
    assertThat(storeAction4x1.inputs).isEqualTo(listOf(Variable("MultiResolutionTriangulation0")))

    assertThat(storeAction4x1.inputs[0]).isSameAs(execAction4x0.outputs[0].variable)
    assertThat(storeAction4x1.inputs[0]).isSameAs(workflow.vars[11])
    assertThat(execAction4x0.inputs[0].variable).isSameAs(forAction4.enumerator)
    assertThat(execAction4x0.inputs[0].variable).isSameAs(workflow.vars[10])
  }
}