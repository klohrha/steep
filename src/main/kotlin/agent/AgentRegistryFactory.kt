package agent

/**
 * Creates [AgentRegistry] objects
 * @author Michel Kraemer
 */
object AgentRegistryFactory {
  /**
   * Create a new [AgentRegistry]
   * @return the [AgentRegistry]
   */
  fun create(): AgentRegistry = LocalAgentRegistry()
}